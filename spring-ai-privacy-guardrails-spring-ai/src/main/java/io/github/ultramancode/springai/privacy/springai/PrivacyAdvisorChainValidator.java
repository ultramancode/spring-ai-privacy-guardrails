package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundarySpec;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Validates the privacy advisors registered by {@link PrivacyChatClientConfigurer}
 * and the tool advisor's position in the sorted request chain. Also verifies that
 * one common model request boundary contains the configured privacy stage and
 * follows the managed privacy advisors.
 *
 * <p>Validation runs for each call or stream subscription so advisors added to individual
 * requests are included. Priority ordering places this check before the managed privacy advisors.</p>
 */
final class PrivacyAdvisorChainValidator implements CallAdvisor, StreamAdvisor, PriorityOrdered {

    private final List<Advisor> managedPrivacyAdvisors;
    private final int expectedToolOrder;
    private final PrivacyModelRequestStage privacyStage;

    PrivacyAdvisorChainValidator(List<Advisor> managedPrivacyAdvisors,
            PrivacyModelRequestStage privacyStage, int expectedToolOrder) {
        this.managedPrivacyAdvisors = List.copyOf(managedPrivacyAdvisors);
        this.expectedToolOrder = expectedToolOrder;
        this.privacyStage = privacyStage;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        validate(request, chain.getCallAdvisors());
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            validate(request, chain.getStreamAdvisors());
            return chain.nextStream(request);
        });
    }

    private void validate(ChatClientRequest request, List<? extends Advisor> requestAdvisors) {
        int lastManagedAdvisorIndex = -1;
        int toolContextIndex = -1;
        int toolCallValidationIndex = -1;
        for (Advisor managedAdvisor : this.managedPrivacyAdvisors) {
            int advisorIndex = requireManagedAdvisorIndex(requestAdvisors, managedAdvisor);
            if (advisorIndex <= lastManagedAdvisorIndex) {
                throw conflict("Privacy advisor order changed: " + managedAdvisor.getName()
                        + " must follow the preceding managed privacy advisor");
            }
            lastManagedAdvisorIndex = advisorIndex;
            if (managedAdvisor instanceof PrivacyToolContextAdvisor) {
                toolContextIndex = advisorIndex;
            }
            if (managedAdvisor instanceof PrivacyToolCallValidationAdvisor) {
                toolCallValidationIndex = advisorIndex;
            }
        }

        int modelBoundaryIndex = requireModelBoundaryIndex(requestAdvisors);
        if (modelBoundaryIndex <= lastManagedAdvisorIndex) {
            throw conflict("Privacy model stage must follow all managed privacy advisors");
        }

        validateToolAdvisorPlacement(request, requestAdvisors, toolContextIndex, toolCallValidationIndex);
    }

    private int requireModelBoundaryIndex(List<? extends Advisor> requestAdvisors) {
        int modelBoundaryIndex = -1;
        for (int i = 0; i < requestAdvisors.size(); i++) {
            if (ModelRequestBoundarySpec.containsStage(requestAdvisors.get(i), privacyStage)) {
                if (modelBoundaryIndex >= 0) {
                    throw conflict("Privacy requires exactly one managed model stage");
                }
                modelBoundaryIndex = i;
            }
        }
        if (modelBoundaryIndex < 0) {
            throw conflict("Privacy advisor layout is missing the registered model stage");
        }
        return modelBoundaryIndex;
    }

    private void validateToolAdvisorPlacement(ChatClientRequest request, List<? extends Advisor> requestAdvisors,
            int toolContextIndex, int toolCallValidationIndex) {
        // Spring AI rejects multiple ToolAdvisors when it builds the request chain.
        int toolAdvisorIndex = -1;
        for (int i = 0; i < requestAdvisors.size(); i++) {
            Advisor advisor = requestAdvisors.get(i);
            if (advisor instanceof ToolAdvisor) {
                if (advisor.getOrder() != this.expectedToolOrder) {
                    throw conflict("Privacy advisor layout requires the tool advisor at the planned order "
                            + this.expectedToolOrder);
                }
                toolAdvisorIndex = i;
            }
        }
        if (toolAdvisorIndex < 0) {
            boolean hasTools = request.prompt().getOptions() instanceof ToolCallingChatOptions options
                    && options.getToolCallbacks() != null
                    && !options.getToolCallbacks().isEmpty();
            if (hasTools) {
                throw conflict("Privacy advisor layout requires a tool advisor for requests with tools");
            }
            return;
        }
        if (toolAdvisorIndex <= toolContextIndex || toolAdvisorIndex >= toolCallValidationIndex) {
            throw conflict("Privacy advisor order must place tool context before the tool advisor "
                    + "and response validation after it; priority ordering is included in this check");
        }
    }

    private int requireManagedAdvisorIndex(List<? extends Advisor> requestAdvisors, Advisor managedAdvisor) {
        int sameClassCount = 0;
        int advisorIndex = -1;
        // An advisor of the same class may have different configuration.
        // Require the registered instance and reject duplicates of that class.
        for (int i = 0; i < requestAdvisors.size(); i++) {
            Advisor advisor = requestAdvisors.get(i);
            if (advisor.getClass() == managedAdvisor.getClass()) {
                sameClassCount++;
            }
            if (advisor == managedAdvisor) {
                advisorIndex = i;
            }
        }
        if (sameClassCount != 1 || advisorIndex < 0) {
            throw conflict("Privacy advisor layout requires exactly one managed " + managedAdvisor.getName());
        }
        return advisorIndex;
    }

    private static PrivacyGuardrailException conflict(String message) {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT, PrivacyPhase.SESSION, message);
    }

    @Override
    public String getName() {
        return "PrivacyAdvisorChainValidator";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

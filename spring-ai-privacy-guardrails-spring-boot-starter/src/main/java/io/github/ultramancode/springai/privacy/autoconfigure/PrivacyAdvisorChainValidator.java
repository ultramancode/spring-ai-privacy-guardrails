package io.github.ultramancode.springai.privacy.autoconfigure;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallValidationAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolContextAdvisor;
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
 * Validates starter-managed privacy advisors and tool advisor placement in the sorted request chain.
 *
 * <p>Validation runs for each call or stream subscription so advisors added to individual
 * requests are included. Priority ordering places this check before the managed privacy advisors.</p>
 */
final class PrivacyAdvisorChainValidator implements CallAdvisor, StreamAdvisor, PriorityOrdered {

    private final List<Advisor> managedAdvisors;
    private final int expectedToolOrder;

    PrivacyAdvisorChainValidator(List<Advisor> managedAdvisors, int expectedToolOrder) {
        this.managedAdvisors = List.copyOf(managedAdvisors);
        this.expectedToolOrder = expectedToolOrder;
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
        int previousAdvisorIndex = -1;
        int toolContextIndex = -1;
        int toolCallValidationIndex = -1;
        for (Advisor managedAdvisor : this.managedAdvisors) {
            int advisorIndex = requireManagedAdvisorIndex(requestAdvisors, managedAdvisor);
            if (advisorIndex <= previousAdvisorIndex) {
                throw conflict("Privacy advisor order changed: " + managedAdvisor.getName()
                        + " must follow the preceding managed privacy boundary");
            }
            previousAdvisorIndex = advisorIndex;
            if (managedAdvisor instanceof PrivacyToolContextAdvisor) {
                toolContextIndex = advisorIndex;
            }
            if (managedAdvisor instanceof PrivacyToolCallValidationAdvisor) {
                toolCallValidationIndex = advisorIndex;
            }
        }

        int toolAdvisorIndex = -1;
        for (int i = 0; i < requestAdvisors.size(); i++) {
            Advisor advisor = requestAdvisors.get(i);
            if (advisor instanceof ToolAdvisor) {
                if (toolAdvisorIndex >= 0 || advisor.getOrder() != this.expectedToolOrder) {
                    throw conflict("Privacy advisor layout requires one tool advisor at the planned order "
                            + this.expectedToolOrder);
                }
                toolAdvisorIndex = i;
            }
        }
        if (toolAdvisorIndex < 0) {
            boolean hasTools = request.prompt().getOptions() instanceof ToolCallingChatOptions options
                    && options.getToolCallbacks() != null && !options.getToolCallbacks().isEmpty();
            if (hasTools) {
                throw conflict("Privacy advisor layout requires a tool advisor for requests with tools");
            }
            return;
        }
        if (!(toolContextIndex < toolAdvisorIndex && toolAdvisorIndex < toolCallValidationIndex)) {
            throw conflict("Privacy advisor order must place tool context before the tool advisor "
                    + "and response validation after it; priority ordering is included in this check");
        }
    }

    private int requireManagedAdvisorIndex(List<? extends Advisor> requestAdvisors, Advisor managedAdvisor) {
        int sameClassCount = 0;
        int advisorIndex = -1;
        // A same-class replacement may have different configuration; require the
        // registered instance and reject duplicate advisors of that class.
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

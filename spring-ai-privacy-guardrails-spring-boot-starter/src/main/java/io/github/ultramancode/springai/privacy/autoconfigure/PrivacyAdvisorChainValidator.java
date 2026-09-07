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

/** Validates the managed boundaries in the chain Spring AI actually sorted for this request. */
final class PrivacyAdvisorChainValidator implements CallAdvisor, StreamAdvisor, PriorityOrdered {

    private final List<Advisor> boundaries;
    private final int toolOrder;

    PrivacyAdvisorChainValidator(List<Advisor> boundaries, int toolOrder) {
        this.boundaries = List.copyOf(boundaries);
        this.toolOrder = toolOrder;
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

    private void validate(ChatClientRequest request, List<? extends Advisor> advisors) {
        int previous = -1;
        int toolContext = -1;
        int toolValidation = -1;
        for (Advisor boundary : this.boundaries) {
            int position = managedPosition(advisors, boundary);
            if (position <= previous) {
                throw conflict("Privacy advisor order changed: " + boundary.getName()
                        + " must follow the preceding managed privacy boundary");
            }
            previous = position;
            if (boundary instanceof PrivacyToolContextAdvisor) {
                toolContext = position;
            }
            if (boundary instanceof PrivacyToolCallValidationAdvisor) {
                toolValidation = position;
            }
        }

        int toolPosition = -1;
        for (int i = 0; i < advisors.size(); i++) {
            Advisor advisor = advisors.get(i);
            if (advisor instanceof ToolAdvisor) {
                if (toolPosition >= 0 || advisor.getOrder() != this.toolOrder) {
                    throw conflict("Privacy advisor layout requires one tool advisor at the planned order "
                            + this.toolOrder);
                }
                toolPosition = i;
            }
        }
        if (toolPosition < 0) {
            boolean hasTools = request.prompt().getOptions() instanceof ToolCallingChatOptions options
                    && options.getToolCallbacks() != null && !options.getToolCallbacks().isEmpty();
            if (hasTools) {
                throw conflict("Privacy advisor layout requires a tool advisor for requests with tools");
            }
            return;
        }
        if (!(toolContext < toolPosition && toolPosition < toolValidation)) {
            throw conflict("Privacy advisor order must place tool context before the tool advisor "
                    + "and response validation after it; priority ordering is included in this check");
        }
    }

    private int managedPosition(List<? extends Advisor> advisors, Advisor boundary) {
        int count = 0;
        int position = -1;
        for (int i = 0; i < advisors.size(); i++) {
            Advisor advisor = advisors.get(i);
            if (advisor.getClass() == boundary.getClass()) {
                count++;
            }
            if (advisor == boundary) {
                position = i;
            }
        }
        if (count != 1 || position < 0) {
            throw conflict("Privacy advisor layout requires exactly one managed " + boundary.getName());
        }
        return position;
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

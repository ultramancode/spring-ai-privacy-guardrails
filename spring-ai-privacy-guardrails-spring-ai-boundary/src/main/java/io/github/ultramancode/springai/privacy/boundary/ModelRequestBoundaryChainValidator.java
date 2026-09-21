package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.PriorityOrdered;
import reactor.core.publisher.Flux;

/** Validates the complete request chain before tool loops or other managed stages start. */
final class ModelRequestBoundaryChainValidator implements CallAdvisor, StreamAdvisor, PriorityOrdered {
    private final ModelRequestBoundaryAdvisor boundary;

    ModelRequestBoundaryChainValidator(ModelRequestBoundaryAdvisor boundary) {
        this.boundary = boundary;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        boundary.validate(chain.getCallAdvisors(), false);
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            boundary.validate(chain.getStreamAdvisors(), false);
            return chain.nextStream(request);
        });
    }

    @Override
    public String getName() {
        return "ModelRequestBoundaryChainValidator";
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }
}

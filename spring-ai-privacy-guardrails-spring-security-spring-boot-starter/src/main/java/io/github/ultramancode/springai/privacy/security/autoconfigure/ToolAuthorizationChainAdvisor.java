package io.github.ultramancode.springai.privacy.security.autoconfigure;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.security.authorization.AuthorizationDeniedException;
import reactor.core.publisher.Flux;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rejects tool advisors not registered by this client's secured builder. */
final class ToolAuthorizationChainAdvisor implements CallAdvisor, StreamAdvisor, PriorityOrdered {

    // Track advisor identity with weak references so this registry does not prevent garbage collection.
    private final ReferenceQueue<ToolCallingAdvisor> collectedAdvisorReferences = new ReferenceQueue<>();
    private final Set<AdvisorReference> registeredAdvisors = new HashSet<>();

    synchronized void register(ToolCallingAdvisor advisor) {
        removeCollectedAdvisors();
        this.registeredAdvisors.add(new AdvisorReference(advisor, this.collectedAdvisorReferences));
    }

    private synchronized boolean isRegistered(Advisor advisor) {
        removeCollectedAdvisors();
        return advisor instanceof ToolCallingAdvisor toolAdvisor
                && this.registeredAdvisors.contains(new AdvisorReference(toolAdvisor, null));
    }

    private void removeCollectedAdvisors() {
        for (Object reference; (reference = this.collectedAdvisorReferences.poll()) != null;) {
            this.registeredAdvisors.remove(reference);
        }
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
        // Spring AI rejects multiple ToolAdvisors when it builds the request chain.
        List<? extends Advisor> toolAdvisors = requestAdvisors.stream()
                .filter(ToolAdvisor.class::isInstance).toList();
        boolean hasTools = request.prompt().getOptions() instanceof ToolCallingChatOptions options
                && options.getToolCallbacks() != null && !options.getToolCallbacks().isEmpty();
        if (toolAdvisors.isEmpty() && !hasTools) {
            return;
        }
        if (toolAdvisors.isEmpty() || !isRegistered(toolAdvisors.get(0))) {
            throw new AuthorizationDeniedException(
                    "Tool authorization requires this client's managed tool advisor; "
                            + "do not disable automatic registration or register a separate ToolAdvisor");
        }
    }

    @Override
    public String getName() {
        return "ToolAuthorizationChainAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static final class AdvisorReference extends WeakReference<ToolCallingAdvisor> {
        private final int identityHash;

        AdvisorReference(ToolCallingAdvisor advisor, ReferenceQueue<ToolCallingAdvisor> queue) {
            super(advisor, queue);
            this.identityHash = System.identityHashCode(advisor);
        }

        @Override
        public int hashCode() {
            return this.identityHash;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof AdvisorReference reference
                    && get() != null && get() == reference.get();
        }
    }
}

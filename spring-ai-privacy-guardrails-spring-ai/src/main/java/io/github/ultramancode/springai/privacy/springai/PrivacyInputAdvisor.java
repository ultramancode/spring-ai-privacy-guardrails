package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

import java.util.Objects;

/** Tokenizes prompt messages inside the request session owned by {@link PrivacyLifecycleAdvisor}. */
public final class PrivacyInputAdvisor implements CallAdvisor, StreamAdvisor {

    /** Tested position after default chat memory and before tool calling. */
    public static final int DEFAULT_ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50;

    private final PrivacyService privacyService;
    private final PrivacyMessageTransformer messageTransformer;
    private final int order;

    /**
     * Creates an input boundary at {@link #DEFAULT_ORDER}.
     *
     * @param privacyService service that owns request sessions and transformations
     */
    public PrivacyInputAdvisor(PrivacyService privacyService) {
        this(privacyService, DEFAULT_ORDER);
    }

    /**
     * Creates an input boundary at an application-selected advisor order.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param order Spring AI advisor order
     */
    public PrivacyInputAdvisor(PrivacyService privacyService, int order) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
        this.messageTransformer = new PrivacyMessageTransformer(privacyService);
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        PrivacyContextHandle handle = requireLifecycleHandle(request);
        return chain.nextCall(tokenizeRequest(request, handle));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        PrivacyContextHandle handle = requireLifecycleHandle(request);
        return chain.nextStream(tokenizeRequest(request, handle));
    }

    @Override
    public String getName() {
        return "PrivacyInputAdvisor";
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    private PrivacyContextHandle requireLifecycleHandle(ChatClientRequest request) {
        PrivacyRequestContextSupport.requireLifecycle(request, "PrivacyInputAdvisor");
        PrivacyContextHandle handle = PrivacyRequestContextSupport.findHandle(request)
                .orElseThrow(() -> new PrivacyGuardrailException(
                        PrivacyFailureCode.CONTEXT_REQUIRED,
                        PrivacyPhase.SESSION,
                        "PrivacyInputAdvisor requires PrivacyLifecycleAdvisor"
                ));
        if (!this.privacyService.isSessionActive(handle)) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.CONTEXT_NOT_ACTIVE,
                    PrivacyPhase.SESSION,
                    "Privacy context is unknown or already closed"
            );
        }
        return handle;
    }

    private ChatClientRequest tokenizeRequest(ChatClientRequest request, PrivacyContextHandle handle) {
        ChatClientRequest attached = PrivacyRequestContextSupport.attachHandle(request, handle);
        return this.messageTransformer.tokenize(handle, attached);
    }
}

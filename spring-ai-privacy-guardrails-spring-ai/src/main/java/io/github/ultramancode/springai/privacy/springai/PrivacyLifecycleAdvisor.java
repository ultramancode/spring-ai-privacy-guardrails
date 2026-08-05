package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * Owns the privacy session around the advisors that execute inside its configured position
 * and applies the optional output policy when control returns to that position.
 * The default order wraps the standard privacy bundle; applications remain responsible for
 * mutations performed by advisors placed outside it. Unrelated advisor classes and numeric
 * orders are application-owned and are not admission rules for this boundary.
 */
public final class PrivacyLifecycleAdvisor implements CallAdvisor, StreamAdvisor {

    /** Default outermost position for the request privacy-session lifetime. */
    public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE;

    private final PrivacyService privacyService;
    private final int order;

    /**
     * Creates a lifecycle boundary at {@link #DEFAULT_ORDER}.
     *
     * @param privacyService service that opens and closes request privacy sessions
     */
    public PrivacyLifecycleAdvisor(PrivacyService privacyService) {
        this(privacyService, DEFAULT_ORDER);
    }

    /**
     * Creates a lifecycle boundary at an application-selected advisor order.
     *
     * @param privacyService service that opens and closes request privacy sessions
     * @param order Spring AI advisor order
     */
    public PrivacyLifecycleAdvisor(PrivacyService privacyService, int order) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        PrivacyOutputAdvisor outputAdvisor = validateBundleAndFindOutputAdvisor(chain.getCallAdvisors());
        try (PrivacySession session = this.privacyService.openSession()) {
            ChatClientRequest activeRequest = PrivacyRequestContextSupport.attachLifecycle(
                    request,
                    session.handle()
            );
            ChatClientResponse response = chain.nextCall(activeRequest);
            if (outputAdvisor != null) {
                response = outputAdvisor.protectAtApplicationBoundary(session.handle(), response);
            }
            return PrivacyRequestContextSupport.stripInternalPrivacyEntries(response);
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        PrivacyOutputAdvisor outputAdvisor = validateBundleAndFindOutputAdvisor(chain.getStreamAdvisors());
        return Flux.using(
                this.privacyService::openSession,
                session -> {
                    ChatClientRequest activeRequest = PrivacyRequestContextSupport.attachLifecycle(
                            request,
                            session.handle()
                    );
                    Flux<ChatClientResponse> responses = chain.nextStream(activeRequest);
                    if (outputAdvisor != null) {
                        responses = outputAdvisor.protectAtApplicationBoundary(session.handle(), responses);
                    }
                    return responses.map(PrivacyRequestContextSupport::stripInternalPrivacyEntries);
                },
                PrivacySession::close
        );
    }

    @Override
    public String getName() {
        return "PrivacyLifecycleAdvisor";
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    private PrivacyOutputAdvisor validateBundleAndFindOutputAdvisor(List<? extends Advisor> advisors) {
        Objects.requireNonNull(advisors, "advisors must not be null");
        long lifecycleCount = advisors.stream()
                .filter(PrivacyLifecycleAdvisor.class::isInstance)
                .count();
        if (lifecycleCount != 1) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    PrivacyPhase.SESSION,
                    "PrivacyLifecycleAdvisor requires exactly one complete mandatory privacy advisor set"
            );
        }
        requireExactlyOne(advisors, PrivacyInputAdvisor.class);
        requireExactlyOne(advisors, PrivacyToolContextAdvisor.class);
        requireExactlyOne(advisors, PrivacyToolCallValidationAdvisor.class);
        requireExactlyOne(advisors, PrivacyModelBoundaryAdvisor.class);
        List<PrivacyOutputAdvisor> outputAdvisors = advisors.stream()
                .filter(PrivacyOutputAdvisor.class::isInstance)
                .map(PrivacyOutputAdvisor.class::cast)
                .toList();
        if (outputAdvisors.size() > 1) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    PrivacyPhase.OUTPUT_POLICY,
                    "PrivacyLifecycleAdvisor detected multiple output policy advisors"
            );
        }
        return outputAdvisors.isEmpty() ? null : outputAdvisors.get(0);
    }

    private void requireExactlyOne(
            List<? extends Advisor> advisors,
            Class<? extends Advisor> requiredType
    ) {
        long count = advisors.stream().filter(requiredType::isInstance).count();
        if (count != 1) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    PrivacyPhase.SESSION,
                    "PrivacyLifecycleAdvisor requires exactly one complete mandatory privacy advisor set"
            );
        }
    }
}

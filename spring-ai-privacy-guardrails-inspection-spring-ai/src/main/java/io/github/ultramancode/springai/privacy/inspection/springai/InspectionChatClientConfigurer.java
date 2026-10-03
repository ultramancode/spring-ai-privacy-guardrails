package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundarySpec;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Objects;

/**
 * Client-scoped runtime input inspection with optional final application-output inspection.
 * Use {@link ModelRequestBoundaryConfigurer#compose}
 * to combine privacy and inspection, or pass this contribution to a managed security factory.
 * No shared model or global client builder is modified.
 *
 * <p>Input inspectors receive each complete runtime payload as one segment, including
 * JSON keys, syntax and escape sequences. When combined with privacy processing,
 * they receive the privacy-transformed payload.</p>
 */
public final class InspectionChatClientConfigurer implements ModelRequestBoundaryConfigurer {

    private final InspectionService service;
    private final InspectionLimits limits;
    private final PrivacyProcessingStatusResolver privacyProcessingStatusResolver;
    private final InspectionObserver observer;
    private final InspectionOutputAdvisor outputAdvisor;

    /**
     * Creates input inspection with default limits and UNKNOWN privacy processing status.
     *
     * <p>For inspectors requiring privacy-processed input, supply a trusted resolver
     * through the four-argument constructor.
     *
     * @param service inspectors and policies to apply before each model call
     */
    public InspectionChatClientConfigurer(InspectionService service) {
        this(
                service,
                InspectionLimits.defaults(),
                PrivacyProcessingStatusResolver.unknown(),
                ignored -> {});
    }

    /**
     * Creates input inspection with explicit limits, privacy status resolution and observation.
     *
     * @param service inspectors and policies to apply before each model call
     * @param limits limits shared by inspectors for each model request
     * @param privacyProcessingStatusResolver trusted source of the input's privacy processing status
     * @param observer observer for inspection reports and hard failures
     */
    public InspectionChatClientConfigurer(
            InspectionService service,
            InspectionLimits limits,
            PrivacyProcessingStatusResolver privacyProcessingStatusResolver,
            InspectionObserver observer) {
        this(service, limits, privacyProcessingStatusResolver, observer, null);
    }

    private InspectionChatClientConfigurer(InspectionService service, InspectionLimits limits,
            PrivacyProcessingStatusResolver privacyProcessingStatusResolver, InspectionObserver observer,
            InspectionOutputAdvisor outputAdvisor) {
        this.service = Objects.requireNonNull(service, "service");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.privacyProcessingStatusResolver =
                Objects.requireNonNull(privacyProcessingStatusResolver, "privacyProcessingStatusResolver");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.outputAdvisor = outputAdvisor;
    }

    /**
     * Returns a new configurer with the supplied output advisor.
     * The advisor may use a separate service, policy, limits and observer.
     */
    public InspectionChatClientConfigurer withOutputInspection(InspectionOutputAdvisor advisor) {
        return new InspectionChatClientConfigurer(service, limits, privacyProcessingStatusResolver,
                observer, Objects.requireNonNull(advisor, "advisor"));
    }

    /**
     * Contributes one inspection stage that runs the service's configured inspectors
     * at the selected client's model request boundary.
     */
    @Override
    public void contributeToBoundary(ChatClient.Builder builder, ModelRequestBoundarySpec boundary) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(boundary, "boundary");
        boundary.inspection(new ContentInspectionStage(service, limits, privacyProcessingStatusResolver, observer));
        if (outputAdvisor != null) {
            builder.defaultAdvisors(outputAdvisor);
        }
    }
}

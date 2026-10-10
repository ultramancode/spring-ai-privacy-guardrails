package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundarySpec;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Objects;

/**
 * Adds input inspection to a selected {@link ChatClient}.
 * Use {@link #withOutputInspection(InspectionOutputAdvisor)} to inspect final responses as well.
 * Combine privacy protection and inspection with {@link ModelRequestBoundaryConfigurer#compose},
 * or pass this configurer to a security factory's {@code builderWithBoundary} method.
 * Inspection applies only to clients built with this configurer.
 *
 * <p>Input inspectors receive each complete text body as one segment, including
 * JSON keys, syntax and escape sequences. When combined with privacy processing,
 * they receive the body after privacy processing.</p>
 */
public final class InspectionChatClientConfigurer implements ModelRequestBoundaryConfigurer {

    private final InspectionService service;
    private final InspectionLimits limits;
    private final PrivacyProcessingStatusResolver privacyProcessingStatusResolver;
    private final InspectionObserver observer;
    private final InspectionOutputAdvisor outputAdvisor;

    /**
     * Creates a configurer with default limits and an input privacy status of UNKNOWN.
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
     * Creates a configurer with the supplied limits, privacy status resolver and observer.
     *
     * @param service inspectors and policies to apply before each model call
     * @param limits limits shared by inspectors for each model request
     * @param privacyProcessingStatusResolver trusted source of the input's privacy processing status
     * @param observer receives inspection reports and exceptions
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
     * Registers input inspection before each model call and adds the output advisor if configured.
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

package io.github.ultramancode.springai.privacy.inspection.autoconfigure;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionPolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionChatClientConfigurer;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionObserver;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionOutputAdvisor;
import io.github.ultramancode.springai.privacy.inspection.springai.PrivacyProcessingStatusResolver;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Predicate;

/**
 * Configures content inspection for selected clients.
 *
 * <p>Creates a default {@link InspectionService} from {@link ContentInspector} beans
 * unless the application provides a service. Apply the {@link InspectionChatClientConfigurer}
 * to each client builder that should use inspection.
 *
 * <p>The default service runs inspectors in Spring order. The default content policy
 * blocks findings. The default failure policy is
 * {@link InspectionFailurePolicy#FAIL_CLOSED}.
 * Output inspection is disabled by default. Enable it on the auto-configured
 * configurer with {@code spring.ai.inspection.output.enabled=true}.
 * Applications can provide policy, resolver and observer beans to customize inspection.
 */
@AutoConfiguration(
        afterName =
                "io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration")
@EnableConfigurationProperties(InspectionProperties.class)
public class InspectionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    InspectionPolicy inspectionPolicy() {
        return InspectionPolicy.blockFindings();
    }

    @Bean
    @ConditionalOnBean(ContentInspector.class)
    @ConditionalOnMissingBean
    InspectionService inspectionService(
            ObjectProvider<ContentInspector> inspectors,
            InspectionPolicy policy,
            InspectionProperties properties) {
        return new InspectionService(
                inspectors.orderedStream().toList(), policy,
                properties.getFailurePolicy(), properties.getFailurePolicyOverrides())
                .withPiiTokenSimplification(properties.getPiiTokenSimplificationInspectors());
    }

    @Bean
    @ConditionalOnBean(InspectionService.class)
    @ConditionalOnMissingBean
    InspectionChatClientConfigurer inspectionChatClientConfigurer(
            InspectionService service,
            InspectionProperties properties,
            ObjectProvider<PrivacyProcessingStatusResolver> resolver,
            ObjectProvider<InspectionObserver> observer) {
        InspectionObserver inspectionObserver = observer.getIfAvailable(InspectionObserver::noop);
        InspectionLimits limits = properties.limits();
        InspectionChatClientConfigurer configurer = new InspectionChatClientConfigurer(
                service,
                limits,
                resolver.getIfAvailable(PrivacyProcessingStatusResolver::unknown),
                inspectionObserver);
        InspectionProperties.Output output = properties.getOutput();
        if (output.isEnabled()) {
            InspectionOutputAdvisor outputAdvisor = new InspectionOutputAdvisor(
                    service, limits, inspectionObserver, output.getMaxFrames(), output.getStreamTimeout());
            return configurer.withOutputInspection(outputAdvisor);
        }
        return configurer;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(name = "privacyModelContentProtectionCheck")
    static class PrivacyIntegration {
        @Bean
        @ConditionalOnMissingBean(PrivacyProcessingStatusResolver.class)
        PrivacyProcessingStatusResolver privacyProcessingStatusResolver(
                @Qualifier("privacyModelContentProtectionCheck")
                        Predicate<ChatClientRequest> privacyProcessingCheck) {
            return request ->
                    privacyProcessingCheck.test(request)
                            ? ContentSegment.PrivacyProcessingStatus.PROCESSED
                            : ContentSegment.PrivacyProcessingStatus.UNKNOWN;
        }
    }
}

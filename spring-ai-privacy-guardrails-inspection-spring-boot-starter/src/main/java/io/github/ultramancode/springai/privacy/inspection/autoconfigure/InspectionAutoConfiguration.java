package io.github.ultramancode.springai.privacy.inspection.autoconfigure;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Predicate;

/**
 * Provides inspection services when {@code spring.ai.inspection.enabled=true}.
 * Applications supply {@link ContentInspector} beans or a custom {@link InspectionService}
 * and explicitly apply the {@link InspectionChatClientConfigurer} to each selected client builder.
 * Enabling this configuration does not modify other clients or create a model backend.
 *
 * <p>Inspectors run in Spring order. The default policy blocks findings and eligible
 * operational failures. Output inspection requires the separate
 * {@code spring.ai.inspection.output.enabled=true} setting.
 * Custom service, policy, resolver and observer beans can replace the defaults.
 */
@AutoConfiguration(
        afterName =
                "io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration")
@EnableConfigurationProperties(InspectionProperties.class)
@ConditionalOnProperty(prefix = "spring.ai.inspection", name = "enabled", havingValue = "true")
public class InspectionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    InspectionPolicy inspectionPolicy() {
        return InspectionPolicy.blockFindings();
    }

    @Bean
    @ConditionalOnMissingBean
    InspectionService inspectionService(
            ObjectProvider<ContentInspector> inspectors,
            InspectionPolicy policy,
            InspectionProperties properties) {
        return new InspectionService(
                inspectors.orderedStream().toList(), policy,
                properties.getFailurePolicy(), properties.getFailurePolicyOverrides());
    }

    @Bean
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
    @ConditionalOnBean(name = "privacyModelContentProtection")
    static class PrivacyIntegration {
        @Bean
        @ConditionalOnMissingBean(PrivacyProcessingStatusResolver.class)
        PrivacyProcessingStatusResolver privacyInspectionProcessingStatusResolver(
                @Qualifier("privacyModelContentProtection")
                        Predicate<ChatClientRequest> privacyProcessedMessages) {
            return request ->
                    privacyProcessedMessages.test(request)
                            ? ContentSegment.PrivacyProcessingStatus.PROCESSED
                            : ContentSegment.PrivacyProcessingStatus.UNKNOWN;
        }
    }
}

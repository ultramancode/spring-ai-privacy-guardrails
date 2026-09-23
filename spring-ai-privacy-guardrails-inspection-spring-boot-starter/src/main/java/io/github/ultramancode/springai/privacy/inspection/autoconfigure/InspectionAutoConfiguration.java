package io.github.ultramancode.springai.privacy.inspection.autoconfigure;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionPolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.springai.PrivacyProcessingStatusResolver;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionChatClientConfigurer;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionObserver;
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

/** Opt-in, explicit-client configuration. Enabling without inspectors fails startup. */
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
                inspectors.orderedStream().toList(), policy, properties.getFailurePolicy());
    }

    @Bean
    @ConditionalOnMissingBean
    InspectionChatClientConfigurer inspectionChatClientConfigurer(
            InspectionService service,
            InspectionProperties properties,
            ObjectProvider<PrivacyProcessingStatusResolver> resolver,
            ObjectProvider<InspectionObserver> observer) {
        return new InspectionChatClientConfigurer(
                service,
                properties.limits(),
                resolver.getIfAvailable(PrivacyProcessingStatusResolver::unknown),
                observer.getIfAvailable(InspectionObserver::noop));
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

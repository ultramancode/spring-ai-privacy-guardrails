package io.github.ultramancode.springai.privacy.inspection.autoconfigure;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionPolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionChatClientConfigurer;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionObserver;
import io.github.ultramancode.springai.privacy.inspection.springai.PrivacyProcessingStatusResolver;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Predicate;

/**
 * Provides content inspection services and client integration.
 *
 * <p>Creates a default {@link InspectionService} from {@link ContentInspector} beans
 * unless the application provides a service. The {@link InspectionChatClientConfigurer}
 * bean is created when a single service bean or a {@code @Primary} service is available.
 *
 * <p>The default service runs inspectors in Spring order. The default content policy
 * blocks findings. The default failure policy is
 * {@link InspectionFailurePolicy#FAIL_CLOSED}.
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
        ObjectProvider<ContentInspector> contentInspectors,
        InspectionPolicy inspectionPolicy,
        InspectionProperties inspectionProperties) {
        return new InspectionService(
            contentInspectors.orderedStream().toList(), inspectionPolicy,
            inspectionProperties.getFailurePolicy(), inspectionProperties.getFailurePolicyOverrides())
            .withPiiTokenSimplification(inspectionProperties.getPiiTokenSimplificationInspectors());
    }

    @Bean
    @ConditionalOnSingleCandidate(InspectionService.class)
    @ConditionalOnMissingBean
    InspectionChatClientConfigurer inspectionChatClientConfigurer(
        InspectionService inspectionService,
        ObjectProvider<PrivacyProcessingStatusResolver> privacyProcessingStatusResolverProvider,
        ObjectProvider<InspectionObserver> inspectionObserverProvider) {
        InspectionObserver inspectionObserver = inspectionObserverProvider.getIfAvailable(InspectionObserver::noop);
        InspectionLimits inspectionLimits = InspectionLimits.defaults();
        return new InspectionChatClientConfigurer(
            inspectionService,
            inspectionLimits,
            privacyProcessingStatusResolverProvider.getIfAvailable(PrivacyProcessingStatusResolver::unknown),
            inspectionObserver);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(name = "privacyModelContentProtectionCheck")
    static class PrivacyIntegration {
        @Bean
        @ConditionalOnMissingBean(PrivacyProcessingStatusResolver.class)
        PrivacyProcessingStatusResolver privacyProcessingStatusResolver(
                @Qualifier("privacyModelContentProtectionCheck")
                        Predicate<ChatClientRequest> privacyProcessingCheck) {
            return chatClientRequest ->
                privacyProcessingCheck.test(chatClientRequest)
                    ? ContentSegment.PrivacyProcessingStatus.PROCESSED
                    : ContentSegment.PrivacyProcessingStatus.UNKNOWN;
        }
    }
}

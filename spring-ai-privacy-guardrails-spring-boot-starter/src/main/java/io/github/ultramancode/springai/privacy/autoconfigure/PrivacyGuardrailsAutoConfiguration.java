package io.github.ultramancode.springai.privacy.autoconfigure;

import io.github.ultramancode.springai.privacy.core.EntityTypeRegistry;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailureObserver;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiResolutionPolicy;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.RegexPiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.RegexPiiRule;
import io.github.ultramancode.springai.privacy.springai.PrivacyInputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyLifecycleAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyModelBoundaryAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyResponseInspectionLimits;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolContextAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallValidationAdvisor;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/** Auto-configures core privacy services and the fixed Spring AI privacy boundary. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.ai.privacy", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PrivacyGuardrailsProperties.class)
public class PrivacyGuardrailsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PiiAnalysisOptions piiAnalysisOptions(PrivacyGuardrailsProperties properties) {
        PrivacyGuardrailsProperties.Analysis analysis = properties.getAnalysis();
        return PiiAnalysisOptions.builder()
                .language(analysis.getLanguage())
                .includedEntityTypes(analysis.getIncludedEntityTypes())
                .minimumScore(analysis.getMinimumScore())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    PiiResolutionPolicy piiResolutionPolicy(PrivacyGuardrailsProperties properties) {
        PrivacyGuardrailsProperties.Analysis analysis = properties.getAnalysis();
        return PiiResolutionPolicy.builder()
                .mode(analysis.getMode())
                .primaryProvider(analysis.getPrimaryProvider())
                .supplementalProviders(analysis.getSupplementalProviders())
                .failurePolicy(analysis.getFailurePolicy())
                .providerMinimumScores(analysis.getProviderMinimumScores())
                .typeConflictFallback(analysis.getTypeConflictFallback())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.privacy.regex", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(RegexPiiAnalyzer.class)
    RegexPiiAnalyzer regexPiiAnalyzer(PrivacyGuardrailsProperties properties) {
        List<PrivacyGuardrailsProperties.Regex.Rule> configuredRules = requireConfiguredRegexRules(properties);
        List<RegexPiiRule> rules = new ArrayList<>(configuredRules.size());
        for (PrivacyGuardrailsProperties.Regex.Rule rule : configuredRules) {
            rules.add(new RegexPiiRule(
                    rule.getEntityType(),
                    rule.getPattern(),
                    rule.getScore(),
                    rule.getCaptureGroup()
            ));
        }
        return new RegexPiiAnalyzer(rules);
    }

    private List<PrivacyGuardrailsProperties.Regex.Rule> requireConfiguredRegexRules(
            PrivacyGuardrailsProperties properties
    ) {
        List<PrivacyGuardrailsProperties.Regex.Rule> rules = properties.getRegex().getRules();
        if (rules.isEmpty()) {
            throw new IllegalStateException(
                    "spring.ai.privacy.regex.rules must contain at least one rule when regex is enabled"
            );
        }
        for (int index = 0; index < rules.size(); index++) {
            if (rules.get(index) == null) {
                throw new IllegalStateException(
                        "spring.ai.privacy.regex.rules[" + index + "] must not be null"
                );
            }
        }
        return rules;
    }

    @Bean
    @ConditionalOnMissingBean
    PrivacyService privacyService(
            ObjectProvider<PiiAnalyzer> analyzers,
            PiiAnalysisOptions options,
            ObjectProvider<EntityTypeRegistry> entityTypes,
            PiiResolutionPolicy resolutionPolicy,
            PrivacyGuardrailsProperties properties,
            ObjectProvider<PiiAnalyzerFailureObserver> failureObserver
    ) {
        List<PiiAnalyzer> configuredAnalyzers = analyzers.orderedStream().toList();
        if (configuredAnalyzers.isEmpty()) {
            throw new IllegalStateException(
                    "No PiiAnalyzer is configured. Enable regex, OpenNLP, Presidio, or provide a custom analyzer"
            );
        }
        return new PrivacyService(
                configuredAnalyzers,
                options,
                entityTypes.getIfAvailable(() -> new EntityTypeRegistry(
                        properties.getAnalysis().getEntityAliases()
                )),
                resolutionPolicy,
                failureObserver.getIfAvailable(PiiAnalyzerFailureObserver::noop)
        );
    }

    @Bean
    PrivacyChatClientConfigurer privacyChatClientConfigurer(
            PrivacyService privacyService,
            PrivacyToolCallbackFactory toolCallbackFactory,
            PrivacyGuardrailsProperties properties
    ) {
        PrivacyGuardrailsProperties.Output outputProperties = properties.getOutput();
        PrivacyResponseInspectionLimits responseInspectionLimits =
                properties.getResponseInspection().limits();
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(new PrivacyLifecycleAdvisor(privacyService));
        advisors.add(new PrivacyInputAdvisor(privacyService));
        if (outputProperties.isEnabled()) {
            advisors.add(new PrivacyOutputAdvisor(
                    privacyService,
                    outputProperties.getAction(),
                    outputProperties.getBlockExceptionMessage(),
                    responseInspectionLimits
            ));
        }
        advisors.add(new PrivacyToolContextAdvisor(privacyService, toolCallbackFactory));
        advisors.add(new PrivacyToolCallValidationAdvisor(
                privacyService,
                responseInspectionLimits
        ));
        advisors.add(new PrivacyModelBoundaryAdvisor(
                privacyService,
                toolCallbackFactory
        ));
        return new PrivacyChatClientConfigurer(advisors);
    }

    @Bean
    @ConditionalOnMissingBean
    ToolDisclosurePolicy toolDisclosurePolicy(PrivacyGuardrailsProperties properties) {
        return ToolDisclosurePolicy.byToolName(properties.getTools().getDisclosures());
    }

    @Bean
    @ConditionalOnMissingBean
    PrivacyToolCallbackFactory privacyToolCallbackFactory(
            PrivacyService privacyService,
            ToolDisclosurePolicy toolDisclosurePolicy
    ) {
        return new PrivacyToolCallbackFactory(privacyService, toolDisclosurePolicy);
    }

}

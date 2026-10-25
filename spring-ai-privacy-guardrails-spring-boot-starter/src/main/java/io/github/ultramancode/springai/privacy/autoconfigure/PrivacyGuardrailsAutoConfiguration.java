package io.github.ultramancode.springai.privacy.autoconfigure;

import io.github.ultramancode.springai.privacy.core.EntityTypeRegistry;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailureObserver;
import io.github.ultramancode.springai.privacy.core.PiiResolutionPolicy;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import io.github.ultramancode.springai.privacy.core.PiiRuleCatalog;
import io.github.ultramancode.springai.privacy.core.PiiRulePreset;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.RuleBasedPiiAnalyzer;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyEnforcementObserver;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.function.Predicate;

/** Auto-configures core privacy services and the starter-managed Spring AI privacy boundary. */
@AutoConfiguration
@EnableConfigurationProperties(PrivacyGuardrailsProperties.class)
public class PrivacyGuardrailsAutoConfiguration {

    /** Optional integrations consume a JDK contract without depending on this starter. */
    @Bean
    @ConditionalOnMissingBean(name = "privacyModelContentProtectionCheck")
    Predicate<ChatClientRequest> privacyModelContentProtectionCheck() {
        return PrivacyChatClientConfigurer::hasPrivacyProcessedMessages;
    }

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
    @ConditionalOnMissingBean(RuleBasedPiiAnalyzer.class)
    @ConditionalOnProperty(prefix = "spring.ai.privacy.rules", name = "enabled", havingValue = "true")
    RuleBasedPiiAnalyzer ruleBasedPiiAnalyzer(
        PrivacyGuardrailsProperties properties,
        ObjectProvider<PiiRule> rules,
        ObjectProvider<PiiRulePreset> presets
    ) {
        PiiRuleCatalog.Builder catalog = PiiRuleCatalog.builder().builtIns();
        catalog.addRules(rules.orderedStream().toList());
        presets.orderedStream().forEach(catalog::addPreset);
        PrivacyGuardrailsProperties.Rules ruleProperties = properties.getRules();
        return catalog.build().analyzerBuilder()
            .presets(ruleProperties.getPresets())
            .include(ruleProperties.getInclude())
            .exclude(ruleProperties.getExclude())
            .build();
    }

    @Bean
    @ConditionalOnBean(PiiAnalyzer.class)
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
                "No PiiAnalyzer is configured. Enable rules, OpenNLP, Presidio, or provide a custom analyzer"
            );
        }
        return new PrivacyService(
            configuredAnalyzers,
            options,
            entityTypes.getIfAvailable(() -> new EntityTypeRegistry(
                properties.getAnalysis().getEntityAliases()
            )),
            resolutionPolicy,
            failureObserver.getIfAvailable(PiiAnalyzerFailureObserver::noop),
            properties.getProcessing().limits()
        );
    }

    @Bean
    @ConditionalOnBean(PrivacyService.class)
    @ConditionalOnMissingBean
    PrivacyChatClientConfigurer privacyChatClientConfigurer(
        PrivacyService privacyService,
        PrivacyToolCallbackFactory toolCallbackFactory,
        ObjectProvider<PrivacyEnforcementObserver> enforcementObserver
    ) {
        PrivacyEnforcementObserver configuredEnforcementObserver = enforcementObserver
            .getIfAvailable(PrivacyEnforcementObserver::noop);
        return PrivacyChatClientConfigurer.builder(privacyService)
            .toolCallbackFactory(toolCallbackFactory)
            .enforcementObserver(configuredEnforcementObserver)
            .build();
    }

    @Bean
    @ConditionalOnMissingBean
    ToolDisclosurePolicy toolDisclosurePolicy() {
        return ToolDisclosurePolicy.denyAll();
    }

    @Bean
    @ConditionalOnBean(PrivacyService.class)
    @ConditionalOnMissingBean
    PrivacyToolCallbackFactory privacyToolCallbackFactory(
        PrivacyService privacyService,
        ToolDisclosurePolicy toolDisclosurePolicy,
        ObjectProvider<PrivacyEnforcementObserver> enforcementObserver
    ) {
        return new PrivacyToolCallbackFactory(
            privacyService,
            toolDisclosurePolicy,
            enforcementObserver.getIfAvailable(PrivacyEnforcementObserver::noop)
        );
    }

}

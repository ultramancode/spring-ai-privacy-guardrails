package io.github.ultramancode.springai.privacy.autoconfigure;

import io.github.ultramancode.springai.privacy.core.EntityTypeRegistry;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailureObserver;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiResolutionPolicy;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.RegexPiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.RegexPiiMatchValidator;
import io.github.ultramancode.springai.privacy.core.RegexPiiRule;
import io.github.ultramancode.springai.privacy.springai.PrivacyEnforcementObserver;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Auto-configures core privacy services and the fixed Spring AI privacy boundary. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.ai.privacy", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PrivacyGuardrailsProperties.class)
public class PrivacyGuardrailsAutoConfiguration {

    private static final Pattern REGEX_MATCH_VALIDATOR_ID_SYNTAX = Pattern.compile(
            "[a-z0-9]+(?:-[a-z0-9]+)*"
    );

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
    RegexPiiAnalyzer regexPiiAnalyzer(
            PrivacyGuardrailsProperties properties,
            List<RegexPiiMatchValidator> matchValidators
    ) {
        List<PrivacyGuardrailsProperties.Regex.Rule> configuredRules = requireConfiguredRegexRules(properties);
        Map<String, RegexPiiMatchValidator> matchValidatorsById = indexMatchValidators(matchValidators);
        List<RegexPiiRule> rules = new ArrayList<>(configuredRules.size());
        for (int index = 0; index < configuredRules.size(); index++) {
            PrivacyGuardrailsProperties.Regex.Rule rule = configuredRules.get(index);
            rules.add(new RegexPiiRule(
                    rule.getEntityType(),
                    rule.getPattern(),
                    rule.getScore(),
                    rule.getCaptureGroup(),
                    resolveMatchValidator(rule.getValidatorId(), index, matchValidatorsById)
            ));
        }
        return new RegexPiiAnalyzer(rules);
    }

    private Map<String, RegexPiiMatchValidator> indexMatchValidators(
            List<RegexPiiMatchValidator> matchValidators
    ) {
        Map<String, RegexPiiMatchValidator> validatorsById = new LinkedHashMap<>();
        for (RegexPiiMatchValidator matchValidator : matchValidators) {
            RegexPiiMatchValidator validator = Objects.requireNonNull(
                    matchValidator,
                    "RegexPiiMatchValidator beans must not be null"
            );
            String validatorId = requireValidValidatorId(
                    validator.id(),
                    "RegexPiiMatchValidator.id"
            );
            if (validatorsById.putIfAbsent(validatorId, validator) != null) {
                throw new IllegalStateException(
                        "Multiple RegexPiiMatchValidator beans use validator ID '"
                                + validatorId + "'"
                );
            }
        }
        return validatorsById;
    }

    private RegexPiiMatchValidator resolveMatchValidator(
            String configuredValidatorId,
            int ruleIndex,
            Map<String, RegexPiiMatchValidator> matchValidatorsById
    ) {
        if (configuredValidatorId == null) {
            return null;
        }
        String property = "spring.ai.privacy.regex.rules[" + ruleIndex + "].validator-id";
        String validatorId = requireValidValidatorId(configuredValidatorId, property);
        RegexPiiMatchValidator matchValidator = matchValidatorsById.get(validatorId);
        if (matchValidator == null) {
            throw new IllegalStateException(
                    property + " references unknown validator ID '" + validatorId + "'"
            );
        }
        return matchValidator;
    }

    private String requireValidValidatorId(String validatorId, String description) {
        if (validatorId == null || validatorId.isBlank()) {
            throw new IllegalStateException(description + " must not be blank");
        }
        if (!REGEX_MATCH_VALIDATOR_ID_SYNTAX.matcher(validatorId).matches()) {
            throw new IllegalStateException(
                    description + " must use lowercase ASCII letters and digits "
                            + "separated by single hyphens"
            );
        }
        return validatorId;
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
            PrivacyGuardrailsProperties properties,
            ObjectProvider<PrivacyEnforcementObserver> enforcementObserver
    ) {
        PrivacyEnforcementObserver configuredEnforcementObserver = enforcementObserver
                .getIfAvailable(PrivacyEnforcementObserver::noop);
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
                    responseInspectionLimits,
                    configuredEnforcementObserver
            ));
        }
        advisors.add(new PrivacyToolContextAdvisor(privacyService, toolCallbackFactory));
        advisors.add(new PrivacyToolCallValidationAdvisor(
                privacyService,
                responseInspectionLimits
        ));
        advisors.add(new PrivacyModelBoundaryAdvisor(
                privacyService,
                toolCallbackFactory,
                configuredEnforcementObserver,
                PrivacyModelBoundaryAdvisor.DEFAULT_ORDER
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

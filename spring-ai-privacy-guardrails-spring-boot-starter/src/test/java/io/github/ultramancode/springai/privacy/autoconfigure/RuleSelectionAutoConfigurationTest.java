package io.github.ultramancode.springai.privacy.autoconfigure;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import io.github.ultramancode.springai.privacy.core.PiiRuleCatalog;
import io.github.ultramancode.springai.privacy.core.PiiRulePreset;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.ResolvedPiiSpan;
import io.github.ultramancode.springai.privacy.core.RuleBasedPiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.rules.BuiltInPiiPresetNames;
import io.github.ultramancode.springai.privacy.core.rules.BuiltInPiiRuleIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RuleSelectionAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PrivacyGuardrailsAutoConfiguration.class,
                    PrivacyConfigurationDiagnosticsAutoConfiguration.class));

    @Test
    void selectsBuiltInPresetsAndAppliesExclusions() {
        this.runner.withPropertyValues("spring.ai.privacy.rules.enabled=true",
                "spring.ai.privacy.rules.presets=COMMON,KR", "spring.ai.privacy.rules.exclude=credit-card")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PrivacyService.class);
                    RuleBasedPiiAnalyzer analyzer = context.getBean(RuleBasedPiiAnalyzer.class);
                    assertThat(analyzer.ruleIds()).contains("email", "kr-mobile-phone", "kr-rrn")
                            .doesNotContain("credit-card");
                });
    }

    @Test
    void registrationDoesNotEnableRulesAndDisabledSelectionsAreNotResolved() {
        AtomicInteger calls = new AtomicInteger();
        this.runner.withBean("applicationRule", PiiRule.class, () -> countingRule("company-id", calls))
                .withPropertyValues("spring.ai.privacy.rules.enabled=false",
                        "spring.ai.privacy.rules.include=not-registered", "spring.ai.privacy.rules.presets=UNKNOWN")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(PiiAnalyzer.class)
                            .doesNotHaveBean(PrivacyService.class);
                    assertThat(calls).hasValue(0);
                });
        this.runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(PiiAnalyzer.class));
    }

    @Test
    void selectsRegisteredRuleWithoutAPreset() {
        AtomicInteger calls = new AtomicInteger();
        this.runner.withBean("applicationRule", PiiRule.class, () -> countingRule("company-id", calls))
                .withPropertyValues("spring.ai.privacy.rules.enabled=true", "spring.ai.privacy.rules.include=company-id")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(PiiRulePreset.class);
                    RuleBasedPiiAnalyzer analyzer = context.getBean(RuleBasedPiiAnalyzer.class);
                    assertThat(analyzer.ruleIds()).containsExactly("company-id");
                    assertThat(analyzer.analyze("x", PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults()))
                            .containsExactly(new PiiSpan("CUSTOM_ID", 0, 1, 0.8));
                    assertThat(calls).hasValue(1);
                });
    }

    @Test
    void userPresetSelectsPatternsWithValidationAndDirectDetectionAndExclusionPreventsExecution() {
        AtomicInteger included = new AtomicInteger();
        AtomicInteger excluded = new AtomicInteger();
        PatternDefinition employeeIdPattern = PatternDefinition.builder()
                .expression("EMP-[0-9]{4}")
                .score(0.9)
                .validator(candidate -> candidate.endsWith("1234"))
                .build();
        this.runner.withBean("pattern", PiiRule.class, () ->
                        PatternPiiRule.builder()
                                .id("employee-id")
                                .entityType("EMPLOYEE_ID")
                                .addPattern(employeeIdPattern)
                                .build())
                .withBean("direct", PiiRule.class, () -> countingRule("company-id", included))
                .withBean("excluded", PiiRule.class, () -> countingRule("unused-id", excluded))
                .withBean(PiiRulePreset.class, () ->
                        new PiiRulePreset("COMPANY", List.of("employee-id", "company-id", "unused-id")))
                .withPropertyValues("spring.ai.privacy.rules.enabled=true", "spring.ai.privacy.rules.presets=COMPANY",
                        "spring.ai.privacy.rules.exclude=unused-id")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RuleBasedPiiAnalyzer analyzer = context.getBean(RuleBasedPiiAnalyzer.class);
                    assertThat(analyzer.analyze("EMP-1234 EMP-9999", PiiAnalysisOptions.defaults(),
                            PrivacyProcessingLimits.defaults())).containsExactly(
                                    new PiiSpan("EMPLOYEE_ID", 0, 8, 0.9), new PiiSpan("CUSTOM_ID", 0, 1, 0.8));
                    assertThat(included).hasValue(1);
                    assertThat(excluded).hasValue(0);
                });
    }

    @Test
    void rejectsEmptyUnknownAndConflictingSelections() {
        for (String property : List.of("spring.ai.privacy.rules.include=unknown",
                "spring.ai.privacy.rules.presets=UNKNOWN", "spring.ai.privacy.rules.exclude=unknown")) {
            this.runner.withPropertyValues("spring.ai.privacy.rules.enabled=true", property)
                    .run(context -> assertThat(context).hasFailed());
        }
        this.runner.withPropertyValues("spring.ai.privacy.rules.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        this.runner.withBean(PiiRule.class, () -> countingRule(BuiltInPiiRuleIds.EMAIL, new AtomicInteger()))
                .withPropertyValues("spring.ai.privacy.rules.enabled=true", "spring.ai.privacy.rules.presets=COMMON")
                .run(context -> assertThat(context).hasFailed());
        this.runner.withBean(PiiRulePreset.class,
                        () -> new PiiRulePreset("KR", List.of(BuiltInPiiRuleIds.EMAIL)))
                .withPropertyValues("spring.ai.privacy.rules.enabled=true", "spring.ai.privacy.rules.presets=KR")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void userRuleBasedAnalyzerOverridesAutomaticSelectionAndUsesConfiguredScores() {
        this.runner.withUserConfiguration(CustomRulesConfiguration.class)
                .withPropertyValues("spring.ai.privacy.rules.enabled=true",
                        "spring.ai.privacy.rules.presets=UNKNOWN", "spring.ai.privacy.analysis.minimum-score=0.9")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RuleBasedPiiAnalyzer.class)
                            .hasSingleBean(PrivacyService.class).doesNotHaveBean("ruleBasedPiiAnalyzer");
                    RuleBasedPiiAnalyzer analyzer = context.getBean(RuleBasedPiiAnalyzer.class);
                    assertThat(analyzer).isSameAs(context.getBean("customRules"));
                    assertThat(analyzer.ruleIds()).containsExactly("credit-card", "email");
                    assertThat(analyzer.analyze("dev@example.com", PiiAnalysisOptions.defaults(),
                            PrivacyProcessingLimits.defaults()))
                            .containsExactly(new PiiSpan("EMAIL_ADDRESS", 0, 15, 0.95));
                    assertThat(context.getBean(PrivacyService.class)
                            .analyze("dev@example.com | 4111111111111111"))
                            .extracting(ResolvedPiiSpan::entityType).containsExactly("EMAIL_ADDRESS");
                });
        this.runner.withUserConfiguration(CustomRulesConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(RuleBasedPiiAnalyzer.class)
                        .hasSingleBean(PrivacyService.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void userPrivacyServiceCanSelectOneOfMultipleRulesAnalyzers(boolean automaticRulesEnabled) {
        PiiRuleCatalog catalog = PiiRuleCatalog.builder().builtIns().build();
        RuleBasedPiiAnalyzer emailAnalyzer = catalog.analyzerBuilder()
                .include(BuiltInPiiRuleIds.EMAIL)
                .build();
        RuleBasedPiiAnalyzer phoneAnalyzer = catalog.analyzerBuilder()
                .include(BuiltInPiiRuleIds.KR_MOBILE_PHONE)
                .build();
        PrivacyService service = new PrivacyService(List.of(emailAnalyzer), PiiAnalysisOptions.defaults());

        this.runner.withBean("emailAnalyzer", RuleBasedPiiAnalyzer.class, () -> emailAnalyzer)
                .withBean("phoneAnalyzer", RuleBasedPiiAnalyzer.class, () -> phoneAnalyzer)
                .withBean(PrivacyService.class, () -> service)
                .withPropertyValues("spring.ai.privacy.rules.enabled=" + automaticRulesEnabled)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PrivacyService.class);
                    assertThat(context.getBeansOfType(PiiAnalyzer.class)).hasSize(2);
                    PrivacyService configuredService = context.getBean(PrivacyService.class);
                    assertThat(configuredService).isSameAs(service);
                    assertThat(configuredService.analyze("dev@example.com 010-1234-5678"))
                            .extracting(ResolvedPiiSpan::entityType).containsExactly("EMAIL_ADDRESS");
                    assertThat(context.getBean("phoneAnalyzer", RuleBasedPiiAnalyzer.class)
                            .analyze("010-1234-5678", PiiAnalysisOptions.defaults(),
                                    PrivacyProcessingLimits.defaults()))
                            .extracting(PiiSpan::entityType).containsExactly("PHONE_NUMBER");
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsDuplicateProvidersInAutomaticallyConfiguredService(boolean automaticRulesEnabled) {
        RuleBasedPiiAnalyzer manual = PiiRuleCatalog.builder().builtIns().build()
                .analyzerBuilder().include(BuiltInPiiRuleIds.EMAIL).build();
        this.runner.withUserConfiguration(CustomRulesConfiguration.class)
                .withBean("additionalRules", PiiAnalyzer.class, () -> manual)
                .withPropertyValues("spring.ai.privacy.rules.enabled=" + automaticRulesEnabled)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Duplicate PII analyzer provider RULES");
                });
    }

    @Test
    void interfaceDeclaredRulesProviderMustDisableAutomaticSelection() {
        PiiAnalyzer manual = PiiRuleCatalog.builder().builtIns().build()
                .analyzerBuilder().include(BuiltInPiiRuleIds.EMAIL).build();
        this.runner.withBean("manual", PiiAnalyzer.class, () -> manual)
                .withPropertyValues("spring.ai.privacy.rules.enabled=true", "spring.ai.privacy.rules.include=email")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Duplicate PII analyzer provider RULES");
                });
        this.runner.withBean("manual", PiiAnalyzer.class, () -> manual)
                .withPropertyValues("spring.ai.privacy.rules.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PrivacyService.class));
    }

    @Test
    void otherProvidersCoexistWithAutomaticRules() {
        this.runner.withBean("external", PiiAnalyzer.class, () -> (text, options, limits) -> List.of())
                .withPropertyValues("spring.ai.privacy.rules.enabled=true", "spring.ai.privacy.rules.include=email")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PrivacyService.class);
                    assertThat(context.getBeansOfType(PiiAnalyzer.class)).hasSize(2);
                });
    }

    @Test
    void acceptsSelectionListsFromEnvironmentVariables() {
        this.runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                        "SPRING_AI_PRIVACY_RULES_ENABLED", "true",
                        "SPRING_AI_PRIVACY_RULES_PRESETS_0", "KR",
                        "SPRING_AI_PRIVACY_RULES_INCLUDE_0", "email",
                        "SPRING_AI_PRIVACY_RULES_EXCLUDE_0", "kr-rrn"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RuleBasedPiiAnalyzer.class).ruleIds())
                            .contains("kr-mobile-phone", "email").doesNotContain("kr-rrn");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomRulesConfiguration {
        @Bean
        RuleBasedPiiAnalyzer customRules() {
            return PiiRuleCatalog.builder()
                    .builtInsWithScores(Map.of(BuiltInPiiRuleIds.EMAIL, 0.95))
                    .build()
                    .analyzerBuilder().presets(BuiltInPiiPresetNames.COMMON).build();
        }
    }

    private static PiiRule countingRule(String ruleId, AtomicInteger calls) {
        return new PiiRule() {
            public String id() { return ruleId; }
            public Set<String> entityTypes() { return Set.of("CUSTOM_ID"); }
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
                calls.incrementAndGet();
                return List.of(new PiiSpan("CUSTOM_ID", 0, 1, 0.8));
            }
        };
    }
}

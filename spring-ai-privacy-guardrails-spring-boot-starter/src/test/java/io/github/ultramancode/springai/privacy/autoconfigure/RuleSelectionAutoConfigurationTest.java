package io.github.ultramancode.springai.privacy.autoconfigure;

import io.github.ultramancode.springai.privacy.core.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

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
                    assertThat(analyzer.ruleIds()).containsExactly("email", "kr-mobile-phone", "kr-rrn");
                });
    }

    @Test
    void registrationDoesNotEnableRulesAndDisabledSelectionsAreNotResolved() {
        AtomicInteger calls = new AtomicInteger();
        this.runner.withBean("applicationRule", PiiRule.class, () -> direct("company-id", calls))
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
    void userPresetSelectsPatternsWithValidationAndDirectDetectionAndExclusionPreventsExecution() {
        AtomicInteger included = new AtomicInteger();
        AtomicInteger excluded = new AtomicInteger();
        this.runner.withBean("pattern", PiiRule.class, () ->
                        new PatternPiiRule("employee-id", "EMPLOYEE_ID", "EMP-[0-9]{4}", 0.9, 0,
                                candidate -> candidate.endsWith("1234")))
                .withBean("direct", PiiRule.class, () -> direct("company-id", included))
                .withBean("excluded", PiiRule.class, () -> direct("unused-id", excluded))
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
        this.runner.withBean(PiiRule.class, () -> direct("email", new AtomicInteger()))
                .withPropertyValues("spring.ai.privacy.rules.enabled=true", "spring.ai.privacy.rules.presets=COMMON")
                .run(context -> assertThat(context).hasFailed());
        this.runner.withBean(PiiRulePreset.class, () -> new PiiRulePreset("KR", List.of("email")))
                .withPropertyValues("spring.ai.privacy.rules.enabled=true", "spring.ai.privacy.rules.presets=KR")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void manualRulesProviderRequiresDisablingAutomaticProviderButOtherProvidersCoexist() {
        RuleBasedPiiAnalyzer manual = PiiRuleCatalog.builder().builtIns().build()
                .select(List.of(), List.of("email"), List.of());
        this.runner.withBean("manual", PiiAnalyzer.class, () -> manual)
                .withPropertyValues("spring.ai.privacy.rules.enabled=true", "spring.ai.privacy.rules.include=email")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("Multiple RULES providers");
                });
        this.runner.withBean("manual", PiiAnalyzer.class, () -> manual)
                .withPropertyValues("spring.ai.privacy.rules.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PrivacyService.class));
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
                            .containsExactly("kr-mobile-phone", "email");
                });
    }

    private static PiiRule direct(String id, AtomicInteger calls) {
        return new PiiRule() {
            public String id() { return id; }
            public Set<String> entityTypes() { return Set.of("CUSTOM_ID"); }
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
                calls.incrementAndGet();
                return List.of(new PiiSpan("CUSTOM_ID", 0, 1, 0.8));
            }
        };
    }
}

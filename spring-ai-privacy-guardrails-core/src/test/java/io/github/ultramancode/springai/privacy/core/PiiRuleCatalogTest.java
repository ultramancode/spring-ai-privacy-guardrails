package io.github.ultramancode.springai.privacy.core;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import io.github.ultramancode.springai.privacy.core.rules.BuiltInPiiPresetNames;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class PiiRuleCatalogTest {
    @Test
    void buildsAnalyzerWithOrderedUniqueRules() {
        AtomicInteger selectedCalls = new AtomicInteger();
        PiiRule direct = rule("direct", text -> {
            selectedCalls.incrementAndGet();
            int start = text.indexOf("secret");
            return List.of(new PiiSpan("CUSTOM_ID", start, start + 6, 0.9));
        });
        PiiRule excluded = rule("excluded", text -> {
            throw new AssertionError("Excluded rule executed");
        });
        PiiRule supplemental = rule("supplemental", text -> List.of());
        PiiRule included = rule("included", text -> List.of());
        PiiRule inactive = rule("inactive", text -> {
            throw new AssertionError("Unselected rule executed");
        });
        PiiRuleCatalog catalog = PiiRuleCatalog.builder()
                .addRules(List.of(direct, excluded, supplemental, included, inactive))
                .addPreset(new PiiRulePreset("CUSTOM", List.of("direct", "excluded")))
                .addPreset(new PiiRulePreset("ADDITIONAL", List.of("supplemental", "excluded")))
                .build();
        RuleBasedPiiAnalyzer analyzer = catalog.analyzerBuilder()
                .presets("CUSTOM", "ADDITIONAL", "CUSTOM")
                .include("direct", "included")
                .exclude("excluded")
                .build();
        assertThat(analyzer.ruleIds()).containsExactly("direct", "supplemental", "included");
        assertThat(analyzer.presetNames()).containsExactly("CUSTOM", "ADDITIONAL");
        // Verify UTF-16 offsets: each Korean character uses one code unit and the emoji uses two.
        List<PiiSpan> spans = analyze(analyzer, "한글😀 secret");
        assertThat(spans).containsExactly(new PiiSpan("CUSTOM_ID", 5, 11, 0.9));
        assertThat(selectedCalls).hasValue(1);
        assertThat(catalog.ruleIds()).contains("excluded", "inactive");
    }

    @Test
    void addsRulesInOrderAndCopiesCollections() {
        PiiRule firstRule = rule("first", text -> List.of());
        PiiRule secondRule = rule("second", text -> List.of());
        PiiRule thirdRule = rule("third", text -> List.of());
        List<PiiRule> rules = new ArrayList<>(List.of(secondRule, thirdRule));
        PiiRuleCatalog.Builder builder = PiiRuleCatalog.builder()
                .addRule(firstRule)
                .addRules(rules);
        rules.clear();

        PiiRuleCatalog catalog = builder.build();

        assertThat(catalog.ruleIds()).containsExactly("first", "second", "third");
    }

    @Test
    void rejectsNullRuleCollectionsAndElements() {
        PiiRule existingRule = rule("existing", text -> List.of());
        PiiRule additionalRule = rule("additional", text -> List.of());
        PiiRuleCatalog.Builder builder = PiiRuleCatalog.builder().addRule(existingRule);

        assertThatNullPointerException().isThrownBy(() -> builder.addRules(null));
        assertThatNullPointerException().isThrownBy(() ->
                builder.addRules(Arrays.asList(additionalRule, null)));

        assertThat(builder.build().ruleIds()).containsExactly("existing");
    }

    @Test
    void rejectsInvalidCatalogDefinitions() {
        PiiRule rule = rule("custom", text -> List.of());
        assertThatIllegalArgumentException().isThrownBy(() ->
                PiiRuleCatalog.builder().addRule(rule).addRule(rule).build());
        assertThatIllegalArgumentException().isThrownBy(() ->
                PiiRuleCatalog.builder().addRule(rule).addRules(List.of(rule)).build());
        assertThatIllegalArgumentException().isThrownBy(() ->
                PiiRuleCatalog.builder().builtIns().addPreset(new PiiRulePreset("KR", List.of())).build())
                .withMessage("Duplicate preset name 'KR'");
        assertThatIllegalArgumentException().isThrownBy(() ->
                PiiRuleCatalog.builder().addPreset(new PiiRulePreset("CUSTOM", List.of("missing"))).build());
        PiiRulePreset preset = new PiiRulePreset("CUSTOM", List.of("custom"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                PiiRuleCatalog.builder().addRule(rule).addPreset(preset).addPreset(preset).build())
                .withMessage("Duplicate preset name 'CUSTOM'");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PiiRulePreset("invalid name", List.of("custom"))).withMessageContaining("preset name");
    }

    @Test
    void rejectsUnknownPresetsAndRuleIds() {
        PiiRuleCatalog catalog = PiiRuleCatalog.builder()
                .addRule(rule("custom", text -> List.of()))
                .addPreset(new PiiRulePreset("CUSTOM", List.of("custom")))
                .build();

        for (String unknownReference : List.of("missing", "invalid name", "", "x".repeat(65))) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    catalog.analyzerBuilder().presets(unknownReference).build())
                    .withMessageContaining("Unknown preset");
            assertThatIllegalArgumentException().isThrownBy(() ->
                    catalog.analyzerBuilder().presets("CUSTOM").include(unknownReference).build())
                    .withMessageContaining("Unknown rule ID");
            assertThatIllegalArgumentException().isThrownBy(() ->
                    catalog.analyzerBuilder().presets("CUSTOM").exclude(unknownReference).build())
                    .withMessageContaining("Unknown rule ID");
        }
    }

    @Test
    void rejectsAnalyzersWithoutRules() {
        PiiRuleCatalog catalog = PiiRuleCatalog.builder()
                .addRule(rule("custom", text -> List.of()))
                .addPreset(new PiiRulePreset("CUSTOM", List.of("custom")))
                .build();

        assertThatIllegalArgumentException().isThrownBy(() -> catalog.analyzerBuilder().build());
        assertThatIllegalArgumentException().isThrownBy(() -> catalog.analyzerBuilder()
                .presets("CUSTOM")
                .exclude("custom")
                .build());
    }

    @Test
    void analyzerBuildersCopyInputsAndReplacePreviousSelections() {
        PiiRuleCatalog catalog = PiiRuleCatalog.builder()
                .addRules(List.of(
                        rule("first", text -> List.of()),
                        rule("second", text -> List.of()),
                        rule("third", text -> List.of()),
                        rule("fourth", text -> List.of())))
                .addPreset(new PiiRulePreset("CUSTOM", List.of("first", "second")))
                .addPreset(new PiiRulePreset("ADDITIONAL", List.of("fourth")))
                .build();
        List<String> presetNames = new ArrayList<>(List.of("CUSTOM"));
        List<String> includedRuleIds = new ArrayList<>(List.of("third"));
        List<String> excludedRuleIds = new ArrayList<>(List.of("second"));
        PiiRuleCatalog.AnalyzerBuilder analyzerBuilder = catalog.analyzerBuilder()
                .presets(presetNames)
                .include(includedRuleIds)
                .exclude(excludedRuleIds);
        presetNames.clear();
        includedRuleIds.clear();
        excludedRuleIds.clear();
        RuleBasedPiiAnalyzer first = analyzerBuilder.build();

        RuleBasedPiiAnalyzer second = analyzerBuilder.presets("ADDITIONAL")
                .include("second")
                .exclude("first")
                .build();

        assertThat(first.ruleIds()).containsExactly("first", "third");
        assertThat(first.presetNames()).containsExactly("CUSTOM");
        assertThat(second.ruleIds()).containsExactly("fourth", "second");
        assertThat(second.presetNames()).containsExactly("ADDITIONAL");
    }

    @Test
    void catalogsAreSnapshotsAndRulesSharingAnEntityTypeRemainSelectable() {
        List<String> ruleIds = new ArrayList<>(List.of("first"));
        PiiRulePreset preset = new PiiRulePreset("CUSTOM", ruleIds);
        ruleIds.clear();
        PiiRuleCatalog.Builder builder = PiiRuleCatalog.builder()
                .addRule(rule("first", text -> List.of()))
                .addPreset(preset);
        PiiRuleCatalog first = builder.build();
        PiiRuleCatalog second = builder.addRule(rule("second", text -> List.of()))
                .addPreset(new PiiRulePreset("ADDITIONAL", List.of("second", "first")))
                .build();
        assertThat(first.ruleIds()).containsExactly("first");
        assertThat(first.presets()).containsExactly(new PiiRulePreset("CUSTOM", List.of("first")));
        assertThat(second.presets()).containsExactly(
                new PiiRulePreset("CUSTOM", List.of("first")),
                new PiiRulePreset("ADDITIONAL", List.of("second", "first")));
        assertThat(second.analyzerBuilder().include("first", "second").build().ruleIds())
                .containsExactly("first", "second");
    }

    @Test
    void presetsKeepTheirOwnRuleOrderAndMayShareRules() {
        PiiRuleCatalog catalog = PiiRuleCatalog.builder()
                .addRules(List.of(
                        rule("first", text -> List.of()),
                        rule("second", text -> List.of()),
                        rule("third", text -> List.of())))
                .addPreset(new PiiRulePreset("CUSTOM", List.of("third", "first")))
                .addPreset(new PiiRulePreset("SHARED", List.of("first", "second")))
                .build();

        assertThat(catalog.presets()).containsExactly(
                new PiiRulePreset("CUSTOM", List.of("third", "first")),
                new PiiRulePreset("SHARED", List.of("first", "second")));
        assertThat(catalog.analyzerBuilder().presets("CUSTOM", "SHARED").build().ruleIds())
                .containsExactly("third", "first", "second");
    }

    @Test
    void rulesCanBeSelectedDirectlyWithoutAPreset() {
        PatternDefinition employeeIdPattern = PatternDefinition.builder()
                .expression("EMP-[0-9]{4}")
                .score(0.9)
                .build();
        PiiRule rule = PatternPiiRule.builder()
                .id("employee-id")
                .entityType("EMPLOYEE_ID")
                .addPattern(employeeIdPattern)
                .build();
        PiiRuleCatalog catalog = PiiRuleCatalog.builder().addRule(rule).build();

        assertThat(catalog.presets()).isEmpty();
        RuleBasedPiiAnalyzer analyzer = catalog.analyzerBuilder().include("employee-id").build();
        assertThat(analyze(analyzer, "EMP-1234"))
                .containsExactly(new PiiSpan("EMPLOYEE_ID", 0, 8, 0.9));
    }

    @Test
    void countryPresetSelectionIsIndependentOfAnalysisLanguage() {
        PiiRuleCatalog catalog = PiiRuleCatalog.builder().builtIns().build();
        RuleBasedPiiAnalyzer analyzer = catalog.analyzerBuilder().presets(BuiltInPiiPresetNames.KR).build();
        PiiAnalysisOptions options = PiiAnalysisOptions.builder().language("en").build();

        assertThat(analyzer.analyze("010-1234-5678", options, PrivacyProcessingLimits.defaults()))
                .containsExactly(new PiiSpan("PHONE_NUMBER", 0, 13, 0.85));
    }

    private static List<PiiSpan> analyze(PiiAnalyzer analyzer, String text) {
        return analyzer.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }

    private static PiiRule rule(String ruleId, Function<String, List<PiiSpan>> detector) {
        return new PiiRule() {
            @Override
            public String id() {
                return ruleId;
            }

            @Override
            public Set<String> entityTypes() {
                return Set.of("CUSTOM_ID");
            }

            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
                return detector.apply(text);
            }
        };
    }
}

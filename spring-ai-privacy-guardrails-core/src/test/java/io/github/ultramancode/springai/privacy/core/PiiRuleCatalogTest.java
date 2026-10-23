package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

class PiiRuleCatalogTest {
    @Test
    void selectionExpandsInOrderDeduplicatesAndNeverExecutesExcludedOrMerelyRegisteredRules() {
        AtomicInteger excludedCalls = new AtomicInteger();
        AtomicInteger selectedCalls = new AtomicInteger();
        PiiRule direct = rule("direct", text -> {
            selectedCalls.incrementAndGet();
            int start = text.indexOf("secret");
            return List.of(new PiiSpan("CUSTOM_ID", start, start + 6, 0.9));
        });
        PiiRule excluded = rule("excluded", text -> {
            excludedCalls.incrementAndGet();
            throw new AssertionError("Excluded rule executed");
        });
        PiiRuleCatalog catalog = PiiRuleCatalog.builder().builtIns()
                .rule(direct).rule(excluded)
                .preset(new PiiRulePreset("CUSTOM", List.of("direct", "excluded")))
                .build();
        RuleBasedPiiAnalyzer analyzer = catalog.select(
                List.of("CUSTOM", "KR", "CUSTOM"), List.of("direct", "email"), List.of("excluded", "kr-rrn"));
        assertThat(analyzer.ruleIds()).containsExactly("direct", "kr-mobile-phone", "email");
        assertThat(analyzer.presetNames()).containsExactly("CUSTOM", "KR");
        List<PiiSpan> spans = analyze(analyzer, "한글😀 secret");
        assertThat(spans).containsExactly(new PiiSpan("CUSTOM_ID", 5, 11, 0.9));
        assertThat(selectedCalls).hasValue(1);
        assertThat(excludedCalls).hasValue(0);
        assertThat(catalog.ruleIds()).contains("excluded", "credit-card");
    }

    @Test
    void validatesAllReferencesAndRejectsOverridesAndEmptySelections() {
        PiiRule rule = rule("custom", text -> List.of());
        assertThatIllegalArgumentException().isThrownBy(() ->
                PiiRuleCatalog.builder().rule(rule).rule(rule).build());
        assertThatIllegalArgumentException().isThrownBy(() ->
                PiiRuleCatalog.builder().builtIns().preset(new PiiRulePreset("KR", List.of())).build());
        assertThatIllegalArgumentException().isThrownBy(() ->
                PiiRuleCatalog.builder().preset(new PiiRulePreset("CUSTOM", List.of("missing"))).build());
        PiiRuleCatalog catalog = PiiRuleCatalog.builder().builtIns().build();
        assertThatIllegalArgumentException().isThrownBy(() -> catalog.select(List.of("NO"), List.of(), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> catalog.select(List.of("KR"), List.of("no"), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> catalog.select(List.of("KR"), List.of(), List.of("no")));
        assertThatIllegalArgumentException().isThrownBy(() -> catalog.select(List.of(), List.of(), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> catalog.select(
                List.of("KR"), List.of(), List.of("kr-mobile-phone", "kr-rrn")));
        assertThat(catalog.select(List.of("KR"), List.of(), List.of()).ruleIds()).doesNotContain("email");
    }

    @Test
    void catalogsAreSnapshotsAndRulesSharingAnEntityTypeRemainSelectable() {
        PiiRuleCatalog.Builder builder = PiiRuleCatalog.builder().rule(rule("first", text -> List.of()));
        PiiRuleCatalog first = builder.build();
        PiiRuleCatalog second = builder.rule(rule("second", text -> List.of())).build();
        assertThat(first.ruleIds()).containsExactly("first");
        assertThat(second.select(List.of(), List.of("first", "second"), List.of()).ruleIds())
                .containsExactly("first", "second");
    }

    private static List<PiiSpan> analyze(PiiAnalyzer analyzer, String text) {
        return analyzer.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }

    private static PiiRule rule(String id, Function<String, List<PiiSpan>> detector) {
        return new PiiRule() {
            public String id() { return id; }
            public Set<String> entityTypes() { return Set.of("CUSTOM_ID"); }
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
                return detector.apply(text);
            }
        };
    }
}

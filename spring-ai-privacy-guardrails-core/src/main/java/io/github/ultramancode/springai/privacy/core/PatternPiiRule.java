package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;

/**
 * A rule composed of trusted Java regular expressions and optional candidate checks.
 * A predicate receives the captured candidate. Returning {@code false} discards it.
 * Accepted candidates retain the capture range and configured score.
 * Java regular expressions may perform excessive backtracking.
 */
public final class PatternPiiRule implements PiiRule {
    private final String id;
    private final String entityType;
    private final Set<String> countries;
    private final List<CompiledPattern> patterns;

    /** Creates one pattern without additional candidate validation. */
    public PatternPiiRule(String id, String entityType, String expression, double score, int captureGroup) {
        this(id, entityType, expression, score, captureGroup, null);
    }

    /** Creates one pattern with an optional thread-safe candidate predicate. */
    public PatternPiiRule(String id, String entityType, String expression, double score,
                          int captureGroup, Predicate<String> validator) {
        this(id, entityType, List.of(new Pattern(expression, score, captureGroup, validator)), Set.of());
    }

    /** Creates a rule with multiple patterns and explicit country metadata. */
    public PatternPiiRule(String id, String entityType, List<Pattern> patterns, Set<String> countries) {
        this.id = PiiRuleSupport.requireRuleId(id);
        this.entityType = EntityTypeRegistry.requireValidEntityType(entityType);
        this.countries = Set.copyOf(Objects.requireNonNull(countries, "countries must not be null"));
        if (Objects.requireNonNull(patterns, "patterns must not be null").isEmpty()) {
            throw new IllegalArgumentException("at least one pattern is required");
        }
        this.patterns = patterns.stream().map(CompiledPattern::new).toList();
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public Set<String> entityTypes() {
        return Set.of(this.entityType);
    }

    @Override
    public Set<String> countries() {
        return this.countries;
    }

    @Override
    public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        List<PiiSpan> spans = new ArrayList<>();
        for (CompiledPattern compiled : this.patterns) {
            PiiRuleSupport.checkInterrupted();
            Matcher matcher = compiled.expression().matcher(text);
            while (matcher.find()) {
                PiiRuleSupport.checkInterrupted();
                Pattern pattern = compiled.definition();
                int start = matcher.start(pattern.captureGroup());
                int end = matcher.end(pattern.captureGroup());
                if (start < 0 || end <= start) {
                    throw new IllegalStateException("Rule '" + this.id + "' produced an empty capture group");
                }
                if (pattern.validator() != null
                        && !pattern.validator().test(matcher.group(pattern.captureGroup()))) {
                    continue;
                }
                if (spans.size() >= limits.maxResultSpans()) {
                    throw PiiRuleSupport.limitExceeded();
                }
                spans.add(new PiiSpan(this.entityType, start, end, pattern.score()));
            }
        }
        return List.copyOf(spans);
    }

    /**
     * One Java regular expression and its candidate validation.
     *
     * @param expression Java regular expression
     * @param score score from 0.0 to 1.0 assigned to accepted matches
     * @param captureGroup group containing the PII, or 0 for the entire match
     * @param validator thread-safe candidate predicate, or null to accept every match
     */
    public record Pattern(String expression, double score, int captureGroup, Predicate<String> validator) {
        /** Validates the pattern configuration. */
        public Pattern {
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("expression must not be blank");
            }
            if (!Double.isFinite(score) || score < 0 || score > 1) {
                throw new IllegalArgumentException("score must be between 0.0 and 1.0");
            }
            if (captureGroup < 0) {
                throw new IllegalArgumentException("captureGroup must be >= 0");
            }
        }
    }

    private record CompiledPattern(Pattern definition, java.util.regex.Pattern expression) {
        private CompiledPattern(Pattern definition) {
            this(Objects.requireNonNull(definition, "pattern must not be null"),
                    java.util.regex.Pattern.compile(definition.expression()));
        }

        private CompiledPattern {
            if (definition.captureGroup() > expression.matcher("").groupCount()) {
                throw new IllegalArgumentException("captureGroup exceeds the pattern group count");
            }
        }
    }
}

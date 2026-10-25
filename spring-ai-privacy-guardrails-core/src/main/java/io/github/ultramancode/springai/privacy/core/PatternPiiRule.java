package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rule composed of trusted Java regular expressions and optional candidate checks.
 * A predicate receives the captured candidate. Returning {@code false} discards it.
 * Accepted candidates retain the capture range and configured score.
 */
public final class PatternPiiRule implements PiiRule {
    private final String id;
    private final String entityType;
    private final List<CompiledPattern> patterns;

    private PatternPiiRule(Builder builder) {
        this.id = PiiRuleSupport.requireValidRuleId(builder.id);
        this.entityType = EntityTypeRegistry.requireValidEntityType(builder.entityType);
        if (builder.patterns.isEmpty()) {
            throw new IllegalArgumentException("at least one pattern is required");
        }
        this.patterns = builder.patterns.stream().map(CompiledPattern::new).toList();
    }

    /** Starts a rule builder. An ID, entity type, and at least one pattern are required. */
    public static Builder builder() {
        return new Builder();
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
    public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        List<PiiSpan> spans = new ArrayList<>();
        for (CompiledPattern compiled : this.patterns) {
            PiiRuleSupport.checkInterrupted();
            PatternDefinition definition = compiled.definition();
            int captureGroup = definition.captureGroup();
            Matcher matcher = compiled.expression().matcher(text);
            while (matcher.find()) {
                PiiRuleSupport.checkInterrupted();
                int start = matcher.start(captureGroup);
                int end = matcher.end(captureGroup);
                if (start < 0 || end <= start) {
                    throw new IllegalStateException("Rule '" + this.id + "' produced an empty capture group");
                }
                if (definition.validator() != null
                        && !definition.validator().test(matcher.group(captureGroup))) {
                    continue;
                }
                if (spans.size() >= limits.maxResultSpans()) {
                    throw PiiRuleSupport.limitExceeded();
                }
                spans.add(new PiiSpan(this.entityType, start, end, definition.score()));
            }
        }
        return List.copyOf(spans);
    }

    /** Builds an immutable rule from patterns. */
    public static final class Builder {
        private String id;
        private String entityType;
        private final List<PatternDefinition> patterns = new ArrayList<>();

        private Builder() {
        }

        /** Sets the rule's selection ID in lowercase kebab-case, such as {@code employee-id}. */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the entity type emitted by every pattern in this rule.
         * Accepts a constant from {@link PiiEntityTypes} or an application-defined label.
         */
        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }

        /** Adds a pattern. All patterns are evaluated in the order they are added. */
        public Builder addPattern(PatternDefinition pattern) {
            this.patterns.add(Objects.requireNonNull(pattern, "pattern must not be null"));
            return this;
        }

        /** Adds patterns in collection iteration order, after any patterns already added. */
        public Builder addPatterns(Collection<PatternDefinition> patterns) {
            this.patterns.addAll(List.copyOf(patterns));
            return this;
        }

        /** Validates the required ID, entity type, and patterns, then compiles the patterns. */
        public PatternPiiRule build() {
            return new PatternPiiRule(this);
        }
    }

    /**
     * One Java regular expression and its candidate validation.
     *
     * @param expression Java regular expression
     * @param score score from 0.0 to 1.0 assigned to accepted matches
     * @param captureGroup group containing the PII, or 0 for the entire match
     * @param validator thread-safe candidate predicate, or null to accept every match
     */
    public record PatternDefinition(String expression, double score, int captureGroup, Predicate<String> validator) {
        /** Validates the pattern configuration. */
        public PatternDefinition {
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

        /** Starts a pattern definition. Expression and score are required. */
        public static Builder builder() {
            return new Builder();
        }

        /** Builds a pattern definition with an optional capture group and candidate check. */
        public static final class Builder {
            private String expression;
            private Double score;
            private int captureGroup;
            private Predicate<String> validator;

            private Builder() {
            }

            /** Sets the Java regular expression. */
            public Builder expression(String expression) {
                this.expression = expression;
                return this;
            }

            /** Sets the score assigned to accepted matches, from 0.0 to 1.0. */
            public Builder score(double score) {
                this.score = score;
                return this;
            }

            /** Sets the capture group number. Defaults to 0 for the entire match. */
            public Builder captureGroup(int captureGroup) {
                this.captureGroup = captureGroup;
                return this;
            }

            /** Sets a thread-safe candidate predicate, or null to accept every match. */
            public Builder validator(Predicate<String> validator) {
                this.validator = validator;
                return this;
            }

            /** Validates the pattern configuration and creates a pattern definition. */
            public PatternDefinition build() {
                if (this.score == null) {
                    throw new IllegalArgumentException("score is required");
                }
                return new PatternDefinition(this.expression, this.score, this.captureGroup, this.validator);
            }
        }
    }

    private record CompiledPattern(PatternDefinition definition, Pattern expression) {
        private CompiledPattern(PatternDefinition definition) {
            this(Objects.requireNonNull(definition, "pattern must not be null"),
                    Pattern.compile(definition.expression()));
        }

        private CompiledPattern {
            if (definition.captureGroup() > expression.matcher("").groupCount()) {
                throw new IllegalArgumentException("captureGroup exceeds the pattern group count");
            }
        }
    }
}

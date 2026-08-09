package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** In-process PII analyzer backed by configured Java regular expressions. */
public final class RegexPiiAnalyzer implements PiiAnalyzer {

    /** Stable provider ID used by core resolution and diagnostics. */
    public static final String PROVIDER_ID = "REGEX";

    private final List<CompiledRule> rules;

    /**
     * Compiles application-owned regex rules for repeated thread-safe analysis.
     *
     * @param rules non-empty rule list; patterns are trusted configuration
     */
    public RegexPiiAnalyzer(List<RegexPiiRule> rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("at least one regex PII rule is required");
        }
        this.rules = rules.stream()
                .map(CompiledRule::new)
                .toList();
    }

    @Override
    public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(options, "options must not be null");
        if (text.isBlank()) {
            return List.of();
        }

        List<PiiSpan> spans = new ArrayList<>();
        for (CompiledRule rule : this.rules) {
            Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                PiiSpan span = toSpan(rule, matcher);
                if (span == null) {
                    continue;
                }
                if (spans.size() >= PiiAnalyzer.MAX_RESULT_SPANS) {
                    throw new PrivacyGuardrailException(
                            PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION,
                            PrivacyPhase.ANALYSIS,
                            "Regex analyzer result exceeded the safe span limit"
                    );
                }
                spans.add(span);
            }
        }
        return List.copyOf(spans);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * Returns the application-configured entity types emitted by these local rules.
     *
     * @return immutable canonical types from the compiled rules
     */
    @Override
    public Set<String> trustedEntityTypes() {
        return this.rules.stream()
                .map(compiledRule -> compiledRule.rule().entityType())
                .collect(Collectors.toUnmodifiableSet());
    }

    private PiiSpan toSpan(CompiledRule rule, Matcher matcher) {
        int captureGroup = rule.rule().captureGroup();
        int start = matcher.start(captureGroup);
        int end = matcher.end(captureGroup);
        if (start < 0 || end <= start) {
            throw new IllegalStateException(
                    "Regex rule capture group " + captureGroup
                            + " did not produce a non-empty span for entity type "
                            + rule.rule().entityType()
            );
        }

        RegexPiiMatchValidator matchValidator = rule.rule().matchValidator();
        if (matchValidator != null) {
            String candidate = matcher.group(captureGroup);
            try {
                if (!matchValidator.isValid(candidate)) {
                    return null;
                }
            } catch (RuntimeException failure) {
                throw new IllegalStateException(
                        "Regex match validator failed for entity type "
                                + rule.rule().entityType(),
                        failure
                );
            }
        }

        return new PiiSpan(
                rule.rule().entityType(),
                start,
                end,
                rule.rule().score()
        );
    }

    private record CompiledRule(RegexPiiRule rule, Pattern pattern) {

        private CompiledRule(RegexPiiRule rule) {
            this(Objects.requireNonNull(rule, "rule must not be null"), Pattern.compile(rule.pattern()));
        }

        private CompiledRule {
            int groupCount = pattern.matcher("").groupCount();
            if (rule.captureGroup() > groupCount) {
                throw new IllegalArgumentException("Regex rule capture group " + rule.captureGroup()
                        + " is greater than available group count " + groupCount
                        + " for entity type " + rule.entityType());
            }
        }
    }
}

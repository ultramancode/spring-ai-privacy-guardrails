package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiEntityTypes;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;

import java.util.List;
import java.util.Set;

/** ASCII email addresses with dotted DNS domains. */
final class EmailPiiRule implements PiiRule {
    private static final String LOCAL_PART_CHARACTERS = "A-Za-z0-9.!#$%&'*+/=?^_`{|}~-";
    private static final String EMAIL_EXPRESSION =
            "(?<![@" + LOCAL_PART_CHARACTERS + "])"
                    + "[" + LOCAL_PART_CHARACTERS + "]{1,64}"
                    + "@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.){1,126}"
                    + "[A-Za-z]{2,63}(?![A-Za-z0-9_@-])";
    private static final PatternDefinition EMAIL_PATTERN = PatternDefinition.builder()
            .expression(EMAIL_EXPRESSION)
            .score(0.85)
            .validator(EmailPiiRule::isValidCandidate)
            .build();

    private final PatternPiiRule patternRule = PatternPiiRule.builder()
            .id(BuiltInPiiRuleIds.EMAIL)
            .entityType(PiiEntityTypes.EMAIL_ADDRESS)
            .addPattern(EMAIL_PATTERN)
            .build();

    @Override
    public String id() {
        return this.patternRule.id();
    }

    @Override
    public Set<String> entityTypes() {
        return this.patternRule.entityTypes();
    }

    @Override
    public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
        return this.patternRule.analyze(text, options, limits);
    }

    /** Checks ASCII email length and local-part dot placement against SMTP rules. */
    private static boolean isValidCandidate(String candidate) {
        return candidate.length() <= 254
                && !candidate.startsWith(".")
                && !candidate.contains("..")
                && !candidate.contains(".@");
    }
}

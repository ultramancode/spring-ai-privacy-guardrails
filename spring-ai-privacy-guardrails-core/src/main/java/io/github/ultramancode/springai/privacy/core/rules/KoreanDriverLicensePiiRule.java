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

/** Hyphenated Korean driver's license number candidates checked for a known region code. */
final class KoreanDriverLicensePiiRule implements PiiRule {
    private static final String LICENSE_NUMBER_EXPRESSION =
            "(?<![A-Za-z0-9])[0-9]{2}-[0-9]{2}-[0-9]{6}-[0-9]{2}(?![A-Za-z0-9])";
    private static final PatternDefinition LICENSE_NUMBER_PATTERN = PatternDefinition.builder()
        .expression(LICENSE_NUMBER_EXPRESSION)
        .score(0.85)
        .validator(KoreanDriverLicensePiiRule::isValidCandidate)
        .build();

    private final PatternPiiRule patternRule = PatternPiiRule.builder()
        .id(BuiltInPiiRuleIds.KR_DRIVER_LICENSE)
        .entityType(PiiEntityTypes.KR_DRIVER_LICENSE)
        .addPattern(LICENSE_NUMBER_PATTERN)
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

    /** Validates the leading region code, which is 11 through 26 or 28. */
    private static boolean isValidCandidate(String candidate) {
        int regionCode = Integer.parseInt(candidate.substring(0, 2));
        return regionCode >= 11 && regionCode <= 28 && regionCode != 27;
    }
}

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

/** Domestic 010 and international +82 10 mobile phone numbers. */
final class KoreanMobilePhonePiiRule implements PiiRule {
    private static final String MOBILE_NUMBER_EXPRESSION =
            "(?<![A-Za-z0-9_])(?:010|\\+82[- ]?10)"
        + "(?:[0-9]{8}|-[0-9]{4}-[0-9]{4}| [0-9]{4} [0-9]{4})"
        + "(?![A-Za-z0-9_])";
    private static final PatternDefinition MOBILE_NUMBER_PATTERN = PatternDefinition.builder()
        .expression(MOBILE_NUMBER_EXPRESSION)
        .score(0.85)
        .build();

    private final PatternPiiRule patternRule = PatternPiiRule.builder()
        .id(BuiltInPiiRuleIds.KR_MOBILE_PHONE)
        .entityType(PiiEntityTypes.PHONE_NUMBER)
        .addPattern(MOBILE_NUMBER_PATTERN)
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
}

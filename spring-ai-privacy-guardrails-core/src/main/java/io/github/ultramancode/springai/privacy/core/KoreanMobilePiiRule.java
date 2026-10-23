package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Bounded scanner for domestic 010 and international +82 10 numbers. */
final class KoreanMobilePiiRule implements PiiRule {
    @Override
    public String id() {
        return "kr-mobile-phone";
    }

    @Override
    public Set<String> entityTypes() {
        return Set.of("PHONE_NUMBER");
    }

    @Override
    public Set<String> countries() {
        return Set.of("KR");
    }

    @Override
    public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
        List<PiiSpan> result = new ArrayList<>();
        for (int start = 0; start < text.length(); start++) {
            PiiRuleSupport.checkInterrupted();
            if (start > 0 && isWord(text.charAt(start - 1))) {
                continue;
            }
            int cursor;
            if (text.startsWith("010", start)) {
                cursor = start + 3;
            } else if (text.startsWith("+82", start)) {
                cursor = start + 3;
                if (cursor < text.length() && isSeparator(text.charAt(cursor))) {
                    cursor++;
                }
                if (!text.startsWith("10", cursor)) {
                    continue;
                }
                cursor += 2;
            } else {
                continue;
            }
            char separator = 0;
            if (cursor < text.length() && isSeparator(text.charAt(cursor))) {
                separator = text.charAt(cursor++);
            }
            if (!fourDigits(text, cursor)) {
                continue;
            }
            cursor += 4;
            if (separator != 0) {
                if (cursor >= text.length() || text.charAt(cursor) != separator) {
                    continue;
                }
                cursor++;
            }
            if (!fourDigits(text, cursor)) {
                continue;
            }
            int end = cursor + 4;
            if (end < text.length() && isWord(text.charAt(end))) {
                continue;
            }
            if (result.size() >= limits.maxResultSpans()) {
                throw PiiRuleSupport.limitExceeded();
            }
            result.add(new PiiSpan("PHONE_NUMBER", start, end, 0.85));
            start = end - 1;
        }
        return List.copyOf(result);
    }

    private static boolean fourDigits(String text, int start) {
        if (start + 4 > text.length()) {
            return false;
        }
        for (int i = start; i < start + 4; i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isSeparator(char value) {
        return value == '-' || value == ' ';
    }

    private static boolean isWord(char value) {
        return value >= '0' && value <= '9' || value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z' || value == '_';
    }
}

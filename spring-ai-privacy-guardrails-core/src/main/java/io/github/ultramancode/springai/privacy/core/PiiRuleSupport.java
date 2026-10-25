package io.github.ultramancode.springai.privacy.core;

final class PiiRuleSupport {
    private PiiRuleSupport() {
    }

    static String requireValidRuleId(String ruleId) {
        if (ruleId == null || ruleId.length() > 64 || !ruleId.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("rule ID must use 1-64 lowercase kebab-case characters");
        }
        return ruleId;
    }

    static String requireValidPresetName(String presetName) {
        if (presetName == null || presetName.length() > 64 || !presetName.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("preset name must start with an ASCII letter and contain 1-64 letters, digits, hyphens or underscores");
        }
        return presetName;
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new PrivacyGuardrailException(PrivacyFailureCode.ANALYSIS_INTERRUPTED,
                PrivacyPhase.ANALYSIS, "Local rule analysis interrupted");
        }
    }

    static PrivacyGuardrailException limitExceeded() {
        return new PrivacyGuardrailException(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
            PrivacyPhase.ANALYSIS, "Local rule analysis exceeded a configured processing limit");
    }
}

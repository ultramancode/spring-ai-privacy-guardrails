package io.github.ultramancode.springai.privacy.core;

final class PiiRuleSupport {
    private PiiRuleSupport() {
    }

    static String requireRuleId(String id) {
        if (id == null || id.length() > 64 || !id.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("rule ID must use 1-64 lowercase kebab-case characters");
        }
        return id;
    }

    static String requirePresetName(String name) {
        if (name == null || name.length() > 64 || !name.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("preset name must start with an ASCII letter and contain 1-64 letters, digits, hyphens or underscores");
        }
        return name;
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

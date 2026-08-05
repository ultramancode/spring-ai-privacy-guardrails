package io.github.ultramancode.springai.privacy.core;

/** Defines which analyzer failures must stop privacy processing. */
public enum PiiAnalyzerFailurePolicy {

    /** Requires every configured analyzer to complete successfully. */
    REQUIRE_ALL,

    /** Requires the configured primary analyzer but tolerates failures from others. */
    REQUIRE_PRIMARY,

    /** Continues with any successful analyzer, but still fails when all analyzers fail. */
    ALLOW_PARTIAL
}

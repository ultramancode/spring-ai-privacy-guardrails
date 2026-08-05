package io.github.ultramancode.springai.privacy.core;

/**
 * Receives sanitized analyzer failures, including failures tolerated by partial-analysis policies.
 * Callbacks run on the analysis caller's thread; implementations must be thread-safe and must not throw.
 */
@FunctionalInterface
public interface PiiAnalyzerFailureObserver {

    /**
     * Receives a privacy-safe failure event after an analyzer attempt fails.
     *
     * @param failure sanitized provider, category, phase, and attempt metadata
     */
    void onAnalyzerFailure(PiiAnalyzerFailure failure);

    /**
     * Returns an observer that discards every event.
     *
     * @return a reusable no-operation observer
     */
    static PiiAnalyzerFailureObserver noop() {
        return failure -> {
        };
    }
}

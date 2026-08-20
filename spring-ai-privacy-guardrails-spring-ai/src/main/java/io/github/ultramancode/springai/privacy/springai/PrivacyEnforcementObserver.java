package io.github.ultramancode.springai.privacy.springai;

/**
 * Receives privacy-safe runtime enforcement outcomes. Callbacks run inline and may be
 * invoked concurrently. A streaming callback may run on a Reactor signal-processing
 * thread rather than the original request thread. Implementations should return
 * promptly and offload potentially blocking network or database I/O. Non-fatal
 * observer failures are isolated and cannot change privacy enforcement.
 */
@FunctionalInterface
public interface PrivacyEnforcementObserver {

    /**
     * Receives a privacy-safe event after a supported boundary completes its decision.
     *
     * @param event boundary and outcome only
     */
    void onEnforcement(PrivacyEnforcementEvent event);

    /**
     * Returns an observer that discards every event.
     *
     * @return a reusable no-operation observer
     */
    static PrivacyEnforcementObserver noop() {
        return event -> {
        };
    }
}

package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureSanitizer;

import java.util.Objects;

/** Isolates optional enforcement observers from the privacy boundary. */
final class PrivacyEnforcementNotifier {

    private final PrivacyEnforcementObserver observer;

    PrivacyEnforcementNotifier(PrivacyEnforcementObserver observer) {
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    void notify(
            PrivacyEnforcementBoundary boundary,
            PrivacyEnforcementOutcome outcome
    ) {
        PrivacyEnforcementEvent event = new PrivacyEnforcementEvent(boundary, outcome);
        try {
            this.observer.onEnforcement(event);
        } catch (Throwable observerFailure) {
            PrivacyFailureSanitizer.rethrowIfFatal(observerFailure);
        }
    }
}

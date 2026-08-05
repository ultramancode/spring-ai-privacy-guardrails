package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;

import java.util.Objects;

/** Single implementation of application-facing output action semantics. */
final class PrivacyOutputPolicyExecutor {

    private PrivacyOutputPolicyExecutor() {
    }

    static Result apply(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String text,
            PrivacyOutputAction action
    ) {
        Objects.requireNonNull(privacyService, "privacyService must not be null");
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(action, "action must not be null");
        if (text == null) {
            return Result.allow(text);
        }
        return switch (action) {
            case TOKENIZE -> Result.allow(PrivacyJsonPayloadTransformer.tokenize(
                    privacyService,
                    handle,
                    text,
                    PrivacyPhase.OUTPUT_POLICY,
                    false
            ));
            case REDACT -> Result.allow(PrivacyJsonPayloadTransformer.redact(
                    privacyService,
                    handle,
                    text,
                    PrivacyPhase.OUTPUT_POLICY,
                    false
            ));
            case BLOCK -> PrivacyJsonPayloadTransformer.containsPii(
                    privacyService,
                    handle,
                    text,
                    PrivacyPhase.OUTPUT_POLICY,
                    false
            )
                    ? Result.block()
                    : Result.allow(text);
        };
    }

    record Result(String text, boolean blocked) {

        private static Result allow(String text) {
            return new Result(text, false);
        }

        private static Result block() {
            return new Result("", true);
        }
    }
}

package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Stores and validates request-scoped response-inspection metadata. */
final class PrivacyOutputContextSupport {

    private static final String CONTEXT_RESPONSE_INSPECTION_LIMITS =
            "io.github.ultramancode.springai.privacy.response-inspection-limits";

    private PrivacyOutputContextSupport() {
    }

    static ChatClientRequest attachResponseInspectionLimits(
            ChatClientRequest request,
            PrivacyResponseInspectionLimits limits
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        Map<String, Object> context = new HashMap<>(request.context());
        context.put(CONTEXT_RESPONSE_INSPECTION_LIMITS, limits);
        return request.mutate().context(context).build();
    }

    static Optional<PrivacyResponseInspectionLimits> findResponseInspectionLimits(
            ChatClientRequest request
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Object responseInspectionLimitsValue = request.context()
                .get(CONTEXT_RESPONSE_INSPECTION_LIMITS);
        if (responseInspectionLimitsValue == null) {
            return Optional.empty();
        }
        if (responseInspectionLimitsValue instanceof PrivacyResponseInspectionLimits limits) {
            return Optional.of(limits);
        }
        throw new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                PrivacyPhase.OUTPUT_POLICY,
                "Privacy response inspection limits are invalid"
        );
    }

    static boolean hasInternalEntries(Map<String, Object> context) {
        return context.containsKey(CONTEXT_RESPONSE_INSPECTION_LIMITS);
    }

    static void removeInternalEntries(Map<String, Object> context) {
        context.remove(CONTEXT_RESPONSE_INSPECTION_LIMITS);
    }
}

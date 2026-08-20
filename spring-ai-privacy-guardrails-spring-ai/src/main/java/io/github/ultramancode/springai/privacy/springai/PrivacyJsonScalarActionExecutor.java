package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Applies one privacy action to a pre-analyzed JSON scalar. */
final class PrivacyJsonScalarActionExecutor {

    private PrivacyJsonScalarActionExecutor() {
    }

    static Object apply(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            Object scalar,
            List<PiiSpan> spans,
            Action action,
            Set<String> allowedEntityTypes,
            PrivacyPhase phase
    ) {
        try {
            return switch (action) {
                case TOKENIZE -> privacyService.tokenizeScalar(
                        handle,
                        scalar,
                        tokenizationSpans(scalar, spans)
                );
                case REDACT -> {
                    String text = PrivacyJsonDocumentProcessor.analysisText(scalar);
                    String redacted = privacyService.redact(handle, text, spans);
                    yield redacted.equals(text) ? scalar : redacted;
                }
                case CONTAINS_PII -> {
                    if (privacyService.containsPii(
                            handle,
                            PrivacyJsonDocumentProcessor.analysisText(scalar),
                            spans
                    )) {
                        throw new PrivacyJsonPayloadTransformer.PiiDetected();
                    }
                    yield scalar;
                }
                case DISCLOSE -> disclose(
                        privacyService,
                        handle,
                        scalar,
                        spans,
                        allowedEntityTypes,
                        phase
                ).value();
            };
        } catch (PrivacyGuardrailException failure) {
            throw PrivacyJsonPayloadTransformer.remapOutputLimit(failure, phase);
        }
    }

    static DisclosureResult disclose(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            Object scalar,
            List<PiiSpan> spans,
            Set<String> allowedEntityTypes,
            PrivacyPhase phase
    ) {
        try {
            Object protectedScalar = privacyService.tokenizeScalar(
                    handle,
                    scalar,
                    tokenizationSpans(scalar, spans)
            );
            Object disclosedScalar = privacyService.detokenizeValueTree(
                    handle,
                    protectedScalar,
                    allowedEntityTypes
            );
            return new DisclosureResult(
                    disclosedScalar,
                    !Objects.equals(protectedScalar, disclosedScalar)
            );
        } catch (PrivacyGuardrailException failure) {
            throw PrivacyJsonPayloadTransformer.remapOutputLimit(failure, phase);
        }
    }

    private static List<PiiSpan> tokenizationSpans(Object scalar, List<PiiSpan> spans) {
        if (!(scalar instanceof BigDecimal decimal)
                || spans.isEmpty()
                || decimal.toString().equals(decimal.toPlainString())) {
            return spans;
        }
        // Core stores a protected number as one typed value and validates supplied spans
        // against Number.toString(); remap already-resolved plain-decimal evidence accordingly.
        int tokenizationLength = decimal.toString().length();
        return spans.stream()
                .map(span -> new PiiSpan(span.entityType(), 0, tokenizationLength, span.score()))
                .toList();
    }

    enum Action {
        TOKENIZE,
        REDACT,
        CONTAINS_PII,
        DISCLOSE
    }

    record DisclosureResult(Object value, boolean disclosed) {
    }
}

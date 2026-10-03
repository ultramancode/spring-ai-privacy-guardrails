package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.ScalarAnalysisText;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamConstraintsException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Orchestrates JSON-aware privacy actions with safe plain-text fallback. */
final class PrivacyJsonPayloadTransformer {

    private static final PrivacyJsonDocumentProcessor DEFAULT_DOCUMENT_PROCESSOR =
            new PrivacyJsonDocumentProcessor(PrivacyProcessingLimits.defaults());
    private static final String PAYLOAD_LIMIT_MESSAGE =
            "Privacy payload exceeded a configured processing limit";

    private PrivacyJsonPayloadTransformer() {
    }

    static String tokenize(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            PrivacyPhase phase,
            boolean requireValidJson
    ) {
        Objects.requireNonNull(privacyService, "privacyService must not be null");
        Objects.requireNonNull(handle, "handle must not be null");
        return analyzeAndTransformJsonOrText(
                privacyService,
                handle,
                payload,
                phase,
                requireValidJson,
                Action.TOKENIZE,
                Set.of()
        );
    }

    static String redact(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            PrivacyPhase phase,
            boolean requireValidJson
    ) {
        Objects.requireNonNull(privacyService, "privacyService must not be null");
        Objects.requireNonNull(handle, "handle must not be null");
        return analyzeAndTransformJsonOrText(
                privacyService,
                handle,
                payload,
                phase,
                requireValidJson,
                Action.REDACT,
                Set.of()
        );
    }

    static DisclosureResult discloseWithOutcome(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            Set<String> allowedEntityTypes,
            PrivacyPhase phase,
            boolean requireValidJson
    ) {
        Objects.requireNonNull(privacyService, "privacyService must not be null");
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(allowedEntityTypes, "allowedEntityTypes must not be null");
        DisclosureTracker tracker = new DisclosureTracker();
        String transformed = analyzeAndTransformJsonOrText(
                privacyService,
                handle,
                payload,
                phase,
                requireValidJson,
                Action.DISCLOSE,
                allowedEntityTypes,
                tracker
        );
        return new DisclosureResult(transformed, tracker.disclosed());
    }

    static String restoreKnownTokens(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            PrivacyPhase phase
    ) {
        Objects.requireNonNull(privacyService, "privacyService must not be null");
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        PrivacyProcessingLimits limits = privacyService.processingLimits();
        requireWithinLimit(payload.length(), limits.maxTextCharacters(), phase);
        if (payload.isBlank()) {
            return requireTransformedResult(payload, phase, limits);
        }
        try {
            try {
                return documentProcessor(limits).transform(
                        payload, scalar -> privacyService.detokenizeValueTree(handle, scalar), phase);
            } catch (PrivacyJsonDocumentProcessor.OutputLimitExceeded | StreamConstraintsException ignored) {
                throw payloadLimitExceeded(phase);
            } catch (JacksonException | PrivacyJsonDocumentProcessor.InvalidJsonPayload ignored) {
                String restoredText = privacyService.detokenize(handle, payload);
                return requireTransformedResult(restoredText, phase, limits);
            }
        } catch (PrivacyGuardrailException failure) {
            throw remapProcessingLimit(failure, phase);
        }
    }

    static boolean containsPii(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            PrivacyPhase phase
    ) {
        Objects.requireNonNull(privacyService, "privacyService must not be null");
        Objects.requireNonNull(handle, "handle must not be null");
        if (payload == null) {
            return false;
        }
        Objects.requireNonNull(phase, "phase must not be null");
        PrivacyProcessingLimits limits = privacyService.processingLimits();
        requireWithinLimit(payload.length(), limits.maxTextCharacters(), phase);
        if (payload.isBlank()) {
            return false;
        }
        try {
            List<String> analysisTexts;
            try {
                analysisTexts = documentProcessor(limits).validateAndCollectAnalysisTexts(payload, phase);
            } catch (StreamConstraintsException ignored) {
                throw payloadLimitExceeded(phase);
            } catch (JacksonException | PrivacyJsonDocumentProcessor.InvalidJsonPayload ignored) {
                return privacyService.containsPii(handle, payload);
            }
            return privacyService.containsPiiInJsonScalars(handle, analysisTexts);
        } catch (PrivacyGuardrailException failure) {
            throw remapProcessingLimit(failure, phase);
        }
    }

    private static String analyzeAndTransformJsonOrText(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            PrivacyPhase phase,
            boolean requireValidJson,
            Action action,
            Set<String> allowedEntityTypes
    ) {
        return analyzeAndTransformJsonOrText(
                privacyService,
                handle,
                payload,
                phase,
                requireValidJson,
                action,
                allowedEntityTypes,
                null
        );
    }

    private static String analyzeAndTransformJsonOrText(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            PrivacyPhase phase,
            boolean requireValidJson,
            Action action,
            Set<String> allowedEntityTypes,
            DisclosureTracker disclosureTracker
    ) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        requireWithinLimit(payload.length(), privacyService.processingLimits().maxTextCharacters(), phase);
        if (payload.isBlank()) {
            return requireTransformedResult(payload, phase, privacyService.processingLimits());
        }

        try {
            return analyzeAndTransformJson(
                    privacyService,
                    handle,
                    payload,
                    phase,
                    action,
                    allowedEntityTypes,
                    disclosureTracker
            );
        } catch (PrivacyGuardrailException failure) {
            throw remapProcessingLimit(failure, phase);
        } catch (PrivacyJsonDocumentProcessor.OutputLimitExceeded ignored) {
            throw payloadLimitExceeded(phase);
        } catch (StreamConstraintsException ignored) {
            throw payloadLimitExceeded(phase);
        } catch (JacksonException | PrivacyJsonDocumentProcessor.InvalidJsonPayload ignored) {
            if (requireValidJson) {
                throw invalidStructuredJson(phase);
            }
            return applyTextAction(
                    privacyService,
                    handle,
                    payload,
                    action,
                    allowedEntityTypes,
                    phase,
                    disclosureTracker
            );
        }
    }

    private static String analyzeAndTransformJson(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            PrivacyPhase phase,
            Action action,
            Set<String> allowedEntityTypes,
            DisclosureTracker disclosureTracker
    ) throws JacksonException {
        PrivacyJsonDocumentProcessor processor =
                documentProcessor(privacyService.processingLimits());
        return switch (action) {
            case TOKENIZE, DISCLOSE -> tokenizeOrDiscloseJson(
                    processor, privacyService, handle, payload, phase,
                    action, allowedEntityTypes, disclosureTracker);
            case REDACT -> analyzeAndRedactJson(
                    processor, privacyService, handle, payload, phase);
        };
    }

    /** Tokenizes JSON scalars, then restores allowed values for DISCLOSE. */
    private static String tokenizeOrDiscloseJson(
            PrivacyJsonDocumentProcessor processor,
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            PrivacyPhase phase,
            Action action,
            Set<String> allowedEntityTypes,
            DisclosureTracker disclosureTracker
    ) throws JacksonException {
        List<Object> sourceScalars = processor.validateAndCollectScalars(payload, phase);
        List<Object> protectedScalars = privacyService.tokenizeScalars(handle, sourceScalars);
        Map<Object, Object> transformedValuesByScalar = new HashMap<>();
        for (int index = 0; index < sourceScalars.size(); index++) {
            Object protectedValue = protectedScalars.get(index);
            if (action == Action.DISCLOSE) {
                Object disclosedValue = privacyService.detokenizeValueTree(
                        handle, protectedValue, allowedEntityTypes);
                if (disclosureTracker != null) {
                    disclosureTracker.record(!Objects.equals(protectedValue, disclosedValue));
                }
                transformedValuesByScalar.put(sourceScalars.get(index), disclosedValue);
            } else {
                transformedValuesByScalar.put(sourceScalars.get(index), protectedValue);
            }
        }
        return processor.rewriteValidatedJson(payload, transformedValuesByScalar::get, phase);
    }

    private static String analyzeAndRedactJson(
            PrivacyJsonDocumentProcessor processor,
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            PrivacyPhase phase
    ) throws JacksonException {
        List<String> analysisTexts = processor.validateAndCollectAnalysisTexts(payload, phase);
        Map<String, String> redactedTexts = privacyService.redactTextsFromJsonScalars(handle, analysisTexts);
        return processor.rewriteValidatedJson(payload, scalar -> {
            String text = ScalarAnalysisText.toAnalysisText(scalar);
            String redacted = redactedTexts.getOrDefault(text, text);
            return redacted.equals(text) ? scalar : redacted;
        }, phase);
    }

    private static String applyTextAction(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String text,
            Action action,
            Set<String> allowedEntityTypes,
            PrivacyPhase phase,
            DisclosureTracker disclosureTracker
    ) {
        try {
            String transformedText = switch (action) {
                case TOKENIZE -> privacyService.tokenize(handle, text);
                case REDACT -> privacyService.redact(handle, text);
                case DISCLOSE -> {
                    String protectedText = privacyService.tokenize(handle, text);
                    String disclosed = privacyService.detokenize(
                            handle,
                            protectedText,
                            allowedEntityTypes
                    );
                    if (disclosureTracker != null) {
                        disclosureTracker.record(!Objects.equals(
                                protectedText,
                                disclosed
                        ));
                    }
                    yield disclosed;
                }
            };
            return requireTransformedResult(transformedText, phase, privacyService.processingLimits());
        } catch (PrivacyGuardrailException failure) {
            throw remapProcessingLimit(failure, phase);
        }
    }

    private static PrivacyJsonDocumentProcessor documentProcessor(PrivacyProcessingLimits limits) {
        if (PrivacyProcessingLimits.defaults().equals(limits)) {
            return DEFAULT_DOCUMENT_PROCESSOR;
        }
        return new PrivacyJsonDocumentProcessor(limits);
    }

    static void requireWithinLimit(long actual, long maximum, PrivacyPhase phase) {
        if (actual > maximum) {
            throw payloadLimitExceeded(phase);
        }
    }

    static String requireTransformedResult(
            String value, PrivacyPhase phase, PrivacyProcessingLimits limits
    ) {
        Objects.requireNonNull(value, "text transformation must not return null");
        requireWithinLimit(value.length(), limits.maxOutputCharacters(), phase);
        return value;
    }

    static PrivacyGuardrailException remapProcessingLimit(
            PrivacyGuardrailException failure,
            PrivacyPhase phase
    ) {
        if (failure.code() == PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED) {
            return payloadLimitExceeded(phase);
        }
        return failure;
    }

    static PrivacyGuardrailException payloadLimitExceeded(PrivacyPhase phase) {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                phase,
                PAYLOAD_LIMIT_MESSAGE
        );
    }

    static PrivacyGuardrailException transformationConflict(
            PrivacyPhase phase,
            String safeMessage
    ) {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                phase,
                safeMessage
        );
    }

    private static PrivacyGuardrailException invalidStructuredJson(PrivacyPhase phase) {
        return transformationConflict(phase, "Structured JSON payload is invalid");
    }

    record DisclosureResult(String payload, boolean disclosed) {
    }

    private static final class DisclosureTracker {

        private boolean disclosed;

        private void record(boolean disclosureOccurred) {
            this.disclosed = this.disclosed || disclosureOccurred;
        }

        private boolean disclosed() {
            return this.disclosed;
        }
    }

    private enum Action {
        TOKENIZE,
        REDACT,
        DISCLOSE
    }
}

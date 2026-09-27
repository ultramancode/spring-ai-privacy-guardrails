package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PiiTokenizationResult;
import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamConstraintsException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

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
                PrivacyJsonScalarActionExecutor.Action.TOKENIZE,
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
                PrivacyJsonScalarActionExecutor.Action.REDACT,
                Set.of()
        );
    }

    static String disclose(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            Set<String> allowedEntityTypes,
            PrivacyPhase phase,
            boolean requireValidJson
    ) {
        return discloseWithOutcome(
                privacyService,
                handle,
                payload,
                allowedEntityTypes,
                phase,
                requireValidJson
        ).payload();
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
                PrivacyJsonScalarActionExecutor.Action.DISCLOSE,
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
        try {
            return transformJsonOrText(
                    payload,
                    scalar -> privacyService.detokenizeValueTree(handle, scalar),
                    text -> privacyService.detokenize(handle, text),
                    phase,
                    false,
                    privacyService.processingLimits()
            );
        } catch (PrivacyGuardrailException failure) {
            throw remapProcessingLimit(failure, phase);
        }
    }

    static boolean containsPii(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            PrivacyPhase phase,
            boolean requireValidJson
    ) {
        Objects.requireNonNull(privacyService, "privacyService must not be null");
        Objects.requireNonNull(handle, "handle must not be null");
        if (payload == null) {
            return false;
        }
        try {
            analyzeAndTransformJsonOrText(
                    privacyService,
                    handle,
                    payload,
                    phase,
                    requireValidJson,
                    PrivacyJsonScalarActionExecutor.Action.CONTAINS_PII,
                    Set.of()
            );
            return false;
        } catch (PiiDetected ignored) {
            return true;
        }
    }

    static String transformJsonOrText(
            String payload,
            Function<Object, Object> scalarTransformer,
            UnaryOperator<String> textTransformer,
            PrivacyPhase phase,
            boolean requireValidJson,
            PrivacyProcessingLimits limits
    ) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(scalarTransformer, "scalarTransformer must not be null");
        Objects.requireNonNull(textTransformer, "textTransformer must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        requireWithinLimit(payload.length(), limits.maxTextCharacters(), phase);
        if (payload.isBlank()) {
            return requireTransformedResult(payload, phase, limits);
        }

        try {
            return documentProcessor(limits).transform(payload, scalarTransformer, phase);
        } catch (PrivacyJsonDocumentProcessor.OutputLimitExceeded ignored) {
            throw payloadLimitExceeded(phase);
        } catch (StreamConstraintsException ignored) {
            throw payloadLimitExceeded(phase);
        } catch (JacksonException | PrivacyJsonDocumentProcessor.InvalidJsonPayload ignored) {
            if (requireValidJson) {
                throw invalidStructuredJson(phase);
            }
            return requireTransformedResult(textTransformer.apply(payload), phase, limits);
        }
    }

    private static String analyzeAndTransformJsonOrText(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String payload,
            PrivacyPhase phase,
            boolean requireValidJson,
            PrivacyJsonScalarActionExecutor.Action action,
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
            PrivacyJsonScalarActionExecutor.Action action,
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
            PrivacyJsonScalarActionExecutor.Action action,
            Set<String> allowedEntityTypes,
            DisclosureTracker disclosureTracker
    ) throws JacksonException {
        PrivacyJsonDocumentProcessor processor =
                documentProcessor(privacyService.processingLimits());
        List<String> analysisTexts = processor.validateAndCollectAnalysisTexts(payload, phase);
        Map<String, List<PiiSpan>> spansByText = PrivacyJsonScalarBatchAnalyzer.analyze(
                privacyService,
                analysisTexts,
                phase
        );
        Function<Object, Object> scalarTransformer = scalar -> {
            String analysisText = PrivacyJsonDocumentProcessor.analysisText(scalar);
            List<PiiSpan> spans = spansByText.getOrDefault(analysisText, List.of());
            return applyScalarAction(
                    privacyService,
                    handle,
                    scalar,
                    spans,
                    action,
                    allowedEntityTypes,
                    phase,
                    disclosureTracker
            );
        };
        return processor.rewrite(payload, scalarTransformer, phase);
    }

    private static Object applyScalarAction(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            Object scalar,
            List<PiiSpan> spans,
            PrivacyJsonScalarActionExecutor.Action action,
            Set<String> allowedEntityTypes,
            PrivacyPhase phase,
            DisclosureTracker disclosureTracker
    ) {
        if (action != PrivacyJsonScalarActionExecutor.Action.DISCLOSE
                || disclosureTracker == null) {
            return PrivacyJsonScalarActionExecutor.apply(
                    privacyService,
                    handle,
                    scalar,
                    spans,
                    action,
                    allowedEntityTypes,
                    phase
            );
        }
        PrivacyJsonScalarActionExecutor.DisclosureResult result =
                PrivacyJsonScalarActionExecutor.disclose(
                        privacyService,
                        handle,
                        scalar,
                        spans,
                        allowedEntityTypes,
                        phase
                );
        disclosureTracker.record(result.disclosed());
        return result.value();
    }

    private static String applyTextAction(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            String text,
            PrivacyJsonScalarActionExecutor.Action action,
            Set<String> allowedEntityTypes,
            PrivacyPhase phase,
            DisclosureTracker disclosureTracker
    ) {
        try {
            String transformedText = switch (action) {
                case TOKENIZE -> privacyService.tokenize(handle, text);
                case REDACT -> privacyService.redact(handle, text);
                case CONTAINS_PII -> {
                    if (privacyService.containsPii(handle, text)) {
                        throw new PiiDetected();
                    }
                    yield text;
                }
                case DISCLOSE -> {
                    PiiTokenizationResult tokenization = privacyService.analyzeAndTokenize(handle, text);
                    String disclosed = privacyService.detokenize(
                            handle,
                            tokenization.tokenizedText(),
                            allowedEntityTypes
                    );
                    if (disclosureTracker != null) {
                        disclosureTracker.record(!Objects.equals(
                                tokenization.tokenizedText(),
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

    /** Internal PII-found signal with stack-trace creation disabled. */
    static final class PiiDetected extends RuntimeException {

        PiiDetected() {
            super(null, null, false, false);
        }
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
}

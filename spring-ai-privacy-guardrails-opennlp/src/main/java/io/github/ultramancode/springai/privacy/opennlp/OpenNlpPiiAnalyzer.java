package io.github.ultramancode.springai.privacy.opennlp;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureSanitizer;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.tokenize.SimpleTokenizer;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** In-process PII analyzer backed by Apache OpenNLP tokenizer and name-finder models. */
public final class OpenNlpPiiAnalyzer implements PiiAnalyzer {

    /** Stable provider ID used by core resolution and diagnostics. */
    public static final String PROVIDER_ID = "OPENNLP";

    private final String language;
    private final Supplier<Tokenizer> tokenizerFactory;
    private final List<OpenNlpEntityModel> entityModels;

    /**
     * Creates an analyzer using OpenNLP's {@link SimpleTokenizer}.
     *
     * @param language language code accepted by this analyzer
     * @param entityModels non-empty application-supplied name-finder models
     */
    public OpenNlpPiiAnalyzer(String language, List<OpenNlpEntityModel> entityModels) {
        this(language, null, entityModels);
    }

    /**
     * Creates an analyzer using an optional application-supplied tokenizer model.
     *
     * @param language language code accepted by this analyzer
     * @param tokenizerModel tokenizer model, or {@code null} to use OpenNLP's
     *                       {@link SimpleTokenizer}
     * @param entityModels non-empty application-supplied name-finder models
     */
    public OpenNlpPiiAnalyzer(
            String language,
            TokenizerModel tokenizerModel,
            List<OpenNlpEntityModel> entityModels
    ) {
        this.language = PiiAnalysisOptions.canonicalizeLanguageCode(language);
        this.tokenizerFactory = tokenizerModel == null
                ? () -> SimpleTokenizer.INSTANCE
                : () -> new TokenizerME(tokenizerModel);
        if (entityModels == null) {
            throw new IllegalArgumentException("entityModels must not be null");
        }
        if (entityModels.isEmpty()) {
            throw new IllegalArgumentException("at least one OpenNLP entity model is required");
        }
        if (entityModels.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("entityModels must not contain null elements");
        }
        this.entityModels = List.copyOf(entityModels);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Set<String> trustedEntityTypes() {
        return this.entityModels.stream()
                .map(OpenNlpEntityModel::entityType)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public List<PiiSpan> analyze(
            String text,
            PiiAnalysisOptions options,
            PrivacyProcessingLimits limits
    ) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        if (text.isBlank()) {
            return List.of();
        }
        try {
            return analyzeText(text, options, limits.maxResultSpans());
        } catch (OpenNlpAnalysisException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw PrivacyFailureSanitizer.sanitize(
                    failure,
                    PrivacyFailureCode.ANALYZER_EXECUTION_FAILED,
                    PrivacyPhase.ANALYSIS,
                    "OpenNLP analyzer execution failed"
            );
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Implementation note:</strong> This implementation analyzes each
     * text locally and independently while reusing its tokenizer and name finders
     * within the batch.</p>
     */
    @Override
    public List<List<PiiSpan>> analyzeSegments(
            List<String> texts,
            PiiAnalysisOptions options,
            PrivacyProcessingLimits limits
    ) {
        Objects.requireNonNull(texts, "texts must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        if (texts.size() > limits.maxAnalysisSegments()) {
            throw new OpenNlpAnalysisException(
                    PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                    "PII analysis exceeded the configured segment limit"
            );
        }
        boolean hasNonBlankText = false;
        for (String text : texts) {
            String sourceText = Objects.requireNonNull(
                    text,
                    "texts must not contain null values"
            );
            if (!sourceText.isBlank()) {
                hasNonBlankText = true;
            }
        }
        if (!hasNonBlankText) {
            return texts.stream()
                    .map(ignored -> List.<PiiSpan>of())
                    .toList();
        }

        try {
            requireLanguage(options);
            Tokenizer tokenizer = this.tokenizerFactory.get();
            List<NameFinderME> finders = this.entityModels.stream()
                    .map(entityModel -> new NameFinderME(entityModel.model()))
                    .toList();
            List<List<PiiSpan>> results = new ArrayList<>(texts.size());
            int spanCount = 0;
            for (String text : texts) {
                rejectInterruptedAnalysis();
                if (text.isBlank()) {
                    results.add(List.of());
                    continue;
                }

                List<PiiSpan> spans;
                try {
                    spans = analyzeText(
                            text,
                            tokenizer,
                            finders,
                            limits.maxResultSpans() - spanCount
                    );
                } finally {
                    // NameFinderME retains adaptive state. Clear that state between
                    // independent texts.
                    finders.forEach(NameFinderME::clearAdaptiveData);
                }
                spanCount += spans.size();
                results.add(spans);
            }
            return List.copyOf(results);
        } catch (OpenNlpAnalysisException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw PrivacyFailureSanitizer.sanitize(
                    failure,
                    PrivacyFailureCode.ANALYZER_EXECUTION_FAILED,
                    PrivacyPhase.ANALYSIS,
                    "OpenNLP analyzer execution failed"
            );
        }
    }

    private List<PiiSpan> analyzeText(String text, PiiAnalysisOptions options, int maxResultSpans) {
        requireLanguage(options);
        Tokenizer tokenizer = this.tokenizerFactory.get();
        List<NameFinderME> finders = this.entityModels.stream()
                .map(entityModel -> new NameFinderME(entityModel.model()))
                .toList();
        return analyzeText(text, tokenizer, finders, maxResultSpans);
    }

    private List<PiiSpan> analyzeText(
            String text,
            Tokenizer tokenizer,
            List<NameFinderME> finders,
            int maxResultSpans
    ) {
        Span[] tokenPositions = tokenizer.tokenizePos(text);
        if (tokenPositions.length == 0) {
            return List.of();
        }
        String[] tokens = new String[tokenPositions.length];
        for (int index = 0; index < tokenPositions.length; index++) {
            tokens[index] = tokenPositions[index].getCoveredText(text).toString();
        }

        List<PiiSpan> spans = new ArrayList<>();
        for (int modelIndex = 0; modelIndex < this.entityModels.size(); modelIndex++) {
            OpenNlpEntityModel entityModel = this.entityModels.get(modelIndex);
            NameFinderME finder = finders.get(modelIndex);
            Span[] entities = finder.find(tokens);
            double[] probabilities = finder.probs(entities);
            for (int index = 0; index < entities.length; index++) {
                if (spans.size() >= maxResultSpans) {
                    throw new OpenNlpAnalysisException(
                            PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                            "OpenNLP analyzer result exceeded the configured span limit"
                    );
                }
                Span entity = entities[index];
                validateEntitySpan(entity, tokenPositions.length);
                int start = tokenPositions[entity.getStart()].getStart();
                // getEnd() points after the entity, so end - 1 selects its last token.
                int end = tokenPositions[entity.getEnd() - 1].getEnd();
                spans.add(new PiiSpan(
                        entityModel.entityType(),
                        start,
                        end,
                        probabilities[index]
                ));
            }
        }
        return List.copyOf(spans);
    }

    private void requireLanguage(PiiAnalysisOptions options) {
        if (!this.language.equals(options.language())) {
            throw new OpenNlpAnalysisException(
                    PrivacyFailureCode.ANALYZER_EXECUTION_FAILED,
                    "OpenNLP analyzer language does not match the requested language"
            );
        }
    }

    private static void rejectInterruptedAnalysis() {
        if (Thread.currentThread().isInterrupted()) {
            throw new OpenNlpAnalysisException(
                    PrivacyFailureCode.ANALYSIS_INTERRUPTED,
                    "PII analysis interrupted"
            );
        }
    }

    static void validateEntitySpan(Span entity, int tokenCount) {
        if (entity == null || entity.getStart() < 0 || entity.getEnd() > tokenCount
                || entity.getStart() >= entity.getEnd()) {
            throw new OpenNlpAnalysisException(
                    PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION,
                    "OpenNLP analyzer returned an invalid entity span"
            );
        }
    }

    private static final class OpenNlpAnalysisException extends PrivacyGuardrailException {

        private OpenNlpAnalysisException(PrivacyFailureCode code, String safeMessage) {
            super(code, PrivacyPhase.ANALYSIS, safeMessage);
        }
    }
}

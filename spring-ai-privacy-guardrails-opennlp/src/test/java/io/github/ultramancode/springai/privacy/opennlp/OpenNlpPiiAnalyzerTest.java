package io.github.ultramancode.springai.privacy.opennlp;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import opennlp.tools.ml.model.SequenceClassificationModel;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.NameSample;
import opennlp.tools.namefind.TokenNameFinderFactory;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.util.CollectionObjectStream;
import opennlp.tools.util.Span;
import opennlp.tools.util.TrainingParameters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenNlpPiiAnalyzerTest {

    private static TokenNameFinderModel personModel;

    @BeforeAll
    static void trainModel() throws IOException {
        personModel = trainPersonModel();
    }

    @Test
    void analyzeMapsTokenSpansBackToUtf16CharacterOffsets() {
        OpenNlpPiiAnalyzer analyzer = analyzer();
        String text = "🙂 Alice joined";

        List<PiiSpan> spans = analyzer.analyze(text, PiiAnalysisOptions.defaults());

        assertThat(spans).anySatisfy(span -> {
            assertThat(span.entityType()).isEqualTo("PERSON");
            assertThat(span.start()).isEqualTo(3);
            assertThat(span.end()).isEqualTo(8);
            assertThat(text.substring(span.start(), span.end())).isEqualTo("Alice");
            assertThat(span.score()).isBetween(0.0, 1.0);
        });
    }

    @Test
    void trustedEntityTypesAreDeclaredFromConfiguredModels() {
        OpenNlpPiiAnalyzer analyzer = new OpenNlpPiiAnalyzer(
                "en",
                List.of(new OpenNlpEntityModel("CUSTOMER_ID", personModel))
        );

        assertThat(analyzer.trustedEntityTypes()).containsExactly("CUSTOMER_ID");
    }

    @Test
    void entityModelRejectsNonCanonicalConfiguredTypes() {
        assertThatThrownBy(() -> new OpenNlpEntityModel("customer-id", personModel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
    }

    @Test
    void analyzeFailsClearlyWhenConfiguredLanguageDoesNotMatch() {
        OpenNlpPiiAnalyzer analyzer = analyzer();

        assertThatThrownBy(() -> analyzer.analyze(
                "Alice",
                PiiAnalysisOptions.builder().language("ko").build()
        )).isInstanceOf(PrivacyGuardrailException.class)
                .hasMessage("OpenNLP analyzer language does not match the requested language");
    }

    @Test
    void constructorRejectsInvalidEntityModelCollections() {
        assertThatThrownBy(() -> new OpenNlpPiiAnalyzer("en", (List<OpenNlpEntityModel>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("entityModels must not be null");
        assertThatThrownBy(() -> new OpenNlpPiiAnalyzer("en", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");

        List<OpenNlpEntityModel> modelsWithNull = new ArrayList<>();
        modelsWithNull.add(null);
        assertThatThrownBy(() -> new OpenNlpPiiAnalyzer("en", modelsWithNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("entityModels must not contain null elements");
    }

    @Test
    void analyzeRejectsNullInputsBeforeBlankShortCircuit() {
        OpenNlpPiiAnalyzer analyzer = analyzer();

        assertThatThrownBy(() -> analyzer.analyze("", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("options must not be null");
        assertThatThrownBy(() -> analyzer.analyze(null, PiiAnalysisOptions.defaults()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("text must not be null");
    }

    @Test
    void validateEntitySpanRejectsInvalidFinderCoordinatesWithoutSensitiveData() {
        assertThatThrownBy(() -> OpenNlpPiiAnalyzer.validateEntitySpan(new Span(0, 0), 1))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessage("OpenNLP analyzer returned an invalid entity span");
        assertThatThrownBy(() -> OpenNlpPiiAnalyzer.validateEntitySpan(new Span(0, 2), 1))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessage("OpenNLP analyzer returned an invalid entity span");
        assertThatThrownBy(() -> OpenNlpPiiAnalyzer.validateEntitySpan(null, 1))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessage("OpenNLP analyzer returned an invalid entity span");
    }

    @Test
    void analyzeSanitizesPrivacyExceptionThrownByUntrustedModel() throws IOException {
        PrivacyGuardrailException injectedFailure = new PrivacyGuardrailException(
                PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION,
                PrivacyPhase.ANALYSIS,
                "synthetic-secret-model-failure"
        );
        OpenNlpPiiAnalyzer analyzer = new OpenNlpPiiAnalyzer(
                "en",
                List.of(new OpenNlpEntityModel("PERSON", modelThrowing(injectedFailure)))
        );

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, sanitized -> {
                    assertThat(sanitized.code()).isEqualTo(PrivacyFailureCode.ANALYZER_EXECUTION_FAILED);
                    assertThat(sanitized.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                    assertThat(sanitized)
                            .hasMessage("OpenNLP analyzer execution failed")
                            .hasMessageNotContaining("synthetic-secret")
                            .hasNoCause();
                });
    }

    @Test
    void analyzeRethrowsFatalModelFailure() throws IOException {
        OutOfMemoryError fatal = new OutOfMemoryError("synthetic-secret-model-failure");
        OpenNlpPiiAnalyzer analyzer = new OpenNlpPiiAnalyzer(
                "en",
                List.of(new OpenNlpEntityModel("PERSON", modelThrowing(fatal)))
        );

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .isSameAs(fatal);
    }

    @Test
    void analyzeIsSafeAcrossConcurrentThreads() throws Exception {
        OpenNlpPiiAnalyzer analyzer = analyzer();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<PiiSpan>>> futures = List.of(
                    executor.submit(() -> analyzer.analyze("Alice joined", PiiAnalysisOptions.defaults())),
                    executor.submit(() -> analyzer.analyze("Bob joined", PiiAnalysisOptions.defaults()))
            );

            PiiSpan alice = futures.get(0).get().get(0);
            PiiSpan bob = futures.get(1).get().get(0);
            assertThat("Alice joined".substring(alice.start(), alice.end())).isEqualTo("Alice");
            assertThat("Bob joined".substring(bob.start(), bob.end())).isEqualTo("Bob");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private OpenNlpPiiAnalyzer analyzer() {
        return new OpenNlpPiiAnalyzer("en", List.of(new OpenNlpEntityModel("PERSON", personModel)));
    }

    private TokenNameFinderModel modelThrowing(RuntimeException failure) throws IOException {
        return new TokenNameFinderModel(new ByteArrayInputStream(serializedPersonModel())) {
            @Override
            public SequenceClassificationModel getNameFinderSequenceModel() {
                throw failure;
            }
        };
    }

    private TokenNameFinderModel modelThrowing(Error failure) throws IOException {
        return new TokenNameFinderModel(new ByteArrayInputStream(serializedPersonModel())) {
            @Override
            public SequenceClassificationModel getNameFinderSequenceModel() {
                throw failure;
            }
        };
    }

    private byte[] serializedPersonModel() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        personModel.serialize(output);
        return output.toByteArray();
    }

    static TokenNameFinderModel trainPersonModel() throws IOException {
        List<NameSample> samples = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            samples.add(sample("Alice", "joined", "today"));
            samples.add(sample("Bob", "called", "support"));
            samples.add(sample("Carol", "opened", "account"));
        }
        TrainingParameters parameters = TrainingParameters.defaultParams();
        parameters.put(TrainingParameters.ITERATIONS_PARAM, 80);
        parameters.put(TrainingParameters.CUTOFF_PARAM, 1);
        return NameFinderME.train(
                "en",
                "person",
                new CollectionObjectStream<>(samples),
                parameters,
                new TokenNameFinderFactory()
        );
    }

    private static NameSample sample(String... tokens) {
        return new NameSample(tokens, new Span[]{new Span(0, 1, "person")}, false);
    }
}

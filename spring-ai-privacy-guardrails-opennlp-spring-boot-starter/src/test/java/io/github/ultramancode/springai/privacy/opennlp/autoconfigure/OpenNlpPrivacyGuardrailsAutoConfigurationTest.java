package io.github.ultramancode.springai.privacy.opennlp.autoconfigure;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.opennlp.OpenNlpPiiAnalyzer;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.NameSample;
import opennlp.tools.namefind.TokenNameFinderFactory;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.tokenize.TokenSample;
import opennlp.tools.tokenize.TokenizerFactory;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.CollectionObjectStream;
import opennlp.tools.util.Span;
import opennlp.tools.util.TrainingParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenNlpPrivacyGuardrailsAutoConfigurationTest {

    private static final AutoConfigurations AUTO_CONFIGURATIONS = AutoConfigurations.of(
            OpenNlpPrivacyGuardrailsAutoConfiguration.class,
            PrivacyGuardrailsAutoConfiguration.class
    );

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AUTO_CONFIGURATIONS)
            .withPropertyValues("spring.ai.privacy.enabled=true");

    @Test
    void autoConfigurationDoesNotCreateAnalyzerByDefault() {
        this.contextRunner
                .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of())
                .run(context -> assertThat(context)
                        .hasSingleBean(PrivacyService.class)
                        .doesNotHaveBean(OpenNlpPiiAnalyzer.class));
    }

    @Test
    void autoConfigurationRequiresGlobalOptInEvenWhenProviderIsEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AUTO_CONFIGURATIONS)
                .withPropertyValues("spring.ai.privacy.opennlp.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(OpenNlpPiiAnalyzer.class)
                        .doesNotHaveBean(PrivacyService.class));
    }

    @Test
    void autoConfigurationFailsFastWhenEnabledWithoutModels() {
        this.contextRunner
                .withPropertyValues("spring.ai.privacy.opennlp.enabled=true")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("entity-models"));
    }

    @Test
    void autoConfigurationRejectsBlankTokenizerModelInsteadOfSelectingTheDefault() {
        OpenNlpPrivacyGuardrailsProperties properties = new OpenNlpPrivacyGuardrailsProperties();
        properties.setTokenizerModel(" ");
        properties.setEntityModels(Map.of("PERSON", "test:model"));
        AtomicBoolean resourceRequested = new AtomicBoolean();

        assertThatThrownBy(() -> new OpenNlpPrivacyGuardrailsAutoConfiguration().openNlpPiiAnalyzer(
                properties,
                PiiAnalysisOptions.defaults(),
                trackingResourceLoader(resourceRequested)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tokenizer-model must not be blank");
        assertThat(resourceRequested).isFalse();
    }

    @Test
    void autoConfigurationValidatesEntityTypeBeforeLoadingItsModel() {
        OpenNlpPrivacyGuardrailsProperties properties = new OpenNlpPrivacyGuardrailsProperties();
        properties.setEntityModels(Map.of("person", "test:model"));
        AtomicBoolean resourceRequested = new AtomicBoolean();

        assertThatThrownBy(() -> new OpenNlpPrivacyGuardrailsAutoConfiguration().openNlpPiiAnalyzer(
                properties,
                PiiAnalysisOptions.defaults(),
                trackingResourceLoader(resourceRequested)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
        assertThat(resourceRequested).isFalse();
    }

    @Test
    void autoConfigurationPreservesApplicationOwnedModelLoadingFailure() {
        this.contextRunner
                .withPropertyValues(
                        "spring.ai.privacy.opennlp.enabled=true",
                        "spring.ai.privacy.opennlp.entity-models.PERSON=file:/synthetic-secret/missing.bin"
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(FileNotFoundException.class)
                        .hasStackTraceContaining("synthetic-secret"));
    }

    @Test
    void autoConfigurationDoesNotReplaceApplicationOwnedResourceLoaderError() {
        String modelLocation = "test:synthetic-secret-model";
        AssertionError loaderFailure = new AssertionError("synthetic-secret-loader-failure");

        contextRunnerWithResourceFailure(
                modelLocation,
                loaderFailure
        )
                .withPropertyValues(
                        "spring.ai.privacy.opennlp.enabled=true",
                        "spring.ai.privacy.opennlp.entity-models.PERSON=" + modelLocation
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isSameAs(loaderFailure));
    }

    @Test
    void autoConfigurationRethrowsFatalErrorFromResourceLoader() {
        String modelLocation = "test:model";
        LinkageError fatal = new LinkageError("synthetic-fatal-loader-failure");

        contextRunnerWithResourceFailure(modelLocation, fatal)
                .withPropertyValues(
                        "spring.ai.privacy.opennlp.enabled=true",
                        "spring.ai.privacy.opennlp.entity-models.PERSON=" + modelLocation
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isSameAs(fatal));
    }

    @Test
    void autoConfigurationLoadsTokenizerAndEntityModelsAndContributesAnalyzer(@TempDir Path tempDir)
            throws IOException {
        Path entityModelPath = tempDir.resolve("person.bin");
        try (OutputStream output = Files.newOutputStream(entityModelPath)) {
            trainPersonModel().serialize(output);
        }
        Path tokenizerModelPath = tempDir.resolve("tokenizer.bin");
        try (OutputStream output = Files.newOutputStream(tokenizerModelPath)) {
            trainTokenizerModel().serialize(output);
        }

        this.contextRunner
                .withPropertyValues(
                        "spring.ai.privacy.opennlp.enabled=true",
                        "spring.ai.privacy.analysis.language=custom-model-language",
                        "spring.ai.privacy.opennlp.tokenizer-model=" + tokenizerModelPath.toUri(),
                        "spring.ai.privacy.opennlp.entity-models.CUSTOMER_RECORD=" + entityModelPath.toUri()
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenNlpPiiAnalyzer.class);
                    PrivacyService service = context.getBean(PrivacyService.class);
                    assertThat(service.analyze("AliceJoined"))
                            .singleElement()
                            .satisfies(span -> {
                                assertThat(span.entityType()).isEqualTo("CUSTOMER_RECORD");
                                assertThat(span.start()).isZero();
                                assertThat(span.end()).isEqualTo(5);
                            });
                });
    }

    private TokenNameFinderModel trainPersonModel() throws IOException {
        List<NameSample> samples = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            samples.add(sample("Alice", "Joined"));
            samples.add(sample("Bob", "Called"));
            samples.add(sample("Carol", "Opened"));
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

    private TokenizerModel trainTokenizerModel() throws IOException {
        List<TokenSample> samples = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            samples.add(tokenSample("Alice", "Joined"));
            samples.add(tokenSample("Bob", "Called"));
            samples.add(tokenSample("Carol", "Opened"));
        }
        TrainingParameters parameters = TrainingParameters.defaultParams();
        parameters.put(TrainingParameters.ITERATIONS_PARAM, 80);
        parameters.put(TrainingParameters.CUTOFF_PARAM, 1);
        return TokenizerME.train(
                new CollectionObjectStream<>(samples),
                new TokenizerFactory("en", null, false, null),
                parameters
        );
    }

    private ApplicationContextRunner contextRunnerWithResourceFailure(String modelLocation, Error failure) {
        return new ApplicationContextRunner(() -> new AnnotationConfigApplicationContext() {
            @Override
            public Resource getResource(String location) {
                if (modelLocation.equals(location)) {
                    throw failure;
                }
                return super.getResource(location);
            }
        }).withConfiguration(AUTO_CONFIGURATIONS)
                .withPropertyValues("spring.ai.privacy.enabled=true");
    }

    private ResourceLoader trackingResourceLoader(AtomicBoolean resourceRequested) {
        return new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                resourceRequested.set(true);
                throw new AssertionError("resource must not be requested");
            }
        };
    }

    private NameSample sample(String... tokens) {
        return new NameSample(tokens, new Span[]{new Span(0, 1, "person")}, false);
    }

    private TokenSample tokenSample(String first, String second) {
        return TokenSample.parse(
                first + TokenSample.DEFAULT_SEPARATOR_CHARS + second,
                TokenSample.DEFAULT_SEPARATOR_CHARS
        );
    }
}

package io.github.ultramancode.springai.privacy.opennlp.autoconfigure;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.core.EntityTypeRegistry;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.opennlp.OpenNlpEntityModel;
import io.github.ultramancode.springai.privacy.opennlp.OpenNlpPiiAnalyzer;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.tokenize.TokenizerModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Auto-configures the Apache OpenNLP analyzer provider and model resources. */
@AutoConfiguration(before = PrivacyGuardrailsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "spring.ai.privacy", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OpenNlpPrivacyGuardrailsProperties.class)
public class OpenNlpPrivacyGuardrailsAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.privacy.opennlp", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(OpenNlpPiiAnalyzer.class)
    OpenNlpPiiAnalyzer openNlpPiiAnalyzer(
            OpenNlpPrivacyGuardrailsProperties properties,
            PiiAnalysisOptions analysisOptions,
            ResourceLoader resourceLoader
    ) throws IOException {
        if (properties.getEntityModels() == null || properties.getEntityModels().isEmpty()) {
            throw new IllegalStateException(
                    "spring.ai.privacy.opennlp.entity-models must contain at least one entity model"
            );
        }

        String tokenizerModelLocation = properties.getTokenizerModel();
        if (tokenizerModelLocation != null && tokenizerModelLocation.isBlank()) {
            throw new IllegalStateException(
                    "spring.ai.privacy.opennlp.tokenizer-model must not be blank"
            );
        }

        List<OpenNlpEntityModel> entityModels = new ArrayList<>();
        for (var modelEntry : properties.getEntityModels().entrySet()) {
            entityModels.add(loadEntityModel(resourceLoader, modelEntry.getKey(), modelEntry.getValue()));
        }
        TokenizerModel tokenizerModel = tokenizerModelLocation == null
                ? null : loadTokenizerModel(resourceLoader, tokenizerModelLocation);
        return new OpenNlpPiiAnalyzer(
                analysisOptions.language(),
                tokenizerModel,
                List.copyOf(entityModels)
        );
    }

    private OpenNlpEntityModel loadEntityModel(
            ResourceLoader resourceLoader,
            String entityType,
            String location
    ) throws IOException {
        String validatedEntityType = EntityTypeRegistry.requireValidEntityType(entityType);
        return new OpenNlpEntityModel(
                validatedEntityType,
                loadNameFinderModel(resourceLoader, location)
        );
    }

    private TokenNameFinderModel loadNameFinderModel(
            ResourceLoader resourceLoader,
            String location
    ) throws IOException {
        try (InputStream input = resolveModelResource(resourceLoader, location).getInputStream()) {
            return new TokenNameFinderModel(input);
        }
    }

    private TokenizerModel loadTokenizerModel(ResourceLoader resourceLoader, String location) throws IOException {
        try (InputStream input = resolveModelResource(resourceLoader, location).getInputStream()) {
            return new TokenizerModel(input);
        }
    }

    private Resource resolveModelResource(ResourceLoader resourceLoader, String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("OpenNLP model resource location must not be blank");
        }
        return resourceLoader.getResource(location);
    }

}

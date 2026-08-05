package io.github.ultramancode.springai.privacy.opennlp.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** Configuration for the Apache OpenNLP analyzer provider. */
@ConfigurationProperties("spring.ai.privacy.opennlp")
public class OpenNlpPrivacyGuardrailsProperties {

    /** Whether the OpenNLP analyzer is enabled. */
    private boolean enabled = false;
    /** Location of the OpenNLP tokenizer model. */
    private String tokenizerModel;
    /** Entity type to OpenNLP name-finder model location mappings. */
    private Map<String, String> entityModels = new LinkedHashMap<>();

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTokenizerModel() {
        return this.tokenizerModel;
    }

    public void setTokenizerModel(String tokenizerModel) {
        this.tokenizerModel = tokenizerModel;
    }

    public Map<String, String> getEntityModels() {
        return this.entityModels;
    }

    public void setEntityModels(Map<String, String> entityModels) {
        this.entityModels = entityModels;
    }
}

package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Configures protection stages and supporting advisors for model requests.
 * Combine feature configurers with {@link #compose(ModelRequestBoundaryConfigurer...)}
 * before applying them to a client builder.
 */
@FunctionalInterface
public interface ModelRequestBoundaryConfigurer extends UnaryOperator<ChatClient.Builder> {

    /** Registers supporting advisors and contributes stages to the selected client's plan. */
    void contributeToBoundary(ChatClient.Builder chatClientBuilder, ModelRequestBoundarySpec boundarySpec);

    /**
     * Applies this configurer and registers a new model request boundary on the supplied builder.
     *
     * <p>To register multiple features in one boundary, combine their configurers with
     * {@link #compose(ModelRequestBoundaryConfigurer...)} and call {@code configure(chatClientBuilder)}
     * once on the combined configurer.</p>
     *
     * @param chatClientBuilder the client builder to configure
     * @return the supplied builder
     */
    default ChatClient.Builder configure(ChatClient.Builder chatClientBuilder) {
        Objects.requireNonNull(chatClientBuilder, "chatClientBuilder");
        ModelRequestBoundarySpec boundarySpec = new ModelRequestBoundarySpec();
        contributeToBoundary(chatClientBuilder, boundarySpec);
        ModelRequestBoundaryAdvisor boundaryAdvisor = new ModelRequestBoundaryAdvisor(boundarySpec.build());
        chatClientBuilder.defaultAdvisors(new ModelRequestBoundaryChainValidator(boundaryAdvisor), boundaryAdvisor);
        return chatClientBuilder;
    }

    @Override
    default ChatClient.Builder apply(ChatClient.Builder chatClientBuilder) {
        return configure(chatClientBuilder);
    }

    /**
     * Returns a new configurer that applies this configuration and sets whether
     * application advisors may run after an inspection boundary. The current configurer is unchanged.
     *
     * <p>When allowed advisors are present, boundary execution logs a warning.
     * Content changed by those advisors is outside the final-inspection guarantee.
     * This option does not bypass stage rejections or other chain validation.</p>
     *
     * @param allow whether to allow application advisors after an inspection boundary
     * @return a new configurer with the selected setting
     */
    default ModelRequestBoundaryConfigurer allowAdvisorsAfterBoundary(boolean allow) {
        return (chatClientBuilder, boundarySpec) -> {
            contributeToBoundary(chatClientBuilder, boundarySpec);
            boundarySpec.allowAdvisorsAfterBoundary(allow);
        };
    }

    /**
     * Combines feature configurers into a configurer for one model request boundary.
     *
     * <p>When applied, the returned configurer invokes the supplied configurers in
     * argument order using the same boundary spec. During model requests, registered
     * stages execute in privacy, authorization, then inspection order, regardless of
     * registration order. Unconfigured stages are skipped.</p>
     *
     * @param configurers the feature configurers in registration order
     * @return a configurer that contributes the selected features to the same boundary
     */
    static ModelRequestBoundaryConfigurer compose(ModelRequestBoundaryConfigurer... configurers) {
        List<ModelRequestBoundaryConfigurer> selectedConfigurers = List.of(configurers);
        return (chatClientBuilder, boundarySpec) -> {
            for (ModelRequestBoundaryConfigurer boundaryConfigurer : selectedConfigurers) {
                boundaryConfigurer.contributeToBoundary(chatClientBuilder, boundarySpec);
            }
        };
    }
}

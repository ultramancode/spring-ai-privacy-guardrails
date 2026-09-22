package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Contributes client infrastructure and a model request stage to one boundary.
 * Compose contributions before applying them to a builder.
 * Separately configured boundaries are rejected at request time.
 * No shared builder registry is used.
 */
@FunctionalInterface
public interface ModelRequestBoundaryConfigurer extends UnaryOperator<ChatClient.Builder> {

    /** Registers supporting advisors and contributes stages to the selected client's plan. */
    void contributeToBoundary(ChatClient.Builder clientBuilder, ModelRequestBoundarySpec boundarySpec);

    /**
     * Applies this configurer and registers a new model request boundary on the supplied builder.
     *
     * <p>To register multiple features in one boundary, combine their configurers with
     * {@link #compose(ModelRequestBoundaryConfigurer...)} and call {@code configure(builder)}
     * once on the combined configurer. This method does not find and extend an existing boundary.</p>
     *
     * @param clientBuilder the client builder to configure
     * @return the supplied builder
     */
    default ChatClient.Builder configure(ChatClient.Builder clientBuilder) {
        Objects.requireNonNull(clientBuilder, "client");
        ModelRequestBoundarySpec boundarySpec = new ModelRequestBoundarySpec();
        contributeToBoundary(clientBuilder, boundarySpec);
        ModelRequestBoundaryAdvisor boundaryAdvisor = new ModelRequestBoundaryAdvisor(boundarySpec.build());
        clientBuilder.defaultAdvisors(new ModelRequestBoundaryChainValidator(boundaryAdvisor), boundaryAdvisor);
        return clientBuilder;
    }

    @Override
    default ChatClient.Builder apply(ChatClient.Builder clientBuilder) {
        return configure(clientBuilder);
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
        return (clientBuilder, boundarySpec) -> {
            contributeToBoundary(clientBuilder, boundarySpec);
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
        List<ModelRequestBoundaryConfigurer> selected = List.of(configurers);
        return (clientBuilder, boundarySpec) -> {
            for (ModelRequestBoundaryConfigurer configurer : selected) {
                configurer.contributeToBoundary(clientBuilder, boundarySpec);
            }
        };
    }
}

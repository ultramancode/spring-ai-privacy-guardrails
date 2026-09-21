package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Contributes client infrastructure and a model request stage to one boundary.
 * Compose contributions before applying them to a builder; separately configured
 * boundaries are rejected at request time. No shared builder registry is used.
 */
@FunctionalInterface
public interface ModelRequestBoundaryConfigurer extends UnaryOperator<ChatClient.Builder> {

    /** Registers supporting advisors and contributes stages to the selected client's plan. */
    void contribute(ChatClient.Builder clientBuilder, ModelRequestBoundarySpec boundarySpec);

    /** Registers one immutable boundary on the supplied builder and returns that builder. */
    default ChatClient.Builder configure(ChatClient.Builder clientBuilder) {
        Objects.requireNonNull(clientBuilder, "client");
        ModelRequestBoundarySpec boundarySpec = new ModelRequestBoundarySpec();
        contribute(clientBuilder, boundarySpec);
        ModelRequestBoundaryAdvisor boundaryAdvisor = new ModelRequestBoundaryAdvisor(boundarySpec.build());
        clientBuilder.defaultAdvisors(new ModelRequestBoundaryChainValidator(boundaryAdvisor), boundaryAdvisor);
        return clientBuilder;
    }

    @Override
    default ChatClient.Builder apply(ChatClient.Builder clientBuilder) {
        return configure(clientBuilder);
    }

    /**
     * Allows application advisors after an inspection boundary, with a warning.
     * Content changed by those advisors is outside the final-inspection guarantee.
     * This option never bypasses a stage's rejection or structural chain validation.
     */
    default ModelRequestBoundaryConfigurer allowAdvisorsAfterBoundary(boolean allow) {
        return (clientBuilder, boundarySpec) -> {
            contribute(clientBuilder, boundarySpec);
            boundarySpec.allowAdvisorsAfterBoundary(allow);
        };
    }

    /** Combines optional features; execution follows phase order, not contribution order. */
    static ModelRequestBoundaryConfigurer compose(ModelRequestBoundaryConfigurer... configurers) {
        List<ModelRequestBoundaryConfigurer> selected = List.of(configurers);
        return (clientBuilder, boundarySpec) -> {
            for (ModelRequestBoundaryConfigurer configurer : selected) {
                configurer.contribute(clientBuilder, boundarySpec);
            }
        };
    }
}

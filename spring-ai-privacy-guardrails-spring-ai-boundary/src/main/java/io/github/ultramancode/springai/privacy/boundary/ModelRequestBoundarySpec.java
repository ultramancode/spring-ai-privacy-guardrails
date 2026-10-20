package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * Registers privacy, authorization and inspection stages for a model request boundary.
 * Applications combine feature configurers through {@link ModelRequestBoundaryConfigurer#compose}.
 */
public final class ModelRequestBoundarySpec {
    /** Default order of the common model request boundary advisor. */
    public static final int DEFAULT_ORDER = Integer.MAX_VALUE - 3;

    private UnaryOperator<ChatClientRequest> privacyStage;
    private UnaryOperator<ChatClientRequest> authorizationStage;
    private BiConsumer<ChatClientRequest, Boolean> inspectionStage;
    private boolean allowAdvisorsAfterBoundary;

    ModelRequestBoundarySpec() {
    }

    /** Registers the privacy module's transformation, before definition filtering. */
    public void privacy(UnaryOperator<ChatClientRequest> stage) {
        if (privacyStage != null) {
            throw new IllegalStateException("Privacy stage already configured");
        }
        privacyStage = Objects.requireNonNull(stage, "privacy stage");
    }

    /** Registers the security stage that removes unauthorized tools from the model request. */
    public void authorization(UnaryOperator<ChatClientRequest> stage) {
        if (authorizationStage != null) {
            throw new IllegalStateException("Authorization stage already configured");
        }
        authorizationStage = Objects.requireNonNull(stage, "authorization stage");
    }

    /**
     * Registers final inspection of the prepared model request.
     * The second argument indicates a streaming request.
     * The inspection stage does not return a replacement request.
     */
    public void inspection(BiConsumer<ChatClientRequest, Boolean> stage) {
        if (inspectionStage != null) {
            throw new IllegalStateException("Inspection stage already configured");
        }
        inspectionStage = Objects.requireNonNull(stage, "inspection stage");
    }

    void allowAdvisorsAfterBoundary(boolean allow) {
        allowAdvisorsAfterBoundary = allow;
    }

    ModelRequestBoundaryPlan build() {
        if (privacyStage == null && authorizationStage == null && inspectionStage == null) {
            throw new IllegalStateException("At least one model request stage is required");
        }
        return new ModelRequestBoundaryPlan(privacyStage, authorizationStage, inspectionStage, allowAdvisorsAfterBoundary);
    }

    /** Supports identity validation of a feature's required model stage. */
    public static boolean containsStage(Advisor advisor, Object stage) {
        return advisor instanceof ModelRequestBoundaryAdvisor boundary && boundary.containsStage(stage);
    }

    /** Supports lifecycle validation without exposing the common advisor implementation. */
    public static boolean containsStageType(Advisor advisor, Class<?> type) {
        return advisor instanceof ModelRequestBoundaryAdvisor boundary && boundary.containsStageType(type);
    }
}

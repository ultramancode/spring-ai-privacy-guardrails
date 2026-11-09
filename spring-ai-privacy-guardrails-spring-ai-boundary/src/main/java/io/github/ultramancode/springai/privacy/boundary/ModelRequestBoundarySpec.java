package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Registers model request protection stages and optional user-message capture for output inspection.
 * Applications combine feature configurers through {@link ModelRequestBoundaryConfigurer#compose}.
 */
public final class ModelRequestBoundarySpec {
    /** Default order of the common model request boundary advisor. */
    public static final int DEFAULT_ORDER = Integer.MAX_VALUE - 3;

    private UnaryOperator<ChatClientRequest> privacyStage;
    private UnaryOperator<ChatClientRequest> authorizationStage;
    private ModelRequestInspectionStage inspectionStage;
    private boolean captureUserMessage;
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

    /** Registers inspection of the prepared request before each model call. */
    public void inspection(ModelRequestInspectionStage stage) {
        if (inspectionStage != null) {
            throw new IllegalStateException("Inspection stage already configured");
        }
        inspectionStage = Objects.requireNonNull(stage, "inspection stage");
    }

    /** Captures the latest user message after the configured stages for output inspection. */
    public void captureUserMessage() {
        captureUserMessage = true;
    }

    void allowAdvisorsAfterBoundary(boolean allow) {
        allowAdvisorsAfterBoundary = allow;
    }

    ModelRequestBoundaryPlan build() {
        if (privacyStage == null && authorizationStage == null && inspectionStage == null && !captureUserMessage) {
            throw new IllegalStateException("At least one model request stage or user-message capture is required");
        }
        return new ModelRequestBoundaryPlan(privacyStage, authorizationStage, inspectionStage,
                captureUserMessage, allowAdvisorsAfterBoundary);
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

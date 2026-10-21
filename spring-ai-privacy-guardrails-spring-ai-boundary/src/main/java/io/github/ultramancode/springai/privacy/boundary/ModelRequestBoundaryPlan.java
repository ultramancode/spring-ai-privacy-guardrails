package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Holds a fixed configuration of stages for the client and executes them in
 * privacy, authorization, then inspection order.
 */
final class ModelRequestBoundaryPlan {

    // A null stage means that feature was not configured for this client.
    private final UnaryOperator<ChatClientRequest> privacyStage;
    private final UnaryOperator<ChatClientRequest> authorizationStage;
    private final ModelRequestInspectionStage inspectionStage;
    private final boolean allowAdvisorsAfterBoundary;

    ModelRequestBoundaryPlan(UnaryOperator<ChatClientRequest> privacyStage,
        UnaryOperator<ChatClientRequest> authorizationStage, ModelRequestInspectionStage inspectionStage,
        boolean allowAdvisorsAfterBoundary) {
        this.privacyStage = privacyStage;
        this.authorizationStage = authorizationStage;
        this.inspectionStage = inspectionStage;
        this.allowAdvisorsAfterBoundary = allowAdvisorsAfterBoundary;
    }

    /** Tests stage identity when validating required feature infrastructure. */
    boolean containsStage(Object stage) {
        return stage != null && (stage == privacyStage || stage == authorizationStage || stage == inspectionStage);
    }

    /** Tests whether a feature's model stage is present without depending on that feature. */
    boolean containsStageType(Class<?> stageType) {
        return stageType.isInstance(privacyStage) || stageType.isInstance(authorizationStage) || stageType.isInstance(inspectionStage);
    }

    boolean hasInspection() {
        return inspectionStage != null;
    }

    boolean allowsAdvisorsAfterBoundary() {
        return allowAdvisorsAfterBoundary;
    }

    ChatClientRequest prepare(ChatClientRequest chatClientRequest, boolean streaming) {
        ChatClientRequest preparedRequest = chatClientRequest;
        if (privacyStage != null) {
            preparedRequest = Objects.requireNonNull(privacyStage.apply(preparedRequest), "privacy stage result");
        }
        if (authorizationStage != null) {
            preparedRequest = Objects.requireNonNull(authorizationStage.apply(preparedRequest), "authorization stage result");
        }
        if (inspectionStage != null) {
            inspectionStage.inspect(preparedRequest, streaming);
        }
        return preparedRequest;
    }

}

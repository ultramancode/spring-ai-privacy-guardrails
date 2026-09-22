package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Holds a fixed configuration of stages for the client and executes them in
 * privacy, authorization, then inspection order.
 */
final class ModelRequestBoundaryPlan {

    // A null stage means that feature was not configured for this client.
    private final UnaryOperator<ChatClientRequest> privacy;
    private final UnaryOperator<ChatClientRequest> authorization;
    private final Consumer<ChatClientRequest> inspection;
    private final boolean allowAdvisorsAfterBoundary;

    ModelRequestBoundaryPlan(UnaryOperator<ChatClientRequest> privacy,
            UnaryOperator<ChatClientRequest> authorization, Consumer<ChatClientRequest> inspection,
            boolean allowAdvisorsAfterBoundary) {
        this.privacy = privacy;
        this.authorization = authorization;
        this.inspection = inspection;
        this.allowAdvisorsAfterBoundary = allowAdvisorsAfterBoundary;
    }

    /** Tests stage identity when validating required feature infrastructure. */
    boolean containsStage(Object stage) {
        return stage != null && (stage == privacy || stage == authorization || stage == inspection);
    }

    /** Tests whether a feature's model stage is present without depending on that feature. */
    boolean containsStageType(Class<?> type) {
        return type.isInstance(privacy) || type.isInstance(authorization) || type.isInstance(inspection);
    }

    boolean hasInspection() {
        return inspection != null;
    }

    boolean allowsAdvisorsAfterBoundary() {
        return allowAdvisorsAfterBoundary;
    }

    ChatClientRequest prepare(ChatClientRequest request) {
        ChatClientRequest prepared = request;
        if (privacy != null) {
            prepared = Objects.requireNonNull(privacy.apply(prepared), "privacy stage result");
        }
        if (authorization != null) {
            prepared = Objects.requireNonNull(authorization.apply(prepared), "authorization stage result");
        }
        if (inspection != null) {
            inspection.accept(prepared);
        }
        return prepared;
    }

}

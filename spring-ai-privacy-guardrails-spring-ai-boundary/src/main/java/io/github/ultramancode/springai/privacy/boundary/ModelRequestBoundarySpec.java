package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Module integration contract used by the library's configurers. Its public visibility
 * allows independently optional feature modules to contribute to one boundary.
 * Applications should compose feature configurers rather than implement request stages.
 */
public final class ModelRequestBoundarySpec {
    /** Default order of the common model request boundary advisor. */
    public static final int DEFAULT_ORDER = Integer.MAX_VALUE - 3;

    private UnaryOperator<ChatClientRequest> privacy;
    private UnaryOperator<ChatClientRequest> authorization;
    private Consumer<ChatClientRequest> inspection;
    private boolean allowAdvisorsAfterBoundary;

    ModelRequestBoundarySpec() {
    }

    /** Registers the privacy module's transformation, before definition filtering. */
    public void privacy(UnaryOperator<ChatClientRequest> stage) {
        if (privacy != null) {
            throw new IllegalStateException("Privacy stage already configured");
        }
        privacy = Objects.requireNonNull(stage, "privacy stage");
    }

    /** Registers the security stage that removes unauthorized tools from the model request. */
    public void authorization(UnaryOperator<ChatClientRequest> stage) {
        if (authorization != null) {
            throw new IllegalStateException("Authorization stage already configured");
        }
        authorization = Objects.requireNonNull(stage, "authorization stage");
    }

    /**
     * Registers final inspection of the prepared model request.
     * The inspection stage does not return a replacement request.
     */
    public void inspection(Consumer<ChatClientRequest> stage) {
        if (inspection != null) {
            throw new IllegalStateException("Inspection stage already configured");
        }
        inspection = Objects.requireNonNull(stage, "inspection stage");
    }

    void allowAdvisorsAfterBoundary(boolean allow) {
        allowAdvisorsAfterBoundary = allow;
    }

    ModelRequestBoundaryPlan build() {
        if (privacy == null && authorization == null && inspection == null) {
            throw new IllegalStateException("At least one model request stage is required");
        }
        return new ModelRequestBoundaryPlan(privacy, authorization, inspection, allowAdvisorsAfterBoundary);
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

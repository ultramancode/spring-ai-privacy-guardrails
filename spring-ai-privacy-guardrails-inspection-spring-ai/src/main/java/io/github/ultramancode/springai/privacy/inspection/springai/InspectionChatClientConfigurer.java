package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundarySpec;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Client-scoped final text inspection. Use {@link ModelRequestBoundaryConfigurer#compose}
 * to combine privacy and inspection, or pass this contribution to a managed security factory.
 * No shared model or global client builder is modified.
 */
public final class InspectionChatClientConfigurer implements ModelRequestBoundaryConfigurer {

    private final InspectionService service;
    private final InspectionLimits limits;
    private final ContentRepresentationResolver representationResolver;
    private final Consumer<InspectionReport> observer;
    private final WeakHashMap<ChatClient.Builder, Boolean> configuredBuilders = new WeakHashMap<>();

    public InspectionChatClientConfigurer(InspectionService service) {
        this(
                service,
                InspectionLimits.defaults(),
                ContentRepresentationResolver.asReceived(),
                ignored -> {});
    }

    public InspectionChatClientConfigurer(
            InspectionService service,
            InspectionLimits limits,
            ContentRepresentationResolver representationResolver,
            Consumer<InspectionReport> observer) {
        this.service = Objects.requireNonNull(service, "service");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.representationResolver = Objects.requireNonNull(representationResolver, "representationResolver");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Contributes one inspection stage that runs the service's configured inspectors
     * at the selected client's model request boundary.
     */
    @Override
    public void contributeToBoundary(ChatClient.Builder builder, ModelRequestBoundarySpec boundary) {
        Objects.requireNonNull(builder, "builder");
        synchronized (configuredBuilders) {
            if (configuredBuilders.containsKey(builder)) {
                throw new IllegalStateException("Builder already configured for inspection");
            }
            boundary.inspection(new ContentInspectionStage(service, limits, representationResolver, observer));
            configuredBuilders.put(builder, true);
        }
    }
}

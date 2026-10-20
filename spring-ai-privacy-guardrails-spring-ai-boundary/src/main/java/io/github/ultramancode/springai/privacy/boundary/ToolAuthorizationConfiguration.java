package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;

import java.util.Objects;

/**
 * Tool advisor builder and matching model-request boundary for tool authorization.
 * Apply both components to the same client builder.
 *
 * @param toolAdvisorBuilder builder that applies authorization to tool execution
 * @param boundaryConfigurer registers authorization lifecycle, definition filtering and advisor validation
 */
public record ToolAuthorizationConfiguration(
    ToolCallingAdvisor.Builder<?> toolAdvisorBuilder,
    ModelRequestBoundaryConfigurer boundaryConfigurer) {

    public ToolAuthorizationConfiguration {
        Objects.requireNonNull(toolAdvisorBuilder, "toolAdvisorBuilder");
        Objects.requireNonNull(boundaryConfigurer, "boundaryConfigurer");
    }
}

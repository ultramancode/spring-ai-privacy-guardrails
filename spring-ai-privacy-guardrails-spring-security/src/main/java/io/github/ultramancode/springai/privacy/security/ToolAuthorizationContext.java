package io.github.ultramancode.springai.privacy.security;

import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Objects;

/**
 * Describes a tool authorization check. Tool input and original request data are not included.
 *
 * @param toolDefinition definition of the tool being authorized
 * @param phase phase at which authorization is evaluated
 */
public record ToolAuthorizationContext(
        ToolDefinition toolDefinition,
        ToolAuthorizationPhase phase
) {

    public ToolAuthorizationContext {
        Objects.requireNonNull(toolDefinition, "toolDefinition must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
    }
}

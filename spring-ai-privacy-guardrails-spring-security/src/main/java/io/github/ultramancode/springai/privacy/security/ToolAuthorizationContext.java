package io.github.ultramancode.springai.privacy.security;

import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Objects;

/**
 * Carries the tool definition and authorization phase for one policy check.
 * The integration does not add tool input arguments or request PII to this context.
 *
 * @param toolDefinition definition of the tool being considered
 * @param phase authorization checkpoint
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

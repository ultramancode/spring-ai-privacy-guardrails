package io.github.ultramancode.springai.privacy.security;

import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Objects;

/**
 * Public Spring Security authorization object for one tool checkpoint.
 * Tool arguments are intentionally excluded so authorization cannot observe raw PII.
 *
 * @param toolDefinition immutable definition of the tool being considered
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

package io.github.ultramancode.springai.privacy.test;

import java.util.Objects;

/**
 * Tool definition text observed at the model boundary during a test.
 *
 * @param name executable tool name
 * @param description model-visible tool description, when present
 * @param inputSchema model-visible JSON input schema, when present
 */
public record ToolDefinitionSnapshot(String name, String description, String inputSchema) {

    /** Validates the required tool name. */
    public ToolDefinitionSnapshot {
        name = Objects.requireNonNull(name, "name must not be null");
    }

    @Override
    public String toString() {
        return "ToolDefinitionSnapshot[namePresent=" + !this.name.isEmpty()
                + ", descriptionPresent=" + (this.description != null && !this.description.isEmpty())
                + ", inputSchemaPresent=" + (this.inputSchema != null && !this.inputSchema.isEmpty()) + "]";
    }
}

package io.github.ultramancode.springai.privacy.test;

import java.util.List;
import java.util.Objects;

/**
 * Immutable text captured at the model boundary during a test invocation.
 *
 * @param invocation invocation style used by the application
 * @param modelVisibleContent message text, tool-call arguments, and tool-response data sent to the model
 * @param toolDefinitions tool definitions visible to the model
 * @param toolControlFields tool-call and tool-response routing fields visible to the model
 */
public record ModelRequestSnapshot(
        Invocation invocation,
        List<String> modelVisibleContent,
        List<ToolDefinitionSnapshot> toolDefinitions,
        List<ToolControlFieldSnapshot> toolControlFields
) {

    /** Defensively copies all captured model-boundary data. */
    public ModelRequestSnapshot {
        invocation = Objects.requireNonNull(invocation, "invocation must not be null");
        modelVisibleContent = List.copyOf(Objects.requireNonNull(
                modelVisibleContent,
                "modelVisibleContent must not be null"
        ));
        toolDefinitions = List.copyOf(Objects.requireNonNull(
                toolDefinitions,
                "toolDefinitions must not be null"
        ));
        toolControlFields = List.copyOf(Objects.requireNonNull(
                toolControlFields,
                "toolControlFields must not be null"
        ));
    }

    /**
     * Creates a snapshot without captured tool definitions or control fields.
     *
     * @param invocation invocation style used by the application
     * @param modelVisibleContent textual content sent to the model
     */
    public ModelRequestSnapshot(Invocation invocation, List<String> modelVisibleContent) {
        this(invocation, modelVisibleContent, List.of(), List.of());
    }

    /** Invocation style used to enter the model. */
    public enum Invocation {

        /** Non-streaming model invocation. */
        CALL,

        /** Streaming model invocation. */
        STREAM
    }

    @Override
    public String toString() {
        return "ModelRequestSnapshot[invocation=" + this.invocation
                + ", modelVisibleContentCount=" + this.modelVisibleContent.size()
                + ", toolDefinitionCount=" + this.toolDefinitions.size()
                + ", toolControlFieldCount=" + this.toolControlFields.size() + "]";
    }
}

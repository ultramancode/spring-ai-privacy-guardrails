package io.github.ultramancode.springai.privacy.test;

import java.util.Objects;

/**
 * Tool-call or tool-response control fields observed at the model boundary.
 *
 * @param source message channel from which the fields were captured
 * @param id provider or application tool-call identifier, when present
 * @param toolCallType provider or application tool-call type, when present
 * @param name executable tool name, when present
 */
public record ToolControlFieldSnapshot(Source source, String id, String toolCallType, String name) {

    /** Validates the control-field source. */
    public ToolControlFieldSnapshot {
        source = Objects.requireNonNull(source, "source must not be null");
    }

    /** Model-visible channel that supplied the control fields. */
    public enum Source {

        /** Tool call requested by an assistant message. */
        ASSISTANT_TOOL_CALL,

        /** Tool result returned through a tool-response message. */
        TOOL_RESPONSE
    }

    @Override
    public String toString() {
        return "ToolControlFieldSnapshot[source=" + this.source
                + ", idPresent=" + (this.id != null)
                + ", toolCallTypePresent=" + (this.toolCallType != null)
                + ", namePresent=" + (this.name != null) + "]";
    }
}

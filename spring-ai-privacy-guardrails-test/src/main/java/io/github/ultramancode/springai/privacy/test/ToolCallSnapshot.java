package io.github.ultramancode.springai.privacy.test;

/**
 * Immutable input and output observed at a delegate tool boundary during a test.
 *
 * @param toolName tool definition name
 * @param input input received by the delegate tool
 * @param output output returned by the delegate tool, or {@code null} when it failed
 * @param failureClassName exception or error class name, or {@code null} when it succeeded
 */
public record ToolCallSnapshot(
        String toolName,
        String input,
        String output,
        String failureClassName
) {

    @Override
    public String toString() {
        return "ToolCallSnapshot[toolName=" + this.toolName
                + ", inputPresent=" + (this.input != null)
                + ", outputPresent=" + (this.output != null)
                + ", failureClassName=" + this.failureClassName + "]";
    }
}

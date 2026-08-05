package io.github.ultramancode.springai.privacy.test;

import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.Collection;

/** Entry point for privacy boundary assertions. */
public final class PrivacyTestAssertions {

    private PrivacyTestAssertions() {
    }

    /**
     * Creates fluent assertions for one privacy test probe.
     *
     * @param actual probe whose captured boundaries should be asserted
     * @return fluent privacy assertions
     */
    public static PrivacyTestProbeAssert assertThatPrivacy(PrivacyTestProbe actual) {
        return new PrivacyTestProbeAssert(actual);
    }

    /**
     * Verifies that every callback planned for Spring AI registration is privacy wrapped.
     * The assertion asks the supplied factory to verify its own wrapping provenance and
     * never invokes callback metadata or string rendering that could retain sensitive test values.
     *
     * @param toolCallbackFactory factory expected to have created every wrapper
     * @param toolCallbacks callbacks planned for Spring AI registration
     */
    public static void assertThatToolsArePrivacyWrapped(
            PrivacyToolCallbackFactory toolCallbackFactory,
            Collection<? extends ToolCallback> toolCallbacks
    ) {
        if (toolCallbackFactory == null) {
            throw new AssertionError("Expected a non-null privacy tool factory");
        }
        if (toolCallbacks == null) {
            throw new AssertionError("Expected a non-null collection of privacy-wrapped tool callbacks");
        }
        int index = 0;
        for (ToolCallback callback : toolCallbacks) {
            requirePrivacyWrapped(toolCallbackFactory, callback, index++);
        }
    }

    /**
     * Verifies a planned callback array in argument order.
     *
     * @param toolCallbackFactory factory expected to have created every wrapper
     * @param toolCallbacks callbacks planned for Spring AI registration
     */
    public static void assertThatToolsArePrivacyWrapped(
            PrivacyToolCallbackFactory toolCallbackFactory,
            ToolCallback... toolCallbacks
    ) {
        if (toolCallbacks == null) {
            throw new AssertionError("Expected a non-null array of privacy-wrapped tool callbacks");
        }
        assertThatToolsArePrivacyWrapped(toolCallbackFactory, Arrays.asList(toolCallbacks));
    }

    private static void requirePrivacyWrapped(
            PrivacyToolCallbackFactory toolCallbackFactory,
            ToolCallback callback,
            int index
    ) {
        if (!toolCallbackFactory.isWrapped(callback)) {
            String actualType = callback == null ? "null" : callback.getClass().getName();
            throw new AssertionError(
                    "Expected tool callback at index <" + index
                            + "> to be wrapped by the supplied privacy factory but was <"
                            + actualType + ">"
            );
        }
    }
}

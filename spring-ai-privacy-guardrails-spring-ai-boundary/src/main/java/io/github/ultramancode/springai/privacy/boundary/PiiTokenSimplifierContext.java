package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Internal handoff of a session-bound PII token simplifier from Privacy to inspection.
 *
 * @hidden
 */
public final class PiiTokenSimplifierContext {
    private static final String KEY = "io.github.ultramancode.springai.privacy.pii-token-simplifier";

    private PiiTokenSimplifierContext() {
    }

    public static ChatClientRequest attach(ChatClientRequest request, UnaryOperator<String> simplifier) {
        return request.mutate().context(KEY, new TokenSimplifier(Objects.requireNonNull(simplifier, "simplifier")))
                .build();
    }

    public static UnaryOperator<String> findSimplifier(ChatClientRequest request) {
        Object value = request.context().get(KEY);
        if (value instanceof TokenSimplifier simplifier) {
            return simplifier.transform();
        }
        return null;
    }

    public static boolean hasInternalEntries(Map<String, Object> context) {
        return context.containsKey(KEY);
    }

    public static void removeInternalEntries(Map<String, Object> context) {
        context.remove(KEY);
    }

    private record TokenSimplifier(UnaryOperator<String> transform) {
        @Override
        public String toString() {
            return "TokenSimplifier[session=<redacted>]";
        }
    }
}

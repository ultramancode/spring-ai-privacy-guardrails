package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Passes a PII token simplifier from Privacy to inspection through request context.
 * Privacy supplies a function that uses the current privacy session.
 * Inspection retrieves that function and applies it to the text it inspects.
 *
 * @hidden
 */
public final class PiiTokenSimplifierContext {
    private static final String PII_TOKEN_SIMPLIFIER_CONTEXT_KEY =
            "io.github.ultramancode.springai.privacy.pii-token-simplifier";

    private PiiTokenSimplifierContext() {
    }

    /**
     * Wraps the supplied function in a {@code TokenSimplifier} and stores it in a new request's context.
     * Inspection retrieves the function with {@link #findSimplifier(ChatClientRequest)} and applies it later.
     */
    public static ChatClientRequest attach(
            ChatClientRequest chatClientRequest,
            UnaryOperator<String> simplifierFunction
    ) {
        Objects.requireNonNull(simplifierFunction, "simplifierFunction");
        TokenSimplifier tokenSimplifier = new TokenSimplifier(simplifierFunction);

        return chatClientRequest.mutate()
                .context(PII_TOKEN_SIMPLIFIER_CONTEXT_KEY, tokenSimplifier)
                .build();
    }

    /**
     * Retrieves the same function supplied to {@link #attach(ChatClientRequest, UnaryOperator)}.
     * The caller executes the returned function by calling {@code apply(text)}.
     *
     * @return the stored function, or {@code null} if the entry is absent or has an unexpected type
     */
    public static UnaryOperator<String> findSimplifier(ChatClientRequest chatClientRequest) {
        Object contextValue = chatClientRequest.context().get(PII_TOKEN_SIMPLIFIER_CONTEXT_KEY);
        if (contextValue instanceof TokenSimplifier tokenSimplifier) {
            return tokenSimplifier.simplifierFunction();
        }
        return null;
    }

    public static boolean containsSimplifierEntry(Map<String, Object> context) {
        return context.containsKey(PII_TOKEN_SIMPLIFIER_CONTEXT_KEY);
    }

    public static void removeSimplifierEntry(Map<String, Object> context) {
        context.remove(PII_TOKEN_SIMPLIFIER_CONTEXT_KEY);
    }

    /**
     * Stores the simplifier function passed to {@code attach()}.
     * When the context value is an instance of this record, {@code findSimplifier()}
     * returns the function stored in that instance.
     * {@code toString()} always returns a fixed message.
     */
    private record TokenSimplifier(UnaryOperator<String> simplifierFunction) {
        public UnaryOperator<String> simplifierFunction() {
            return simplifierFunction;
        }

        @Override
        public String toString() {
            return "TokenSimplifier[session=<redacted>]";
        }
    }
}

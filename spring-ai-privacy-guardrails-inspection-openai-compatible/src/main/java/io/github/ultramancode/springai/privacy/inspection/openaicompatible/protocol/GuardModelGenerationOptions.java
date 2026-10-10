package io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol;

import com.openai.models.chat.completions.ChatCompletionCreateParams;

/**
 * Generation settings for the built-in guard-model protocols.
 *
 * @param maxCompletionTokens positive limit for generated tokens, including reasoning tokens
 * @param temperature sampling temperature between 0.0 and 2.0, or null to omit the parameter
 *        and use the server's default
 */
public record GuardModelGenerationOptions(long maxCompletionTokens, Double temperature) {

    public GuardModelGenerationOptions {
        if (maxCompletionTokens < 1) {
            throw new IllegalArgumentException("maxCompletionTokens must be positive");
        }
        if (temperature != null) {
            if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
                throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
            }
        }
    }

    void applyTo(ChatCompletionCreateParams.Builder requestBuilder) {
        requestBuilder.maxCompletionTokens(maxCompletionTokens);
        if (temperature != null) {
            requestBuilder.temperature(temperature);
        }
    }
}

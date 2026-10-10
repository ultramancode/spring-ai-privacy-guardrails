package io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.util.Objects;
import java.util.Optional;

/** Builds Kanana Safeguard-Prompt requests with one user message and parses its single-token verdicts. */
public final class KananaSafeguardPromptProtocol implements GuardModelProtocol {

    private static final GuardModelGenerationOptions DEFAULT_GENERATION_OPTIONS =
            new GuardModelGenerationOptions(1L, 0.0);

    private final GuardModelGenerationOptions generationOptions;

    /** Uses a one-token limit and temperature 0. */
    public KananaSafeguardPromptProtocol() {
        this(DEFAULT_GENERATION_OPTIONS);
    }

    /**
     * Uses the supplied generation settings with Kanana Safeguard-Prompt's request and verdict format.
     *
     * @param generationOptions generation settings
     */
    public KananaSafeguardPromptProtocol(GuardModelGenerationOptions generationOptions) {
        this.generationOptions = Objects.requireNonNull(generationOptions, "generationOptions");
    }

    @Override
    public ChatCompletionCreateParams request(String model, String text) {
        ChatCompletionCreateParams.Builder requestBuilder = ChatCompletionCreateParams.builder()
                .model(model)
                .n(1L)
                .addUserMessage(text);
        generationOptions.applyTo(requestBuilder);
        return requestBuilder.build();
    }

    @Override
    public Optional<InspectionFinding> parse(String segmentId, String output) {
        return switch (output.strip()) {
            case "<SAFE>" -> Optional.empty();
            case "<UNSAFE-A1>" ->
                    Optional.of(new InspectionFinding(
                            segmentId,
                            InspectionFinding.Category.PROMPT_INJECTION,
                            "UNSAFE-A1",
                            null));
            case "<UNSAFE-A2>" ->
                    Optional.of(new InspectionFinding(
                            segmentId,
                            InspectionFinding.Category.PROMPT_LEAKING,
                            "UNSAFE-A2",
                            null));
            default -> throw new InspectionException(InspectionFailureCode.INVALID_RESPONSE);
        };
    }
}

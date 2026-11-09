package io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.util.Objects;
import java.util.Optional;

/**
 * Builds Kanana Safeguard 8B requests to inspect model responses and parses single-token verdicts.
 * Sends the response as an {@code assistant} message and the reference question as a {@code user} message.
 * When no question is supplied, the user message is empty. Earlier conversation turns are not sent.
 *
 * <p>{@code <SAFE>} produces no finding. Unsafe verdicts map to these categories:
 * <ul>
 * <li>{@code <UNSAFE-S1>}: {@link InspectionFinding.Category#HATE_SPEECH}</li>
 * <li>{@code <UNSAFE-S2>}: {@link InspectionFinding.Category#HARASSMENT}</li>
 * <li>{@code <UNSAFE-S3>}: {@link InspectionFinding.Category#SEXUAL_CONTENT}</li>
 * <li>{@code <UNSAFE-S4>}: {@link InspectionFinding.Category#CRIMINAL_ACTIVITY}</li>
 * <li>{@code <UNSAFE-S5>}: {@link InspectionFinding.Category#CHILD_SEXUAL_EXPLOITATION}</li>
 * <li>{@code <UNSAFE-S6>}: {@link InspectionFinding.Category#SELF_HARM}</li>
 * <li>{@code <UNSAFE-S7>}: {@link InspectionFinding.Category#MISINFORMATION}</li>
 * </ul>
 * <p>Findings preserve the model's code, such as {@code UNSAFE-S3}, without a confidence score.
 * Unknown, incomplete or multiple labels fail with {@link InspectionFailureCode#INVALID_RESPONSE}.
 */
public final class KananaSafeguardOutputProtocol implements GuardModelProtocol {

    private static final GuardModelGenerationOptions DEFAULT_GENERATION_OPTIONS =
            new GuardModelGenerationOptions(1L, 0.0);

    private final GuardModelGenerationOptions generationOptions;

    /** Uses a one-token limit and temperature 0. */
    public KananaSafeguardOutputProtocol() {
        this(DEFAULT_GENERATION_OPTIONS);
    }

    /**
     * Uses the supplied generation settings with Kanana Safeguard's output classification format.
     *
     * @param generationOptions generation settings
     */
    public KananaSafeguardOutputProtocol(GuardModelGenerationOptions generationOptions) {
        this.generationOptions = Objects.requireNonNull(generationOptions, "generationOptions");
    }

    @Override
    public ChatCompletionCreateParams request(String model, String text) {
        return request(model, text, null);
    }

    @Override
    public ChatCompletionCreateParams request(String model, String text, String userMessage) {
        ChatCompletionCreateParams.Builder requestBuilder = ChatCompletionCreateParams.builder()
                .model(model)
                .n(1L)
                .addUserMessage(userMessage == null ? "" : userMessage)
                .addAssistantMessage(text);
        generationOptions.applyTo(requestBuilder);
        return requestBuilder.build();
    }

    @Override
    public Optional<InspectionFinding> parse(String segmentId, String output) {
        String verdict = output.strip();
        if (verdict.equals("<SAFE>")) {
            return Optional.empty();
        }
        InspectionFinding.Category category = switch (verdict) {
            case "<UNSAFE-S1>" -> InspectionFinding.Category.HATE_SPEECH;
            case "<UNSAFE-S2>" -> InspectionFinding.Category.HARASSMENT;
            case "<UNSAFE-S3>" -> InspectionFinding.Category.SEXUAL_CONTENT;
            case "<UNSAFE-S4>" -> InspectionFinding.Category.CRIMINAL_ACTIVITY;
            case "<UNSAFE-S5>" -> InspectionFinding.Category.CHILD_SEXUAL_EXPLOITATION;
            case "<UNSAFE-S6>" -> InspectionFinding.Category.SELF_HARM;
            case "<UNSAFE-S7>" -> InspectionFinding.Category.MISINFORMATION;
            default -> throw new InspectionException(InspectionFailureCode.INVALID_RESPONSE);
        };
        String code = verdict.substring(1, verdict.length() - 1);
        return Optional.of(new InspectionFinding(segmentId, category, code, null));
    }
}

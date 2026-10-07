package io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Kanana Safeguard-Prompt's single-user-turn, single-token classification contract. */
public final class KananaPromptProtocol implements GuardModelProtocol {

    @Override
    public boolean acceptsLengthFinish() {
        return true;
    }

    @Override
    public Map<String, Object> request(String model, String text) {
        return Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", text)),
                "stream", false,
                "temperature", 0,
                "max_tokens", 1,
                "add_generation_prompt", false,
                "skip_special_tokens", false);
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

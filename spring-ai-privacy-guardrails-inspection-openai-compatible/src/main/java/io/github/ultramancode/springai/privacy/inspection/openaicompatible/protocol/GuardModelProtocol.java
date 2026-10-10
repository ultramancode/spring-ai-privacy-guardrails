package io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.util.Optional;

/**
 * Builds requests and parses verdicts for a guard model served through an OpenAI-compatible endpoint.
 * Implementations are shared across requests and must be thread-safe. Do not retain inspected text.
 * The HTTP inspector handles network requests, privacy requirements, timeouts and result collection.
 */
public interface GuardModelProtocol {

    /**
     * Builds non-null chat-completion parameters for one text segment.
     * The inspector accepts one response choice containing a text verdict, so leave {@code n} unset or use 1.
     * It uses non-streaming calls and does not execute tool calls.
     */
    ChatCompletionCreateParams request(String model, String text);

    /**
     * Parses the model output for one text segment.
     * Implementations must validate the complete verdict from the output alone.
     * An empty optional means a safe verdict.
     * Incomplete, malformed or unknown output must throw an {@link InspectionException} with
     * {@link InspectionFailureCode#INVALID_RESPONSE}.
     * A null return or an unexpected runtime exception causes INVALID_RESULT, even with FAIL_OPEN.
     *
     * @return a non-null optional containing the finding, or empty for a safe verdict
     */
    Optional<InspectionFinding> parse(String segmentId, String output);
}

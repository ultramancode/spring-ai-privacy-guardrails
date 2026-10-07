package io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.util.Map;
import java.util.Optional;

/**
 * Request and verdict semantics for a guard model served through an OpenAI-compatible endpoint.
 * Implementations are shared across requests and must be thread-safe without retaining content.
 * The HTTP inspector owns transport, disclosure checks, deadlines and result aggregation.
 */
public interface GuardModelProtocol {

    /** Builds a non-null, non-streaming chat-completions payload for one text segment. */
    Map<String, Object> request(String model, String text);

    /**
     * Parses one completed model response. An empty optional means the protocol's safe verdict.
     * Malformed or unknown output must throw an {@link InspectionException} with
     * {@link InspectionFailureCode#INVALID_RESPONSE}.
     * A null return or an unexpected runtime exception is a contract failure and cannot fail open.
     *
     * @return a non-null optional containing the finding, or empty for a safe verdict
     */
    Optional<InspectionFinding> parse(String segmentId, String output);

    /**
     * Whether the protocol accepts a response with {@code finish_reason="length"}.
     * The output must still pass {@link #parse(String, String)} validation.
     */
    boolean acceptsLengthFinish();
}

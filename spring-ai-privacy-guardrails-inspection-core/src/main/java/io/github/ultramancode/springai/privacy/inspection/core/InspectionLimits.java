package io.github.ultramancode.springai.privacy.inspection.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Finite input and execution limits for one inspection request.
 *
 * @param maxSegments maximum number of text segments in the request
 * @param maxCharacters maximum combined text length in UTF-16 code units
 * @param maxChunks maximum chunks per inspector across all request segments, such as ONNX
 *        windows or remote segment requests
 * @param timeout total time budget shared by all inspectors, starting when the
 *        {@link InspectionRequest} is constructed
 */
public record InspectionLimits(
        int maxSegments, int maxCharacters, int maxChunks, Duration timeout) {

    public InspectionLimits {
        Objects.requireNonNull(timeout, "timeout");
        if (maxSegments < 1 || maxSegments > 10_000) {
            throw new IllegalArgumentException("maxSegments must be between 1 and 10000");
        }
        if (maxCharacters < 1 || maxCharacters > 10_000_000) {
            throw new IllegalArgumentException("maxCharacters must be between 1 and 10000000");
        }
        if (maxChunks < 1 || maxChunks > 10_000) {
            throw new IllegalArgumentException("maxChunks must be between 1 and 10000");
        }
        if (timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most 1 hour");
        }
    }

    public static InspectionLimits defaults() {
        return new InspectionLimits(64, 131_072, 256, Duration.ofSeconds(10));
    }
}

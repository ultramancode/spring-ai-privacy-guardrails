package io.github.ultramancode.springai.privacy.inspection.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Finite input and execution limits for one inspection request.
 *
 * @param maxSegments maximum number of text segments in the request
 * @param maxCharacters maximum combined text length in UTF-16 code units
 * @param timeout elapsed time budget for further inspection work and response waits,
 *        starting when the {@link InspectionRequest} is constructed. Policy evaluation also
 *        consumes this budget
 */
public record InspectionLimits(int maxSegments, int maxCharacters, Duration timeout) {

    public static final int DEFAULT_MAX_SEGMENTS = 64;
    public static final int DEFAULT_MAX_CHARACTERS = 131_072;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public InspectionLimits {
        Objects.requireNonNull(timeout, "timeout");
        if (maxSegments < 1) {
            throw new IllegalArgumentException("maxSegments must be positive");
        }
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            timeout.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("timeout must fit in nanoseconds");
        }
    }

    public static InspectionLimits defaults() {
        return new InspectionLimits(DEFAULT_MAX_SEGMENTS, DEFAULT_MAX_CHARACTERS, DEFAULT_TIMEOUT);
    }
}

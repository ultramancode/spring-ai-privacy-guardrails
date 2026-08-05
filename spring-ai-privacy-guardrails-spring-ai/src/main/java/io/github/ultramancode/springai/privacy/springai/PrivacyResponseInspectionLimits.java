package io.github.ultramancode.springai.privacy.springai;

import java.time.Duration;
import java.util.Objects;

/**
 * Hard limits for inspecting one model response and buffering one response stream.
 *
 * @param maxStreamFrames maximum response frames inspected per streaming subscription
 * @param maxCharacters maximum cumulative text and textual metadata characters
 * @param maxMediaBytes maximum cumulative bytes from supported media payloads
 * @param streamIdleTimeout maximum wait between streaming response frames
 */
public record PrivacyResponseInspectionLimits(
        int maxStreamFrames,
        long maxCharacters,
        long maxMediaBytes,
        Duration streamIdleTimeout
) {

    /** Default maximum number of frames inspected per streaming response subscription. */
    public static final int DEFAULT_MAX_STREAM_FRAMES = 1024;

    /** Default maximum cumulative textual characters inspected per response. */
    public static final long DEFAULT_MAX_CHARACTERS = 1_000_000;

    /** Default maximum cumulative supported media bytes inspected per response. */
    public static final long DEFAULT_MAX_MEDIA_BYTES = 16 * 1024 * 1024;

    /** Default maximum wait between streaming response frames. */
    public static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(60);

    private static final PrivacyResponseInspectionLimits DEFAULTS = new PrivacyResponseInspectionLimits(
            DEFAULT_MAX_STREAM_FRAMES,
            DEFAULT_MAX_CHARACTERS,
            DEFAULT_MAX_MEDIA_BYTES,
            DEFAULT_STREAM_IDLE_TIMEOUT
    );

    /** Validates that every response-inspection limit is positive. */
    public PrivacyResponseInspectionLimits {
        if (maxStreamFrames <= 0) {
            throw new IllegalArgumentException("maxStreamFrames must be positive");
        }
        if (maxCharacters <= 0) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        if (maxMediaBytes <= 0) {
            throw new IllegalArgumentException("maxMediaBytes must be positive");
        }
        streamIdleTimeout = Objects.requireNonNull(
                streamIdleTimeout,
                "streamIdleTimeout must not be null"
        );
        if (streamIdleTimeout.isZero() || streamIdleTimeout.isNegative()) {
            throw new IllegalArgumentException("streamIdleTimeout must be positive");
        }
    }

    /**
     * Returns the shared default limits used by the Spring AI response boundaries.
     *
     * @return immutable default response-inspection limits
     */
    public static PrivacyResponseInspectionLimits defaults() {
        return DEFAULTS;
    }
}

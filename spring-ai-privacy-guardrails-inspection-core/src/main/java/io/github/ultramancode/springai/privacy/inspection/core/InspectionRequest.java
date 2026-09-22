package io.github.ultramancode.springai.privacy.inspection.core;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable content snapshot with a monotonic deadline. Do not retain beyond inspection. */
public final class InspectionRequest {

    private final List<ContentSegment> segments;
    private final InspectionLimits limits;
    private final long startedNanos = System.nanoTime();

    public InspectionRequest(List<ContentSegment> segments, InspectionLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty() || segments.size() > limits.maxSegments()) {
            throw new InspectionException(InspectionFailure.LIMIT_EXCEEDED);
        }
        this.segments = List.copyOf(segments);
        Set<String> ids = new HashSet<>();
        long characters = 0;
        for (ContentSegment segment : this.segments) {
            if (!ids.add(segment.id())) {
                throw new IllegalArgumentException("Segment IDs must be distinct");
            }
            characters += segment.text().length();
        }
        if (characters > limits.maxCharacters()) {
            throw new InspectionException(InspectionFailure.LIMIT_EXCEEDED);
        }
    }

    public List<ContentSegment> segments() {
        return segments;
    }

    public InspectionLimits limits() {
        return limits;
    }

    public Duration remaining() {
        checkInterrupted();
        long remaining = limits.timeout().toNanos() - (System.nanoTime() - startedNanos);
        if (remaining <= 0) {
            throw new InspectionException(InspectionFailure.TIMEOUT);
        }
        return Duration.ofNanos(remaining);
    }

    public void checkActive() {
        remaining();
    }

    public static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new InspectionException(InspectionFailure.CANCELLED);
        }
    }

    /**
     * Requires every segment to be marked {@link ContentSegment.Representation#PRIVACY_PROTECTED}.
     *
     * <p>Checks the supplied representation markers; it does not detect or transform personal data.
     *
     * @throws InspectionException with {@link InspectionFailure#DISCLOSURE_DENIED} if any segment
     *         is not marked as privacy-protected
     */
    public void requireProtected() {
        if (segments.stream()
                .anyMatch(
                        s ->
                                s.representation()
                                        != ContentSegment.Representation.PRIVACY_PROTECTED)) {
            throw new InspectionException(InspectionFailure.DISCLOSURE_DENIED);
        }
    }

    @Override
    public String toString() {
        return "InspectionRequest[segments=" + segments.size() + ", content=<redacted>]";
    }
}

package io.github.ultramancode.springai.privacy.inspection.core;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable inspection input with a time budget starting at construction.
 * Do not retain after inspection.
 */
public final class InspectionRequest {

    private final List<ContentSegment> segments;
    private final InspectionLimits limits;
    private final long startedNanos = System.nanoTime();

    public InspectionRequest(List<ContentSegment> segments, InspectionLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty() || segments.size() > limits.maxSegments()) {
            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
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
            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
        }
    }

    public List<ContentSegment> segments() {
        return segments;
    }

    public InspectionLimits limits() {
        return limits;
    }

    /** Returns the remaining time for further inspection work and response waits. */
    public Duration remaining() {
        checkInterrupted();
        long remaining = limits.timeout().toNanos() - (System.nanoTime() - startedNanos);
        if (remaining <= 0) {
            throw new InspectionException(InspectionFailureCode.TIMEOUT);
        }
        return Duration.ofNanos(remaining);
    }

    /** Checks whether further inspection work may start. */
    public void checkActive() {
        remaining();
    }

    public static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new InspectionException(InspectionFailureCode.CANCELLED);
        }
    }

    /**
     * Requires every segment to be marked {@link ContentSegment.PrivacyProcessingStatus#PROCESSED}.
     * Checks the supplied status without performing privacy processing.
     *
     * @throws InspectionException with {@link InspectionFailureCode#DISCLOSURE_DENIED} if any segment
     *         is not marked as privacy-processed
     */
    public void requirePrivacyProcessed() {
        for (ContentSegment segment : segments) {
            if (segment.privacyProcessingStatus()
                    != ContentSegment.PrivacyProcessingStatus.PROCESSED) {
                throw new InspectionException(InspectionFailureCode.DISCLOSURE_DENIED);
            }
        }
    }

    @Override
    public String toString() {
        return "InspectionRequest[segments=" + segments.size() + ", content=<redacted>]";
    }
}

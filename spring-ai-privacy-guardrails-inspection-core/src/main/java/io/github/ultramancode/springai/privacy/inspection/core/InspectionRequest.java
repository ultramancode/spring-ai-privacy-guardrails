package io.github.ultramancode.springai.privacy.inspection.core;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The text segments and limits for one inspection.
 * The request is immutable, and its timeout starts when it is constructed.
 * It contains the inspected text, so do not retain it after inspection.
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

    /** Returns the time remaining for inspection. Throws if the timeout expired or the thread was interrupted. */
    public Duration remaining() {
        checkInterrupted();
        long remaining = limits.timeout().toNanos() - (System.nanoTime() - startedNanos);
        if (remaining <= 0) {
            throw new InspectionException(InspectionFailureCode.TIMEOUT);
        }
        return Duration.ofNanos(remaining);
    }

    /** Throws if the timeout expired or the thread was interrupted, preventing further inspection work. */
    public void checkActive() {
        remaining();
    }

    public static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new InspectionException(InspectionFailureCode.CANCELLED);
        }
    }

    /**
     * Checks that every segment is marked {@link ContentSegment.PrivacyProcessingStatus#PROCESSED}.
     * This method checks the supplied status and does not perform privacy processing.
     *
     * @throws InspectionException with {@link InspectionFailureCode#PRIVACY_PROCESSING_REQUIRED} if any segment
     *         is not marked as privacy-processed
     */
    public void requirePrivacyProcessed() {
        for (ContentSegment segment : segments) {
            if (segment.privacyProcessingStatus()
                    != ContentSegment.PrivacyProcessingStatus.PROCESSED) {
                throw new InspectionException(InspectionFailureCode.PRIVACY_PROCESSING_REQUIRED);
            }
        }
    }

    @Override
    public String toString() {
        return "InspectionRequest[segments=" + segments.size() + ", content=<redacted>]";
    }
}

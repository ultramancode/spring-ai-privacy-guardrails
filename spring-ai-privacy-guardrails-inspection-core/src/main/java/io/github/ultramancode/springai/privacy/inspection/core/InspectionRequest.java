package io.github.ultramancode.springai.privacy.inspection.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The text segments, optional user message and limits for one inspection.
 * The request is immutable, and its timeout starts when it is constructed.
 * It contains the inspected text, so do not retain it after inspection.
 */
public final class InspectionRequest {

    private final List<ContentSegment> segments;
    private final ContentSegment userMessage;
    private final InspectionLimits limits;
    private final long startedNanos;

    public InspectionRequest(List<ContentSegment> segments, InspectionLimits limits) {
        this(segments, null, limits);
    }

    /**
     * The user message must have role USER and is not an inspection target.
     * Its text counts toward the character limit.
     *
     * @param segments text segments to inspect
     * @param userMessage user message providing context, or {@code null}
     * @param limits size and time limits for the inspection
     */
    public InspectionRequest(List<ContentSegment> segments, ContentSegment userMessage, InspectionLimits limits) {
        this(segments, userMessage, limits, System.nanoTime());
    }

    private InspectionRequest(List<ContentSegment> segments, ContentSegment userMessage,
            InspectionLimits limits, long startedNanos) {
        this.startedNanos = startedNanos;
        this.limits = Objects.requireNonNull(limits, "limits");
        if (userMessage != null && userMessage.role() != ContentSegment.Role.USER) {
            throw new IllegalArgumentException("User message must have role USER");
        }
        this.userMessage = userMessage;
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty() || segments.size() > limits.maxSegments()) {
            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
        }
        this.segments = List.copyOf(segments);
        Set<String> ids = new HashSet<>();
        long characters = userMessage == null ? 0 : userMessage.text().length();
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

    /** Optional user message for interpreting the inspected text. */
    public Optional<ContentSegment> userMessage() {
        return Optional.ofNullable(userMessage);
    }

    public InspectionLimits limits() {
        return limits;
    }

    InspectionRequest withSimplifiedPiiTokens(UnaryOperator<String> piiTokenSimplifier) {
        List<ContentSegment> simplifiedSegments = new ArrayList<>(segments.size());
        for (ContentSegment segment : segments) {
            checkActive();
            String simplifiedText = piiTokenSimplifier.apply(segment.text());
            Objects.requireNonNull(simplifiedText, "simplified text");
            simplifiedSegments.add(new ContentSegment(segment.id(), segment.role(), segment.privacyProcessingStatus(), simplifiedText));
        }
        ContentSegment simplifiedUserMessage = null;
        if (userMessage != null) {
            checkActive();
            String simplifiedText = Objects.requireNonNull(piiTokenSimplifier.apply(userMessage.text()), "simplified text");
            simplifiedUserMessage = new ContentSegment(userMessage.id(), userMessage.role(),
                    userMessage.privacyProcessingStatus(), simplifiedText);
        }
        checkActive();
        return new InspectionRequest(simplifiedSegments, simplifiedUserMessage, limits, startedNanos);
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
     * Checks that all text in this request is marked
     * {@link ContentSegment.PrivacyProcessingStatus#PROCESSED}.
     * This method checks the supplied status and does not perform privacy processing.
     *
     * @throws InspectionException with {@link InspectionFailureCode#PRIVACY_PROCESSING_REQUIRED} if any text
     *         is not marked as privacy-processed
     */
    public void requirePrivacyProcessed() {
        if (userMessage != null
                && userMessage.privacyProcessingStatus() != ContentSegment.PrivacyProcessingStatus.PROCESSED) {
            throw new InspectionException(InspectionFailureCode.PRIVACY_PROCESSING_REQUIRED);
        }
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

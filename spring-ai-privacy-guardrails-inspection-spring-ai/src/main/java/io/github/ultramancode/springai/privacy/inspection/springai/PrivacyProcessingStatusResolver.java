package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import org.springframework.ai.chat.client.ChatClientRequest;

/**
 * Reports the privacy processing status of text extracted from a model request.
 * This resolver does not perform privacy processing.
 *
 * <p>Determine the status from trusted application state, never from user-controlled metadata.
 * Return {@code PROCESSED} when configured privacy processing has completed for all extracted text,
 * {@code UNPROCESSED} when processing is known not to have been applied,
 * or {@code UNKNOWN} when the status cannot be confirmed.
 */
@FunctionalInterface
public interface PrivacyProcessingStatusResolver {

    /**
     * Returns one status that applies to all text extracted from the request.
     *
     * <p>During input inspection, a null result or an unclassified runtime exception
     * causes {@code INVALID_RESULT}. An interrupted thread causes {@code CANCELLED}
     * and preserves the interrupt flag. These failures stop the model call even with
     * {@code FAIL_OPEN} and are reported to {@link InspectionObserver#onFailure}.
     *
     * @param request the model request at the inspection boundary
     * @return the confirmed privacy processing status, or UNKNOWN when it cannot be established
     */
    ContentSegment.PrivacyProcessingStatus resolve(ChatClientRequest request);

    /** Returns a resolver that always reports UNKNOWN. */
    static PrivacyProcessingStatusResolver unknown() {
        return request -> ContentSegment.PrivacyProcessingStatus.UNKNOWN;
    }
}

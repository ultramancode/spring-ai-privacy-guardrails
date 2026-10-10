package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import org.springframework.ai.chat.client.ChatClientRequest;

/**
 * Supplies the privacy processing status for text extracted from a model request.
 * Determine this status from trusted application state, never from user-controlled metadata.
 * Return UNKNOWN if processing cannot be confirmed. A missing processing marker alone
 * does not establish that the text is UNPROCESSED.
 */
@FunctionalInterface
public interface PrivacyProcessingStatusResolver {

    /**
     * Returns one status that applies to all text extracted from the request.
     * A null result or an unclassified runtime exception causes INVALID_RESULT.
     * If the thread is interrupted, inspection stops with CANCELLED and preserves the interrupt flag.
     * These failures stop the model call even with FAIL_OPEN and are reported to
     * {@link InspectionObserver#onFailure}.
     *
     * @param request the model request at the inspection boundary
     * @return the confirmed privacy processing status, or UNKNOWN when it cannot be established
     */
    ContentSegment.PrivacyProcessingStatus resolve(ChatClientRequest request);

    /**
     * Creates a resolver that makes no claim about prior privacy processing.
     *
     * @return a resolver that always returns UNKNOWN
     */
    static PrivacyProcessingStatusResolver unknown() {
        return request -> ContentSegment.PrivacyProcessingStatus.UNKNOWN;
    }
}

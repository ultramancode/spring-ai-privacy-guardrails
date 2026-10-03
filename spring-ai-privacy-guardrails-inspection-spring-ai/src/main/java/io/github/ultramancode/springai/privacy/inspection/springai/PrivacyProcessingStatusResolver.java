package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import org.springframework.ai.chat.client.ChatClientRequest;

/**
 * Trusted application integration that supplies privacy processing status for all text extracted
 * from the current model request. Return UNKNOWN when processing cannot be established.
 * Absence of a processing marker does not establish UNPROCESSED status.
 * Never read processing claims from user-controlled metadata.
 * A null result or an unclassified runtime exception causes a sanitized INVALID_RESULT failure.
 * An interrupted thread causes CANCELLED and retains its interrupt flag. Failures prevent the
 * model call even with FAIL_OPEN and are delivered to InspectionObserver.onFailure.
 */
@FunctionalInterface
public interface PrivacyProcessingStatusResolver {

    ContentSegment.PrivacyProcessingStatus resolve(ChatClientRequest request);

    static PrivacyProcessingStatusResolver unknown() {
        return request -> ContentSegment.PrivacyProcessingStatus.UNKNOWN;
    }
}

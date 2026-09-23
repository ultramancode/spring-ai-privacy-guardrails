package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import org.springframework.ai.chat.client.ChatClientRequest;

/**
 * Trusted application integration that supplies privacy processing status for all text extracted
 * from the current model request. Return UNKNOWN when processing cannot be established;
 * absence of a processing marker does not establish UNPROCESSED status.
 * Never read processing claims from user-controlled metadata.
 */
@FunctionalInterface
public interface PrivacyProcessingStatusResolver {

    ContentSegment.PrivacyProcessingStatus resolve(ChatClientRequest request);

    static PrivacyProcessingStatusResolver unknown() {
        return request -> ContentSegment.PrivacyProcessingStatus.UNKNOWN;
    }
}

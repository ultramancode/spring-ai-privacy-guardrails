package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import org.springframework.ai.chat.client.ChatClientRequest;

/** Trusted application integration: never read protection claims from user-controlled metadata. */
@FunctionalInterface
public interface ContentRepresentationResolver {

    ContentSegment.Representation resolve(ChatClientRequest request);

    static ContentRepresentationResolver asReceived() {
        return request -> ContentSegment.Representation.AS_RECEIVED;
    }
}

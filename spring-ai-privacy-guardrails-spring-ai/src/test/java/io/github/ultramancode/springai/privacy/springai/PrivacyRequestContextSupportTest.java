package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyRequestContextSupportTest {

    @Test
    void cleanupRemovesEveryPrivacyEntryAndPreservesApplicationContext() {
        PrivacyService service = TestPrivacyServices.privacyService();
        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = new ChatClientRequest(
                    new Prompt("hello"),
                    Map.of("application", "kept")
            );
            request = PrivacyRequestContextSupport.attachLifecycle(request, session.handle());
            request = PrivacyOutputContextSupport.attachResponseInspectionLimits(
                    request,
                    new PrivacyResponseInspectionLimits(
                            16,
                            1_024,
                            1_024,
                            Duration.ofSeconds(1)
                    )
            );
            request = PrivacyToolExecutionContextSupport.attachValidatedToolCallbackSnapshot(request);
            request = PrivacyToolExecutionContextSupport.attachRegisteredToolNames(
                    request,
                    Set.of("lookup")
            );

            assertThat(request.context()).hasSize(6);
            assertThat(PrivacyRequestContextSupport.withoutInternalPrivacyContext(request.context()))
                    .containsExactly(Map.entry("application", "kept"));

            ChatClientResponse response = new ChatClientResponse(
                    TestPrivacyServices.response("ok").chatResponse(),
                    request.context()
            );
            ChatClientResponse cleanedResponse = PrivacyRequestContextSupport.withoutInternalPrivacyContext(response);

            assertThat(cleanedResponse.context()).containsExactly(Map.entry("application", "kept"));
            assertThat(cleanedResponse.chatResponse()).isSameAs(response.chatResponse());
        }
    }
}

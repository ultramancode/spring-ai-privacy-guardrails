package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivacyAdvisorChainValidatorTest {

    private final PrivacyService service = TestPrivacyServices.privacyService();
    private final PrivacyInputAdvisor inputAdvisor = new PrivacyInputAdvisor(service);
    private final PrivacyModelRequestStage privacyStage =
            new PrivacyModelRequestStage(service, null, PrivacyEnforcementObserver.noop());
    private final PrivacyAdvisorChainValidator validator =
            new PrivacyAdvisorChainValidator(List.of(inputAdvisor), privacyStage, 100);
    private final ChatClientRequest request = new ChatClientRequest(new Prompt("Hello"), Map.of());

    @Test
    void adviseCallRejectsMissingPrivacyStage() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.getCallAdvisors()).thenReturn(List.of(inputAdvisor));

        assertThatThrownBy(() -> validator.adviseCall(request, chain))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessage("Privacy advisor layout is missing the registered model stage");

        verify(chain, never()).nextCall(request);
    }

    @Test
    void adviseStreamRejectsMissingPrivacyStage() {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.getStreamAdvisors()).thenReturn(List.of(inputAdvisor));

        StepVerifier.create(validator.adviseStream(request, chain))
                .expectErrorMatches(error -> error instanceof PrivacyGuardrailException
                        && error.getMessage().equals("Privacy advisor layout is missing the registered model stage"))
                .verify();

        verify(chain, never()).nextStream(request);
    }
}

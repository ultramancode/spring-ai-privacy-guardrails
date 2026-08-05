package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivacyLifecycleAdvisorTest {

    @Test
    void customOrderIsApplicationOwned() {
        PrivacyService service = TestPrivacyServices.privacyService();

        assertThat(new PrivacyLifecycleAdvisor(service).getOrder())
                .isEqualTo(PrivacyLifecycleAdvisor.DEFAULT_ORDER);
        assertThat(new PrivacyLifecycleAdvisor(service, 123).getOrder()).isEqualTo(123);
    }

    @Test
    void adviseCallReappliesOutputPolicyAfterEveryInnerAdvisorAndCleansContext() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyLifecycleAdvisor lifecycle = new PrivacyLifecycleAdvisor(service);
        PrivacyOutputAdvisor output = new PrivacyOutputAdvisor(service);
        CallAdvisorChain chain = callChain(service, lifecycle, output);
        when(chain.nextCall(any())).thenAnswer(invocation -> {
            ChatClientRequest active = invocation.getArgument(0);
            return new ChatClientResponse(
                    TestPrivacyServices.response("Alice added after output policy").chatResponse(),
                    active.context()
            );
        });

        ChatClientResponse result = lifecycle.adviseCall(request(), chain);

        assertThat(result.chatResponse().getResult().getOutput().getText())
                .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern()
                        + " added after output policy")
                .doesNotContain("Alice");
        assertThat(result.context()).isEmpty();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void adviseStreamReappliesOutputPolicyToSplitPiiAddedAfterInnerOutputPolicy() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyLifecycleAdvisor lifecycle = new PrivacyLifecycleAdvisor(service);
        PrivacyOutputAdvisor output = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = streamChain(service, lifecycle, output);
        when(chain.nextStream(any())).thenAnswer(invocation -> {
            ChatClientRequest active = invocation.getArgument(0);
            return Flux.just(
                    new ChatClientResponse(TestPrivacyServices.response("Ali").chatResponse(), active.context()),
                    new ChatClientResponse(TestPrivacyServices.response("ce").chatResponse(), active.context())
            );
        });

        List<ChatClientResponse> results = lifecycle.adviseStream(request(), chain).collectList().block();

        assertThat(results).hasSize(2);
        String combined = results.stream()
                .map(result -> result.chatResponse().getResult().getOutput().getText())
                .filter(Objects::nonNull)
                .reduce("", String::concat);
        assertThat(combined)
                .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern())
                .doesNotContain("Alice");
        assertThat(results).allSatisfy(result -> assertThat(result.context()).isEmpty());
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void adviseStreamDoesNotTreatAFrequentlyEmittingLongStreamAsIdle() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyLifecycleAdvisor lifecycle = new PrivacyLifecycleAdvisor(service);
        PrivacyOutputAdvisor output = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                new PrivacyResponseInspectionLimits(20, 1000, 1000, Duration.ofMillis(200))
        );
        StreamAdvisorChain chain = streamChain(service, lifecycle, output);
        when(chain.nextStream(any())).thenAnswer(invocation -> {
            ChatClientRequest active = invocation.getArgument(0);
            return Flux.interval(Duration.ofMillis(30))
                    .take(10)
                    .map(index -> new ChatClientResponse(
                            TestPrivacyServices.response("safe-" + index).chatResponse(),
                            active.context()
                    ));
        });

        List<ChatClientResponse> results = lifecycle.adviseStream(request(), chain).collectList().block();

        assertThat(results).hasSize(10);
        assertThat(results).allSatisfy(result -> assertThat(result.context()).isEmpty());
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void adviseCallClosesSessionWhenDownstreamFails() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyLifecycleAdvisor lifecycle = new PrivacyLifecycleAdvisor(service);
        CallAdvisorChain chain = callChain(service, lifecycle, null);
        when(chain.nextCall(any())).thenThrow(new IllegalStateException("failed"));

        assertThatThrownBy(() -> lifecycle.adviseCall(request(), chain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void adviseStreamClosesSessionWhenSubscriberCancels() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyLifecycleAdvisor lifecycle = new PrivacyLifecycleAdvisor(service);
        StreamAdvisorChain chain = streamChain(service, lifecycle, null);
        when(chain.nextStream(any())).thenReturn(Flux.never());

        reactor.core.Disposable subscription = lifecycle.adviseStream(request(), chain).subscribe();
        assertThat(service.activeSessionCount()).isOne();

        subscription.dispose();

        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void allowsUnrelatedAdvisorAtTheSameDefaultOrder() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyLifecycleAdvisor lifecycle = new PrivacyLifecycleAdvisor(service);
        CallAdvisor sameOrder = mock(CallAdvisor.class);
        when(sameOrder.getOrder()).thenReturn(PrivacyLifecycleAdvisor.DEFAULT_ORDER);
        CallAdvisorChain chain = callChain(service, lifecycle, null);
        List<CallAdvisor> advisors = new ArrayList<>(chain.getCallAdvisors());
        advisors.add(sameOrder);
        when(chain.getCallAdvisors()).thenReturn(List.copyOf(advisors));
        when(chain.nextCall(any())).thenReturn(TestPrivacyServices.response("ok"));

        assertThat(lifecycle.adviseCall(request(), chain)).isNotNull();
        verify(chain).nextCall(any());
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void rejectsAnotherPrivacyLifecycleAdvisorBeforeOpeningSession() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyLifecycleAdvisor lifecycle = new PrivacyLifecycleAdvisor(service);
        CallAdvisorChain chain = callChain(service, lifecycle, null);
        List<CallAdvisor> advisors = new ArrayList<>(chain.getCallAdvisors());
        advisors.add(new PrivacyLifecycleAdvisor(service));
        when(chain.getCallAdvisors()).thenReturn(List.copyOf(advisors));

        assertThatThrownBy(() -> lifecycle.adviseCall(request(), chain))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("complete mandatory privacy advisor set");
        verify(chain, never()).nextCall(any());
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void rejectsAnIncompleteMandatoryAdvisorSet() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyLifecycleAdvisor lifecycle = new PrivacyLifecycleAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.getCallAdvisors()).thenReturn(List.of(lifecycle));

        assertThatThrownBy(() -> lifecycle.adviseCall(request(), chain))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("complete mandatory privacy advisor set");
        verify(chain, never()).nextCall(any());
    }

    private CallAdvisorChain callChain(
            PrivacyService service,
            PrivacyLifecycleAdvisor lifecycle,
            PrivacyOutputAdvisor output
    ) {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ArrayList<CallAdvisor> advisors = new ArrayList<>();
        advisors.add(lifecycle);
        advisors.add(new PrivacyInputAdvisor(service));
        if (output != null) {
            advisors.add(output);
        }
        advisors.add(new PrivacyToolContextAdvisor(service));
        advisors.add(new PrivacyToolCallValidationAdvisor(service));
        advisors.add(new PrivacyModelBoundaryAdvisor(service));
        when(chain.getCallAdvisors()).thenReturn(List.copyOf(advisors));
        return chain;
    }

    private StreamAdvisorChain streamChain(
            PrivacyService service,
            PrivacyLifecycleAdvisor lifecycle,
            PrivacyOutputAdvisor output
    ) {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ArrayList<StreamAdvisor> advisors = new ArrayList<>();
        advisors.add(lifecycle);
        advisors.add(new PrivacyInputAdvisor(service));
        if (output != null) {
            advisors.add(output);
        }
        advisors.add(new PrivacyToolContextAdvisor(service));
        advisors.add(new PrivacyToolCallValidationAdvisor(service));
        advisors.add(new PrivacyModelBoundaryAdvisor(service));
        when(chain.getStreamAdvisors()).thenReturn(List.copyOf(advisors));
        return chain;
    }

    private ChatClientRequest request() {
        return new ChatClientRequest(new Prompt("hello"), Map.of());
    }
}

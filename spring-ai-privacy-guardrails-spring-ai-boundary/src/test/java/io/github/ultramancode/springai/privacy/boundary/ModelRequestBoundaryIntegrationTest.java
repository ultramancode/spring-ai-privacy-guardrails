package io.github.ultramancode.springai.privacy.boundary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRequestBoundaryIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void phasesHaveFixedOrderRegardlessOfContributionOrder(boolean streaming) {
        List<String> stages = new ArrayList<>();
        RecordingModel model = new RecordingModel();
        ModelRequestBoundaryConfigurer inspection = (clientBuilder, boundarySpec) -> boundarySpec.inspection(request -> {
            stages.add("inspection");
            assertThat(request.prompt().getContents()).isEqualTo("authorized");
        });
        ModelRequestBoundaryConfigurer authorization = (clientBuilder, boundarySpec) -> boundarySpec.authorization(request -> {
            stages.add("authorization");
            assertThat(request.prompt().getContents()).isEqualTo("protected");
            return request.mutate().prompt(new Prompt("authorized")).build();
        });
        ModelRequestBoundaryConfigurer privacy = (clientBuilder, boundarySpec) -> boundarySpec.privacy(request -> {
            stages.add("privacy");
            return request.mutate().prompt(new Prompt("protected")).build();
        });
        ChatClient client = ModelRequestBoundaryConfigurer.compose(inspection, authorization, privacy)
                .configure(ChatClient.builder(model)).build();

        execute(client, streaming, "original");

        assertThat(stages).containsExactly("privacy", "authorization", "inspection");
        assertThat(model.contents).containsExactly("authorized");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateAdvisorRequiresClientScopedOptInAndCannotOverrideRejection(boolean streaming) {
        RecordingModel model = new RecordingModel();
        AtomicInteger inspections = new AtomicInteger();
        ModelRequestBoundaryConfigurer inspection = (clientBuilder, boundarySpec) -> boundarySpec.inspection(request -> {
            inspections.incrementAndGet();
            if (request.prompt().getContents().equals("blocked")) {
                throw new IllegalArgumentException("inspection blocked");
            }
        });
        ChatClient strict = inspection.configure(ChatClient.builder(model)).build();
        ChatClient allowed = inspection.allowAdvisorsAfterBoundary(true)
                .configure(ChatClient.builder(model)).build();

        assertThatThrownBy(() -> execute(strict, streaming, "hello", new LateAdvisor()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("explicit opt-in");
        assertThat(inspections).hasValue(0);
        execute(allowed, streaming, "hello", new LateAdvisor());
        assertThat(model.contents).containsExactly("changed after inspection");
        assertThatThrownBy(() -> execute(allowed, streaming, "blocked", new LateAdvisor()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inspection blocked");
        assertThat(model.contents).hasSize(1);
    }

    @Test
    void privacyWithoutInspectionContinuesToAllowLateAdvisors() {
        RecordingModel model = new RecordingModel();
        ModelRequestBoundaryConfigurer privacy = (clientBuilder, boundarySpec) -> boundarySpec.privacy(request -> request);
        ChatClient client = privacy.configure(ChatClient.builder(model)).build();

        execute(client, false, "hello", new LateAdvisor());

        assertThat(model.contents).containsExactly("changed after inspection");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clonesAndUnselectedClientsDoNotShareMutablePlans(boolean streaming) {
        RecordingModel model = new RecordingModel();
        AtomicInteger inspections = new AtomicInteger();
        ModelRequestBoundaryConfigurer inspection = (clientBuilder, boundarySpec) -> boundarySpec.inspection(request -> inspections.incrementAndGet());
        ChatClient.Builder plainBuilder = ChatClient.builder(model);
        ChatClient.Builder protectedBuilder = inspection.configure(plainBuilder.clone());
        ChatClient protectedClient = protectedBuilder.build();

        execute(plainBuilder.build(), streaming, "plain");
        assertThat(inspections).hasValue(0);
        execute(protectedBuilder.clone().build(), streaming, "cloned");
        execute(protectedClient.mutate().build(), streaming, "mutated");
        assertThat(inspections).hasValue(2);
        assertThat(model.contents).containsExactly("plain", "cloned", "mutated");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void optInDoesNotPermitDuplicateBoundariesOrToolsAfterInspection(boolean streaming) {
        RecordingModel model = new RecordingModel();
        ModelRequestBoundaryConfigurer inspection = (clientBuilder, boundarySpec) -> boundarySpec.inspection(request -> { });
        ModelRequestBoundaryConfigurer allowed = inspection.allowAdvisorsAfterBoundary(true);
        ChatClient.Builder duplicated = allowed.configure(ChatClient.builder(model));
        allowed.configure(duplicated);
        assertThatThrownBy(() -> execute(duplicated.build(), streaming, "hello"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Exactly one");

        ChatClient lateTools = allowed.configure(ChatClient.builder(model)
                .defaultAdvisors(ToolCallingAdvisor.builder()
                        .advisorOrder(ModelRequestBoundaryAdvisor.DEFAULT_ORDER + 1).build())).build();
        assertThatThrownBy(() -> execute(lateTools, streaming, "hello"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Tool advisors must run before");
        assertThat(model.contents).isEmpty();
    }

    @Test
    void duplicateStageContributionsAreRejected() {
        ModelRequestBoundaryConfigurer inspection = (clientBuilder, boundarySpec) -> boundarySpec.inspection(request -> { });
        assertThatThrownBy(() -> ModelRequestBoundaryConfigurer.compose(inspection, inspection)
                .configure(ChatClient.builder(new RecordingModel())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Inspection stage already configured");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void providerFailuresAndIncrementalFramesPassThroughUnchanged(boolean inspectionEnabled) {
        IllegalStateException providerFailure = new IllegalStateException("provider failure");
        ChatResponse frame = new ChatResponse(List.of(new Generation(new AssistantMessage("first"))));
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw providerFailure;
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.concat(Flux.just(frame), Flux.error(providerFailure));
            }
        };
        ModelRequestBoundaryConfigurer configurer = (clientBuilder, boundarySpec) -> {
            if (inspectionEnabled) {
                boundarySpec.inspection(request -> { });
            } else {
                boundarySpec.privacy(request -> request);
            }
        };
        ChatClient client = configurer.configure(ChatClient.builder(model)).build();
        assertThatThrownBy(() -> client.prompt().user("hello").call().content())
                .isSameAs(providerFailure);
        StepVerifier.create(client.prompt().user("hello").stream().chatResponse())
                .expectNext(frame)
                .expectErrorMatches(failure -> failure == providerFailure)
                .verify(Duration.ofSeconds(5));
    }

    private static void execute(ChatClient client, boolean streaming, String input, LateAdvisor... advisors) {
        ChatClient.ChatClientRequestSpec request = client.prompt().user(input).advisors(advisors);
        if (streaming) {
            request.stream().content().collectList().block(Duration.ofSeconds(5));
        } else {
            request.call().content();
        }
    }

    private static final class RecordingModel implements ChatModel {
        private final List<String> contents = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            contents.add(prompt.getContents());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }
    }

    private static final class LateAdvisor implements CallAdvisor, StreamAdvisor {
        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            return chain.nextCall(request.mutate().prompt(new Prompt("changed after inspection")).build());
        }

        @Override
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
            return chain.nextStream(request.mutate().prompt(new Prompt("changed after inspection")).build());
        }

        @Override
        public int getOrder() {
            return Integer.MAX_VALUE - 1;
        }

        @Override
        public String getName() {
            return "late-test-advisor";
        }
    }
}

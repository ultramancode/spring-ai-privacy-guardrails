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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRequestBoundaryIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stagesExecuteInFixedOrderRegardlessOfRegistrationOrder(boolean streaming) {
        List<String> executedStages = new ArrayList<>();
        RecordingModel model = new RecordingModel();
        Consumer<ChatClientRequest> inspectionStage = request -> {
            executedStages.add("inspection");
            assertThat(request.prompt().getContents()).isEqualTo("authorized");
        };
        UnaryOperator<ChatClientRequest> authorizationStage = request -> {
            executedStages.add("authorization");
            assertThat(request.prompt().getContents()).isEqualTo("protected");
            return request.mutate().prompt(new Prompt("authorized")).build();
        };
        UnaryOperator<ChatClientRequest> privacyStage = request -> {
            executedStages.add("privacy");
            assertThat(request.prompt().getContents()).isEqualTo("original");
            return request.mutate().prompt(new Prompt("protected")).build();
        };

        ModelRequestBoundaryConfigurer inspectionConfigurer =
                (clientBuilder, boundarySpec) -> boundarySpec.inspection(inspectionStage);
        ModelRequestBoundaryConfigurer authorizationConfigurer =
                (clientBuilder, boundarySpec) -> boundarySpec.authorization(authorizationStage);
        ModelRequestBoundaryConfigurer privacyConfigurer =
                (clientBuilder, boundarySpec) -> boundarySpec.privacy(privacyStage);
        ModelRequestBoundaryConfigurer combinedConfigurer = ModelRequestBoundaryConfigurer.compose(
                inspectionConfigurer, authorizationConfigurer, privacyConfigurer);
        ChatClient client = combinedConfigurer.configure(ChatClient.builder(model)).build();

        execute(client, streaming, "original");

        assertThat(executedStages).containsExactly("privacy", "authorization", "inspection");
        assertThat(model.receivedContents).containsExactly("authorized");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inspectionRunsBeforeEachModelCallAndIncludesToolResults(boolean streaming) {
        List<String> executionOrder = new CopyOnWriteArrayList<>();
        List<List<Message>> inspectedMessages = new CopyOnWriteArrayList<>();
        String toolResult = "lookup completed";
        ToolCallback lookupTool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("Returns a test result")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String input) {
                executionOrder.add("tool");
                return toolResult;
            }
        };
        ChatModel model = new ChatModel() {
            private final AtomicInteger callCount = new AtomicInteger();

            @Override
            public ChatResponse call(Prompt prompt) {
                executionOrder.add("model");
                AssistantMessage response = new AssistantMessage("done");
                if (callCount.incrementAndGet() == 1) {
                    AssistantMessage.ToolCall toolCall =
                            new AssistantMessage.ToolCall("lookup-call", "function", "lookup", "{}");
                    response = AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
                }
                return new ChatResponse(List.of(new Generation(response)));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> Flux.just(call(prompt)));
            }

            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        Consumer<ChatClientRequest> inspectionStage = request -> {
            executionOrder.add("inspection");
            inspectedMessages.add(List.copyOf(request.prompt().getInstructions()));
        };
        ModelRequestBoundaryConfigurer inspectionConfigurer =
                (clientBuilder, boundarySpec) -> boundarySpec.inspection(inspectionStage);
        ChatClient client = inspectionConfigurer.configure(ChatClient.builder(model))
                .defaultTools(lookupTool).build();

        execute(client, streaming, "Run lookup");

        assertThat(executionOrder).containsExactly("inspection", "model", "tool", "inspection", "model");
        assertThat(inspectedMessages).hasSize(2);
        assertThat(inspectedMessages.get(0)).noneMatch(ToolResponseMessage.class::isInstance);
        List<String> inspectedToolResults = inspectedMessages.get(1).stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData)
                .toList();
        assertThat(inspectedToolResults).containsExactly(toolResult);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateAdvisorRequiresClientScopedOptIn(boolean streaming) {
        RecordingModel model = new RecordingModel();
        AtomicInteger inspectionCount = new AtomicInteger();
        ModelRequestBoundaryConfigurer inspectionConfigurer = (clientBuilder, boundarySpec) -> {
            boundarySpec.inspection(request -> inspectionCount.incrementAndGet());
        };
        ChatClient strictClient = inspectionConfigurer.configure(ChatClient.builder(model)).build();
        ChatClient optedInClient = inspectionConfigurer.allowAdvisorsAfterBoundary(true)
                .configure(ChatClient.builder(model)).build();

        assertThatThrownBy(() -> execute(strictClient, streaming, "hello", new LateAdvisor("changed by late advisor")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit opt-in");
        assertThat(inspectionCount).hasValue(0);
        assertThat(model.receivedContents).isEmpty();

        execute(optedInClient, streaming, "hello", new LateAdvisor("changed by late advisor"));

        assertThat(inspectionCount).hasValue(1);
        assertThat(model.receivedContents).containsExactly("changed by late advisor");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateAdvisorCannotBypassInspectionRejectionEvenWithOptIn(boolean streaming) {
        RecordingModel model = new RecordingModel();
        LateAdvisor lateAdvisor = new LateAdvisor("changed by late advisor");
        Consumer<ChatClientRequest> inspectionStage = request -> {
            if (request.prompt().getContents().equals("blocked")) {
                throw new IllegalArgumentException("inspection blocked");
            }
        };

        ModelRequestBoundaryConfigurer inspectionConfigurer =
                (clientBuilder, boundarySpec) -> boundarySpec.inspection(inspectionStage);
        ChatClient client = inspectionConfigurer.allowAdvisorsAfterBoundary(true)
                .configure(ChatClient.builder(model)).build();

        assertThatThrownBy(() -> execute(client, streaming, "blocked", lateAdvisor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inspection blocked");
        assertThat(lateAdvisor.executionCount).hasValue(0);
        assertThat(model.receivedContents).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void privacyWithoutInspectionAllowsLateAdvisors(boolean streaming) {
        RecordingModel model = new RecordingModel();
        ModelRequestBoundaryConfigurer privacyConfigurer = (clientBuilder, boundarySpec) -> {
            boundarySpec.privacy(request -> request);
        };
        ChatClient client = privacyConfigurer.configure(ChatClient.builder(model)).build();

        execute(client, streaming, "hello", new LateAdvisor("changed by late advisor"));

        assertThat(model.receivedContents).containsExactly("changed by late advisor");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inspectionAppliesOnlyToConfiguredClientsAndTheirCopies(boolean streaming) {
        RecordingModel model = new RecordingModel();
        AtomicInteger inspectionCount = new AtomicInteger();
        ModelRequestBoundaryConfigurer inspectionConfigurer = (clientBuilder, boundarySpec) -> {
            boundarySpec.inspection(request -> inspectionCount.incrementAndGet());
        };
        ChatClient.Builder plainBuilder = ChatClient.builder(model);
        ChatClient.Builder inspectedBuilder = inspectionConfigurer.configure(plainBuilder.clone());
        ChatClient inspectedClient = inspectedBuilder.build();

        execute(plainBuilder.build(), streaming, "plain");
        assertThat(inspectionCount).hasValue(0);

        execute(inspectedBuilder.clone().build(), streaming, "cloned");
        assertThat(inspectionCount).hasValue(1);

        execute(inspectedClient.mutate().build(), streaming, "mutated");
        assertThat(inspectionCount).hasValue(2);
        assertThat(model.receivedContents).containsExactly("plain", "cloned", "mutated");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateBoundariesAreRejectedEvenWithOptIn(boolean streaming) {
        RecordingModel model = new RecordingModel();
        ModelRequestBoundaryConfigurer inspectionConfigurer = (clientBuilder, boundarySpec) -> {
            boundarySpec.inspection(request -> { });
        };
        ModelRequestBoundaryConfigurer optedInConfigurer = inspectionConfigurer.allowAdvisorsAfterBoundary(true);
        ChatClient.Builder clientBuilder = optedInConfigurer.configure(ChatClient.builder(model));
        optedInConfigurer.configure(clientBuilder);
        ChatClient client = clientBuilder.build();

        assertThatThrownBy(() -> execute(client, streaming, "hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one");
        assertThat(model.receivedContents).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void toolAdvisorsAfterBoundaryAreRejectedEvenWithOptIn(boolean streaming) {
        RecordingModel model = new RecordingModel();
        ModelRequestBoundaryConfigurer inspectionConfigurer = (clientBuilder, boundarySpec) -> {
            boundarySpec.inspection(request -> { });
        };
        ToolCallingAdvisor lateToolAdvisor = ToolCallingAdvisor.builder()
                .advisorOrder(ModelRequestBoundaryAdvisor.DEFAULT_ORDER + 1)
                .build();
        ChatClient.Builder clientBuilder = ChatClient.builder(model).defaultAdvisors(lateToolAdvisor);
        ChatClient client = inspectionConfigurer.allowAdvisorsAfterBoundary(true).configure(clientBuilder).build();

        assertThatThrownBy(() -> execute(client, streaming, "hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tool advisors must run before");
        assertThat(model.receivedContents).isEmpty();
    }

    @Test
    void duplicateInspectionRegistrationsAreRejected() {
        ModelRequestBoundaryConfigurer inspectionConfigurer = (clientBuilder, boundarySpec) -> {
            boundarySpec.inspection(request -> { });
        };
        ModelRequestBoundaryConfigurer combinedConfigurer = ModelRequestBoundaryConfigurer.compose(
                inspectionConfigurer, inspectionConfigurer);
        ChatClient.Builder clientBuilder = ChatClient.builder(new RecordingModel());

        assertThatThrownBy(() -> combinedConfigurer.configure(clientBuilder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inspection stage already configured");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modelFailuresAndPartialStreamResponsesArePreserved(boolean inspectionEnabled) {
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
        private final List<String> receivedContents = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            receivedContents.add(prompt.getContents());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }
    }

    private static final class LateAdvisor implements CallAdvisor, StreamAdvisor {
        private final String replacementContent;
        private final AtomicInteger executionCount = new AtomicInteger();

        private LateAdvisor(String replacementContent) {
            this.replacementContent = replacementContent;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            executionCount.incrementAndGet();
            return chain.nextCall(request.mutate().prompt(new Prompt(replacementContent)).build());
        }

        @Override
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
            executionCount.incrementAndGet();
            return chain.nextStream(request.mutate().prompt(new Prompt(replacementContent)).build());
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

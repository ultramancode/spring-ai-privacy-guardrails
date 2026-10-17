package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.onnx.OnnxClassificationConfig;
import io.github.ultramancode.springai.privacy.inspection.onnx.OnnxContentInspector;
import io.github.ultramancode.springai.privacy.inspection.onnx.OnnxInspectionConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding.Category.TOXICITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxInspectionIntegrationTest {

    private static final String SAFE = "Thank you for your help. ";
    private static final String INSULT = "You are an idiot. ";
    private static final Path MODEL_DIRECTORY = Path.of(System.getProperty("inspection.test.model"));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onnxInputInspectionBlocksTheTailBeforeCallingTheModel(boolean streaming) {
        try (OnnxContentInspector onnx = localInspector()) {
            InspectionChatClientConfigurer inspection = new InspectionChatClientConfigurer(
                    new InspectionService(List.of(onnx)));
            AtomicInteger modelCalls = new AtomicInteger();
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    modelCalls.incrementAndGet();
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.defer(() -> Flux.just(call(prompt)));
                }
            };
            ChatClient client = inspection.configure(ChatClient.builder(model)).build();

            invoke(client, SAFE, streaming);
            assertThat(modelCalls).hasValue(1);

            assertThatThrownBy(() -> invoke(client, SAFE.repeat(40) + INSULT.repeat(20), streaming))
                    .isInstanceOf(InspectionBlockedException.class);
            assertThat(modelCalls).hasValue(1);
        }
    }

    private OnnxContentInspector localInspector() {
        return new OnnxContentInspector("local",
                OnnxInspectionConfig.defaults(MODEL_DIRECTORY.resolve("model.onnx")),
                OnnxClassificationConfig.builder()
                        .tokenizer(MODEL_DIRECTORY.resolve("tokenizer.json"))
                        .tokenizerConfig(MODEL_DIRECTORY.resolve("tokenizer_config.json"))
                        .windowSize(128)
                        .overlapSize(32)
                        .logitCount(6)
                        .activation(OnnxClassificationConfig.Activation.SIGMOID)
                        .labels(List.of(new OnnxClassificationConfig.Label(4, TOXICITY, "INSULT", 0.8)))
                        .build());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onnxOutputInspectionBlocksTheTailBeforeReleasingAnyResponse(boolean streaming) {
        try (OnnxContentInspector onnx = localInspector()) {
            InspectionService service = new InspectionService(List.of(onnx));
            InspectionChatClientConfigurer inspection = new InspectionChatClientConfigurer(service)
                    .withOutputInspection(new InspectionOutputAdvisor(service));
            String prefix = SAFE.repeat(40);
            AtomicReference<String> tail = new AtomicReference<>("world");
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(prefix + tail.get()))));
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.defer(() -> Flux.just(
                            new ChatResponse(List.of(new Generation(new AssistantMessage(prefix)))),
                            new ChatResponse(List.of(new Generation(new AssistantMessage(tail.get()))))));
                }
            };
            ChatClient client = inspection.configure(ChatClient.builder(model)).build();
            List<String> delivered = new ArrayList<>();
            Runnable invoke = () -> {
                if (streaming) {
                    client.prompt().user(SAFE).stream().content().doOnNext(delivered::add)
                            .blockLast(Duration.ofSeconds(10));
                } else {
                    delivered.add(client.prompt().user(SAFE).call().content());
                }
            };

            invoke.run();
            assertThat(String.join("", delivered)).isEqualTo(prefix + "world");
            delivered.clear();
            tail.set(INSULT.repeat(20));

            assertThatThrownBy(invoke::run).isInstanceOf(InspectionBlockedException.class);
            assertThat(delivered).isEmpty();
        }
    }

    private void invoke(ChatClient client, String text, boolean streaming) {
        if (streaming) {
            client.prompt().user(text).stream().content().collectList().block(Duration.ofSeconds(10));
        } else {
            client.prompt().user(text).call().content();
        }
    }
}

package example;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionBlockedException;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionChatClientConfigurer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootConfiguration
@EnableAutoConfiguration
public class InspectionOnlyPublishedArtifactConsumer {

    @Bean
    ContentInspector applicationRules() {
        InspectionRule rule = InspectionRule.literal(
                "attack", InspectionFinding.Category.PROMPT_INJECTION, "attack");
        return new RuleBasedContentInspector("application-rules", List.of(rule));
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                InspectionOnlyPublishedArtifactConsumer.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "logging.level.root=ERROR",
                        "spring.ai.inspection.enabled=true",
                        "spring.ai.inspection.output.enabled=true")
                .run(args)) {
            InspectionChatClientConfigurer configurer = context.getBean(InspectionChatClientConfigurer.class);
            verifyInspection(configurer, false);
            verifyInspection(configurer, true);
        }
        System.out.println("Inspection-only published artifact runtime smoke passed");
    }

    private static void verifyInspection(InspectionChatClientConfigurer configurer, boolean streaming) {
        PublishedInspectionModel model = new PublishedInspectionModel();
        ChatClient client = configurer.configure(ChatClient.builder(model)).build();
        String allowed = invoke(client, "hello", streaming, new AtomicInteger());
        if (!"answer".equals(allowed) || model.calls.get() != 1) {
            throw new IllegalStateException("Allowed inspection did not reach the model");
        }

        expectBlocked(client, "attack", streaming);
        if (model.calls.get() != 1) {
            throw new IllegalStateException("Blocked input reached the model");
        }

        expectBlocked(client, "unsafe output", streaming);
        if (model.calls.get() != 2) {
            throw new IllegalStateException("Output inspection did not run after the model call");
        }
    }

    private static void expectBlocked(ChatClient client, String input, boolean streaming) {
        AtomicInteger emittedFrames = new AtomicInteger();
        try {
            invoke(client, input, streaming, emittedFrames);
            throw new IllegalStateException("Inspection allowed blocked content");
        } catch (InspectionBlockedException expected) {
            if (emittedFrames.get() != 0) {
                throw new IllegalStateException("Inspection released content before blocking");
            }
        }
    }

    private static String invoke(ChatClient client, String input, boolean streaming, AtomicInteger emittedFrames) {
        if (streaming) {
            List<String> frames = client.prompt().user(input).stream().content()
                    .doOnNext(frame -> emittedFrames.incrementAndGet())
                    .collectList().block(Duration.ofSeconds(5));
            return String.join("", frames);
        }
        return client.prompt().user(input).call().content();
    }

    private static final class PublishedInspectionModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            String output = prompt.getUserMessage().getText().equals("unsafe output") ? "attack" : "answer";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }
    }
}

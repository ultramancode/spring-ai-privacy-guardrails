package example;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionDecision;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionBlockedException;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionChatClientConfigurer;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionOutputAdvisor;
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
                "logging.level.root=ERROR")
            .run(args)) {
            InspectionOutputAdvisor inspectionOutputAdvisor = new InspectionOutputAdvisor(
                context.getBean(InspectionService.class));
            InspectionChatClientConfigurer inspectionConfigurer = context.getBean(InspectionChatClientConfigurer.class)
                .withOutputInspection(inspectionOutputAdvisor);
            verifyInspection(inspectionConfigurer, false);
            verifyInspection(inspectionConfigurer, true);
        }
        verifyStandaloneInspection();
        System.out.println("Inspection-only published artifact runtime smoke passed");
    }

    private static void verifyStandaloneInspection() {
        InspectionRule rule = InspectionRule.literal("blocked-phrase", InspectionFinding.Category.PROMPT_INJECTION,
            "ignore previous instructions");
        InspectionService inspectionService = new InspectionService(
            List.of(new RuleBasedContentInspector("application-rules", List.of(rule))));
        InspectionRequest inspectionRequest = new InspectionRequest(List.of(new ContentSegment("input", ContentSegment.Role.USER,
            ContentSegment.PrivacyProcessingStatus.UNPROCESSED, "ignore previous instructions")), InspectionLimits.defaults());
        if (inspectionService.inspect(inspectionRequest).decision() != InspectionDecision.BLOCK) {
            throw new IllegalStateException("Standalone inspection did not block the configured phrase");
        }
    }

    private static void verifyInspection(InspectionChatClientConfigurer inspectionConfigurer, boolean streaming) {
        PublishedInspectionModel chatModel = new PublishedInspectionModel();
        ChatClient chatClient = inspectionConfigurer.configure(ChatClient.builder(chatModel)).build();
        String allowed = invoke(chatClient, "hello", streaming, new AtomicInteger());
        if (!"answer".equals(allowed) || chatModel.calls.get() != 1) {
            throw new IllegalStateException("Allowed inspection did not reach the model");
        }

        expectBlocked(chatClient, "attack", streaming);
        if (chatModel.calls.get() != 1) {
            throw new IllegalStateException("Blocked input reached the model");
        }

        expectBlocked(chatClient, "unsafe output", streaming);
        if (chatModel.calls.get() != 2) {
            throw new IllegalStateException("Output inspection did not run after the model call");
        }
    }

    private static void expectBlocked(ChatClient chatClient, String input, boolean streaming) {
        AtomicInteger emittedFrames = new AtomicInteger();
        try {
            invoke(chatClient, input, streaming, emittedFrames);
            throw new IllegalStateException("Inspection allowed blocked content");
        } catch (InspectionBlockedException expected) {
            if (emittedFrames.get() != 0) {
                throw new IllegalStateException("Inspection released content before blocking");
            }
        }
    }

    private static String invoke(ChatClient chatClient, String input, boolean streaming, AtomicInteger emittedFrames) {
        if (streaming) {
            List<String> frames = chatClient.prompt().user(input).stream().content()
                .doOnNext(frame -> emittedFrames.incrementAndGet())
                .collectList().block(Duration.ofSeconds(5));
            return String.join("", frames);
        }
        return chatClient.prompt().user(input).call().content();
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

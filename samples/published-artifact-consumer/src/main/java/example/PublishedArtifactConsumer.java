package example;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.RegexPiiAnalyzer;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.test.PrivacyTestProbe;
import io.github.ultramancode.springai.privacy.test.ToolCallSnapshot;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;

@SpringBootConfiguration
@EnableAutoConfiguration
public class PublishedArtifactConsumer {

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                PublishedArtifactConsumer.class
        )
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "logging.level.root=ERROR",
                        "spring.ai.privacy.enabled=true",
                        "spring.ai.privacy.regex.enabled=true",
                        "spring.ai.privacy.regex.rules[0].entity-type=EMAIL_ADDRESS",
                        "spring.ai.privacy.regex.rules[0].pattern="
                                + "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
                        "spring.ai.privacy.regex.rules[0].score=0.95",
                        "spring.ai.privacy.regex.rules[1].entity-type=CUSTOMER_ID",
                        "spring.ai.privacy.regex.rules[1].pattern=\\bCUST-\\d{4}\\b",
                        "spring.ai.privacy.regex.rules[1].score=0.99",
                        "spring.ai.privacy.tools.disclosures[customerLookup][0]=CUSTOMER_ID",
                        "spring.ai.privacy.output.enabled=true"
                )
                .run(args)) {
            verifyRuntime(context);
        }
    }

    private static void verifyRuntime(ConfigurableApplicationContext context) {
        context.getBean(PrivacyGuardrailsAutoConfiguration.class);
        context.getBean(RegexPiiAnalyzer.class);
        context.getBean(PrivacyChatClientConfigurer.class);
        PrivacyService privacyService = context.getBean(PrivacyService.class);

        String tokenized;
        try (PrivacySession session = privacyService.openSession()) {
            tokenized = privacyService.tokenize(session.handle(), "alice@example.com");
            if (!OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS").matcher(tokenized).find()) {
                throw new IllegalStateException("Published starter did not tokenize the synthetic email");
            }
        }
        if (privacyService.activeSessionCount() != 0) {
            throw new IllegalStateException("Published starter retained a privacy session after close");
        }
        verifyToolBoundary(context, privacyService);
        System.out.println("Published artifact runtime smoke passed");
    }

    private static void verifyToolBoundary(
            ConfigurableApplicationContext context,
            PrivacyService privacyService
    ) {
        PrivacyToolCallbackFactory toolCallbackFactory = context.getBean(PrivacyToolCallbackFactory.class);
        try (PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService)) {
            ToolCallback protectedTool = probe.wrapTool(customerLookup(), toolCallbackFactory);
            ChatModel model = probe.wrapModel(new PublishedToolLoopModel());
            ChatClient.Builder builder = ChatClient.builder(model).defaultTools(protectedTool);
            context.getBean(PrivacyChatClientConfigurer.class).configure(builder);
            String finalResponse = builder.build()
                    .prompt()
                    .user("Find CUST-0042 for alice@example.com")
                    .call()
                    .content();

            if (probe.modelRequests().size() != 2 || probe.toolCalls().size() != 1) {
                throw new IllegalStateException("Published configurer did not execute one complete tool loop");
            }
            ToolCallSnapshot call = probe.toolCalls().get(0);
            if (!call.input().contains("CUST-0042") || call.input().contains("alice@example.com")) {
                throw new IllegalStateException("Published tool wrapper violated scoped disclosure");
            }
            boolean modelSawRaw = probe.modelRequests().stream()
                    .flatMap(request -> request.modelVisibleContent().stream())
                    .anyMatch(content -> content.contains("CUST-0042")
                            || content.contains("alice@example.com")
                            || content.contains("CUST-9000")
                            || content.contains("result@example.com"));
            if (modelSawRaw) {
                throw new IllegalStateException("Published configurer exposed raw PII to the model");
            }
            if (finalResponse.contains("final@example.com")
                    || !OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS").matcher(finalResponse).find()) {
                throw new IllegalStateException("Published output policy did not protect the final response");
            }
        }
        if (privacyService.activeSessionCount() != 0) {
            throw new IllegalStateException("Published tool smoke retained a privacy session after close");
        }
    }

    private static ToolCallback customerLookup() {
        return FunctionToolCallback.builder(
                        "customerLookup",
                        (CustomerLookupRequest ignored) -> "processed CUST-9000 for result@example.com"
                )
                .description("Returns one synthetic customer")
                .inputType(CustomerLookupRequest.class)
                .build();
    }

    private record CustomerLookupRequest(String customerId, String email) {
    }

    private static final class PublishedToolLoopModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            if (this.calls.incrementAndGet() == 1) {
                String modelInput = prompt.getInstructions().stream()
                        .map(Message::getText)
                        .filter(Objects::nonNull)
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
                String customerToken = token(modelInput, "CUSTOMER_ID");
                String emailToken = token(modelInput, "EMAIL_ADDRESS");
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "customerLookup",
                                "{\"customerId\":\"" + customerToken
                                        + "\",\"email\":\"" + emailToken + "\"}"
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("Contact final@example.com")
            )));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private static String token(String text, String entityType) {
            Matcher matcher = OpaquePiiTokenFormat.patternForEntityType(entityType).matcher(text);
            if (!matcher.find()) {
                throw new IllegalStateException("Expected protected " + entityType + " token");
            }
            return matcher.group();
        }
    }
}

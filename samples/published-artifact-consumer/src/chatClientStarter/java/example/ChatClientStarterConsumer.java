package example;

import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import org.springframework.context.annotation.Bean;
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
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

@SpringBootConfiguration
@EnableAutoConfiguration
public class ChatClientStarterConsumer {
    @Bean
    PiiRule employeeIdRule() {
        PatternDefinition employeeIdPattern = PatternDefinition.builder()
                .expression("EMP-[0-9]{4}")
                .score(1.0)
                .build();
        return PatternPiiRule.builder()
                .id("employee-id")
                .entityType("EMPLOYEE_ID")
                .addPattern(employeeIdPattern)
                .build();
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ChatClientStarterConsumer.class)
            .web(WebApplicationType.NONE)
            .properties("spring.main.banner-mode=off", "logging.level.root=ERROR",
                "spring.ai.privacy.rules.enabled=true",
                "spring.ai.privacy.rules.include=employee-id")
            .run(args)) {
            GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder().privacy().build();
            ChatClient chatClient = context.getBean(GuardrailsChatClientFactory.class)
                .builder(new EchoModel(), guardrailsPolicy).build();
            String input = "EMP-1234";
            String blockingResponse = chatClient.prompt().user(input).call().content();
            String streamingResponse = chatClient.prompt().user(input).stream().content().collectList()
                .map(parts -> String.join("", parts)).block(Duration.ofSeconds(5));
            if (!OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID").matcher(blockingResponse).matches()
                || !OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID").matcher(streamingResponse).matches()
                || context.getBean(PrivacyService.class).activeSessionCount() != 0) {
                throw new IllegalStateException("ChatClient starter did not protect the quick-start input");
            }
        }
        System.out.println("ChatClient starter smoke passed");
    }

    private static final class EchoModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(prompt.getUserMessage().getText()))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }
    }
}

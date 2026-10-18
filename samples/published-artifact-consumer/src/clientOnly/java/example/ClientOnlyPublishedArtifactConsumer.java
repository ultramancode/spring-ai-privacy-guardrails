package example;

import io.github.ultramancode.springai.privacy.client.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.client.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionDecision;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
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
public class ClientOnlyPublishedArtifactConsumer {
    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ClientOnlyPublishedArtifactConsumer.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=ERROR",
                        "spring.ai.privacy.regex.enabled=true",
                        "spring.ai.privacy.regex.rules[0].entity-type=EMPLOYEE_ID",
                        "spring.ai.privacy.regex.rules[0].pattern=EMP-[0-9]{4}",
                        "spring.ai.privacy.regex.rules[0].score=1.0")
                .run(args)) {
            GuardrailsPolicy policy = GuardrailsPolicy.builder().privacy().build();
            ChatClient client = context.getBean(GuardrailsChatClientFactory.class)
                    .builder(new EchoModel(), policy).build();
            String input = "EMP-1234";
            String blocking = client.prompt().user(input).call().content();
            String streaming = client.prompt().user(input).stream().content().collectList()
                    .map(parts -> String.join("", parts)).block(Duration.ofSeconds(5));
            if (!OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID").matcher(blocking).matches()
                    || !OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID").matcher(streaming).matches()
                    || context.getBean(PrivacyService.class).activeSessionCount() != 0) {
                throw new IllegalStateException("Client starter did not protect the quick-start input");
            }
        }
        verifyStandaloneInspection();
        System.out.println("Client starter and standalone inspection smoke passed");
    }

    private static void verifyStandaloneInspection() {
        InspectionRule rule = InspectionRule.literal("blocked-phrase", InspectionFinding.Category.PROMPT_INJECTION,
                "ignore previous instructions");
        InspectionService service = new InspectionService(
                List.of(new RuleBasedContentInspector("application-rules", List.of(rule))));
        InspectionRequest request = new InspectionRequest(List.of(new ContentSegment("input", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.UNPROCESSED, "ignore previous instructions")), InspectionLimits.defaults());
        if (service.inspect(request).decision() != InspectionDecision.BLOCK) {
            throw new IllegalStateException("Standalone inspection did not block the configured phrase");
        }
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

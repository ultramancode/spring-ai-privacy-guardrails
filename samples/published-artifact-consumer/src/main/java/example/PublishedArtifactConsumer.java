package example;

import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.RegexPiiAnalyzer;
import io.github.ultramancode.springai.privacy.security.SpringSecurityToolBoundary;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationPhase;
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.test.PrivacyTestProbe;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;

@SpringBootConfiguration
@EnableAutoConfiguration
public class PublishedArtifactConsumer {

    @Bean
    GuardrailsPolicy guardrailsPolicy() {
        ToolDisclosurePolicy disclosure = ToolDisclosurePolicy.allowEntityTypesByToolName(
            Map.of("customerLookup", List.of("CUSTOMER_ID")));
        GuardrailsPolicy.Privacy privacyPolicy = GuardrailsPolicy.Privacy.builder()
            .toolDisclosurePolicy(disclosure)
            .outputProtection().tokenize()
            .build();
        return GuardrailsPolicy.builder()
            .privacy(privacyPolicy)
            .toolAuthorization(true)
            .build();
    }

    @Bean
    PublishedAuthorizationChecks publishedAuthorizationChecks() {
        return new PublishedAuthorizationChecks();
    }

    @Bean
    AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager(
            PublishedAuthorizationChecks checks
    ) {
        return (authentication, authorizationContext) -> checks.authorize(
                authentication.get(),
                authorizationContext
        );
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
            PublishedArtifactConsumer.class
        )
            .web(WebApplicationType.NONE)
            .properties(
                "spring.main.banner-mode=off",
                "logging.level.root=ERROR",
                "spring.ai.privacy.regex.enabled=true",
                "spring.ai.privacy.regex.rules[0].entity-type=EMAIL_ADDRESS",
                "spring.ai.privacy.regex.rules[0].pattern="
                    + "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
                "spring.ai.privacy.regex.rules[0].score=0.95",
                "spring.ai.privacy.regex.rules[1].entity-type=CUSTOMER_ID",
                "spring.ai.privacy.regex.rules[1].pattern=\\bCUST-\\d{4}\\b",
                "spring.ai.privacy.regex.rules[1].score=0.99"
            )
            .run(args)) {
            verifyRuntime(context);
        }
    }

    private static void verifyRuntime(ConfigurableApplicationContext context) {
        context.getBean(PrivacyGuardrailsAutoConfiguration.class);
        context.getBean(RegexPiiAnalyzer.class);
        context.getBean(PrivacyChatClientConfigurer.class);
        context.getBean(GuardrailsChatClientFactory.class);
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
        SpringSecurityToolBoundary toolBoundary = context.getBean(
                SpringSecurityToolBoundary.class
        );
        ToolCallingManager upstreamManager = context.getBean(ToolCallingManager.class);
        if (upstreamManager == toolBoundary.toolCallingManager()) {
            throw new IllegalStateException(
                    "Published Security starter replaced the shared application ToolCallingManager"
            );
        }
        PublishedAuthorizationChecks authorizationChecks = context.getBean(
                PublishedAuthorizationChecks.class
        );
        AtomicInteger deniedToolCalls = new AtomicInteger();
        try (PrivacyTestProbe privacyTestProbe = PrivacyTestProbe.create(privacyService)) {
            AtomicReference<CustomerLookupRequest> toolInput = new AtomicReference<>();
            ToolCallback customerTool = customerLookup(toolInput);
            ToolCallback deniedTool = adminDelete(deniedToolCalls);
            ChatModel chatModel = privacyTestProbe.wrapModel(
                new PublishedToolLoopModel(upstreamManager)
            );
            ChatClient.Builder chatClientBuilder = context.getBean(GuardrailsChatClientFactory.class)
                .builder(chatModel).defaultTools(customerTool, deniedTool);
            SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "published-consumer",
                    "not-used",
                    List.of()
            ));
            SecurityContextHolder.setContext(securityContext);
            String finalResponse;
            try {
                finalResponse = chatClientBuilder.build()
                    .prompt()
                    .user("Find CUST-0042 for alice@example.com")
                    .call()
                    .content();
            } finally {
                SecurityContextHolder.clearContext();
            }

            CustomerLookupRequest input = toolInput.get();
            if (privacyTestProbe.modelRequests().size() != 2 || input == null || deniedToolCalls.get() != 0) {
                throw new IllegalStateException("Published client did not preserve the authorized tool loop");
            }
            if (!input.customerId().equals("CUST-0042") || input.email().contains("alice@example.com")) {
                throw new IllegalStateException("Published client violated scoped disclosure");
            }
            boolean modelSawRaw = privacyTestProbe.modelRequests().stream()
                .flatMap(request -> request.modelVisibleContent().stream())
                .anyMatch(content -> content.contains("CUST-0042")
                    || content.contains("alice@example.com")
                    || content.contains("CUST-9000")
                    || content.contains("result@example.com"));
            if (modelSawRaw) {
                throw new IllegalStateException("Published client exposed raw PII to the model");
            }
            if (finalResponse.contains("final@example.com")
                    || !OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS").matcher(finalResponse).find()) {
                throw new IllegalStateException("Published output policy did not protect the final response");
            }
            authorizationChecks.verify();
        }
        if (privacyService.activeSessionCount() != 0) {
            throw new IllegalStateException("Published tool smoke retained a privacy session after close");
        }
    }

    private static ToolCallback customerLookup(AtomicReference<CustomerLookupRequest> input) {
        return FunctionToolCallback.builder(
            "customerLookup",
            (CustomerLookupRequest request) -> {
                input.set(request);
                return "processed CUST-9000 for result@example.com";
            }
        )
            .description("Returns one synthetic customer")
            .inputType(CustomerLookupRequest.class)
            .build();
    }

    private static ToolCallback adminDelete(AtomicInteger calls) {
        return FunctionToolCallback.builder(
                        "adminDelete",
                        (AdminDeleteRequest ignored) -> {
                            calls.incrementAndGet();
                            return "deleted";
                        }
                )
                .description("Deletes one synthetic customer")
                .inputType(AdminDeleteRequest.class)
                .build();
    }

    private record CustomerLookupRequest(String customerId, String email) {
    }

    private record AdminDeleteRequest(String customerId) {
    }

    private static final class PublishedAuthorizationChecks {

        private final AtomicInteger definitionChecks = new AtomicInteger();
        private final AtomicInteger executionChecks = new AtomicInteger();
        private final AtomicInteger deniedDefinitionChecks = new AtomicInteger();

        private AuthorizationResult authorize(
                Authentication authentication,
                ToolAuthorizationContext context
        ) {
            boolean granted = authentication != null
                    && authentication.isAuthenticated()
                    && authentication.getName().equals("published-consumer")
                    && context.toolDefinition().name().equals("customerLookup");
            if (granted && context.phase() == ToolAuthorizationPhase.DEFINITION) {
                this.definitionChecks.incrementAndGet();
            }
            if (granted && context.phase() == ToolAuthorizationPhase.EXECUTION) {
                this.executionChecks.incrementAndGet();
            }
            if (!granted
                    && context.phase() == ToolAuthorizationPhase.DEFINITION
                    && context.toolDefinition().name().equals("adminDelete")) {
                this.deniedDefinitionChecks.incrementAndGet();
            }
            return new AuthorizationDecision(granted);
        }

        private void verify() {
            if (this.definitionChecks.get() == 0
                    || this.executionChecks.get() == 0
                    || this.deniedDefinitionChecks.get() == 0) {
                throw new IllegalStateException(
                        "Published Security starter did not verify allowed and denied tool boundaries"
                );
            }
        }
    }

    private static final class PublishedToolLoopModel implements ChatModel {

        private final ToolCallingManager toolCallingManager;
        private final AtomicInteger calls = new AtomicInteger();

        private PublishedToolLoopModel(ToolCallingManager toolCallingManager) {
            this.toolCallingManager = Objects.requireNonNull(
                    toolCallingManager,
                    "toolCallingManager must not be null"
            );
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            verifyExposedDefinitions(prompt);
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

        private void verifyExposedDefinitions(Prompt prompt) {
            if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
                throw new IllegalStateException("Expected tool-calling options");
            }
            List<ToolDefinition> definitions = this.toolCallingManager.resolveToolDefinitions(options);
            if (definitions.size() != 1
                    || !definitions.get(0).name().equals("customerLookup")) {
                throw new IllegalStateException(
                        "Published Security starter exposed an unexpected tool definition"
                );
            }
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

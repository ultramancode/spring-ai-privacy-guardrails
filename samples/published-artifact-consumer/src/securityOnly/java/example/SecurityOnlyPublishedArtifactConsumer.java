package example;

import io.github.ultramancode.springai.privacy.security.SpringSecurityToolBoundary;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationPhase;
import io.github.ultramancode.springai.privacy.security.autoconfigure.ToolAuthorizationChatClientConfigurer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootConfiguration
@EnableAutoConfiguration
public class SecurityOnlyPublishedArtifactConsumer {

    @Bean
    AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager(
            PublishedAuthorizationChecks checks
    ) {
        return (authentication, context) -> checks.authorize(
                authentication.get().getName(),
                context
        );
    }

    @Bean
    PublishedAuthorizationChecks publishedAuthorizationChecks() {
        return new PublishedAuthorizationChecks();
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                SecurityOnlyPublishedArtifactConsumer.class
        )
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "logging.level.root=ERROR",
                        "spring.ai.privacy.security.enabled=true"
                )
                .run(args)) {
            verifyToolAuthorization(context);
        }
        System.out.println("Security-only published artifact runtime smoke passed");
    }

    private static void verifyToolAuthorization(ConfigurableApplicationContext context) {
        SpringSecurityToolBoundary boundary = context.getBean(SpringSecurityToolBoundary.class);
        ToolCallingManager authorizationAwareManager = context.getBean(
                ToolCallingManager.class
        );
        if (authorizationAwareManager != boundary.toolCallingManager()) {
            throw new IllegalStateException(
                    "Security starter did not select its authorization-aware manager"
            );
        }

        AtomicReference<CustomerLookupRequest> customerLookupInput = new AtomicReference<>();
        AtomicInteger deniedToolCalls = new AtomicInteger();
        ToolCallback customerLookup = customerLookup(customerLookupInput);
        ToolCallback adminDelete = adminDelete(deniedToolCalls);
        PublishedToolLoopModel model = new PublishedToolLoopModel(authorizationAwareManager);
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(authorizationAwareManager)
                .build();
        ChatClient.Builder builder = ChatClient.builder(model)
                .defaultAdvisors(toolCallingAdvisor)
                .defaultTools(customerLookup, adminDelete);
        context.getBean(ToolAuthorizationChatClientConfigurer.class).configure(builder);

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "published-consumer",
                "not-used",
                List.of()
        ));
        SecurityContextHolder.setContext(securityContext);
        String response;
        try {
            response = builder.build()
                    .prompt()
                    .user("Find CUST-0042")
                    .call()
                    .content();
        } finally {
            SecurityContextHolder.clearContext();
        }

        if (!"done".equals(response)) {
            throw new IllegalStateException("Security-only tool loop did not complete");
        }
        if (!model.exposedToolNames().equals(List.of("customerLookup"))) {
            throw new IllegalStateException("A denied tool definition reached the model");
        }
        CustomerLookupRequest toolInput = customerLookupInput.get();
        if (toolInput == null || !"CUST-0042".equals(toolInput.customerId())) {
            throw new IllegalStateException("The authorized raw tool did not receive its input");
        }
        if (deniedToolCalls.get() != 0) {
            throw new IllegalStateException("The denied raw tool was executed");
        }
        context.getBean(PublishedAuthorizationChecks.class).verify();
    }

    private static ToolCallback customerLookup(
            AtomicReference<CustomerLookupRequest> input
    ) {
        return FunctionToolCallback.builder(
                        "customerLookup",
                        (CustomerLookupRequest request) -> {
                            input.set(request);
                            return "found";
                        }
                )
                .description("Finds one synthetic customer")
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

    private record CustomerLookupRequest(String customerId) {
    }

    private record AdminDeleteRequest(String customerId) {
    }

    private static final class PublishedAuthorizationChecks {

        private final AtomicInteger definitionChecks = new AtomicInteger();
        private final AtomicInteger executionChecks = new AtomicInteger();
        private final AtomicInteger deniedDefinitionChecks = new AtomicInteger();

        private AuthorizationDecision authorize(
                String principalName,
                ToolAuthorizationContext context
        ) {
            boolean granted = principalName.equals("published-consumer")
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
                        "Security-only tool checks did not cover allow and deny paths"
                );
            }
        }
    }

    private static final class PublishedToolLoopModel implements ChatModel {

        private final ToolCallingManager manager;
        private List<String> exposedToolNames = List.of();

        private PublishedToolLoopModel(ToolCallingManager manager) {
            this.manager = manager;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (hasToolResponse(prompt)) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            this.exposedToolNames = this.manager.resolveToolDefinitions(options).stream()
                    .map(ToolDefinition::name)
                    .toList();
            AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                    "call-customerLookup",
                    "function",
                    "customerLookup",
                    "{\"customerId\":\"CUST-0042\"}"
            );
            AssistantMessage response = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(call))
                    .build();
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

        private List<String> exposedToolNames() {
            return this.exposedToolNames;
        }

        private static boolean hasToolResponse(Prompt prompt) {
            return prompt.getInstructions().stream()
                    .anyMatch(ToolResponseMessage.class::isInstance);
        }
    }
}

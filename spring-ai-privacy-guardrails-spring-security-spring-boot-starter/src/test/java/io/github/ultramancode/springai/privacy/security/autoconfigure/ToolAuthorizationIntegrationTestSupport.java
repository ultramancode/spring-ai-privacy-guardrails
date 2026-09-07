package io.github.ultramancode.springai.privacy.security.autoconfigure;

import com.sun.net.httpserver.HttpServer;
import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Timeout(20)
abstract class ToolAuthorizationIntegrationTestSupport {

    protected final List<String> modelRequests = new CopyOnWriteArrayList<>();
    protected HttpServer server;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    protected ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolAuthorizationAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class,
                        ChatClientAutoConfiguration.class))
                .withUserConfiguration(PolicyConfiguration.class)
                .withPropertyValues("spring.ai.privacy.security.enabled=true",
                        "spring.ai.openai.api-key=test-api-key",
                        "spring.ai.openai.base-url=http://127.0.0.1:" + this.server.getAddress().getPort());
    }

    protected ApplicationContextRunner privacyContextRunner() {
        return contextRunner().withConfiguration(AutoConfigurations.of(
                        PrivacyGuardrailsAutoConfiguration.class, PrivacySecurityAutoConfiguration.class))
                .withPropertyValues("spring.ai.privacy.enabled=true",
                        "spring.ai.privacy.regex.enabled=true",
                        "spring.ai.privacy.regex.rules[0].entity-type=EMPLOYEE_ID",
                        "spring.ai.privacy.regex.rules[0].pattern=EMP-[0-9]{4}",
                        "spring.ai.privacy.regex.rules[0].score=0.9",
                        "spring.ai.privacy.tools.disclosures.customerLookup[0]=EMPLOYEE_ID");
    }

    protected void startModelServer(String... responses) throws IOException {
        Queue<String> queuedResponses = new ConcurrentLinkedQueue<>(List.of(responses));
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            try (exchange) {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                this.modelRequests.add(request);
                String response = queuedResponses.poll();
                if (response != null && response.contains("SEARCH_QUERY_PARAMETER")) {
                    response = response.replace("SEARCH_QUERY_PARAMETER", toolSearchQueryParameter(request));
                }
                byte[] body = (response == null ? "{}" : response).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",
                        response != null && response.startsWith("data:") ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(response == null ? 500 : 200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        this.server.start();
    }

    protected static String toolSearchQueryParameter(String request) {
        // The simulated model must follow the actual schema: 2.0.0 exposes arg0,
        // while 2.0.1 exposes query. This is test response construction, not a runtime workaround.
        for (JsonNode tool : JsonMapper.builder().build().readTree(request).path("tools")) {
            JsonNode function = tool.path("function");
            if ("toolSearchTool".equals(function.path("name").asString())) {
                JsonNode properties = function.path("parameters").path("properties");
                if (properties.has("query")) {
                    return "query";
                }
                if (properties.has("arg0")) {
                    return "arg0";
                }
            }
        }
        throw new IllegalStateException("The model did not receive a supported Tool Search query schema");
    }

    protected static ToolCallback tool(String name, AtomicInteger calls) {
        return tool(name, input -> calls.incrementAndGet());
    }

    protected static ToolCallback tool(String name, Consumer<String> inputConsumer) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("Test operation")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }

            @Override
            public String call(String input) {
                inputConsumer.accept(input);
                return "success";
            }
        };
    }

    protected static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", "unused", List.of()));
    }

    protected static String toolResponse(String toolName) {
        return toolResponse(toolName, "{}");
    }

    protected static String toolResponse(String toolName, String escapedArguments) {
        return """
                {"id":"test","object":"chat.completion","created":0,"model":"test",
                 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant",
                   "tool_calls":[{"id":"call-1","type":"function","function":{"name":"%s","arguments":"%s"}}]}}]}
                """.formatted(toolName, escapedArguments);
    }

    protected static String finalResponse() {
        return """
                {"id":"test","object":"chat.completion","created":0,"model":"test",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"done"}}]}
                """;
    }

    protected static String toolStreamResponse(String toolName) {
        return toolStreamResponse(toolName, "{}");
    }

    protected static String toolStreamResponse(String toolName, String escapedArguments) {
        return """
                data: {"id":"test","object":"chat.completion.chunk","created":0,"model":"test","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"%s","arguments":"%s"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """.formatted(toolName, escapedArguments);
    }

    protected static String finalStreamResponse() {
        return """
                data: {"id":"test","object":"chat.completion.chunk","created":0,"model":"test","choices":[{"index":0,"delta":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}

                data: [DONE]

                """;
    }

    protected static final class RemoveToolContextAdvisor implements CallAdvisor {
        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            ToolCallingChatOptions options = (ToolCallingChatOptions) request.prompt().getOptions();
            return chain.nextCall(request.mutate().prompt(new Prompt(request.prompt().getInstructions(),
                    options.mutate().toolContext(null).build())).build());
        }

        @Override
        public String getName() {
            return "RemoveToolContextAdvisor";
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PolicyConfiguration {
        @Bean
        AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
            return (authentication, context) -> new AuthorizationDecision(
                    context.toolDefinition().name().equals("customerLookup"));
        }
    }
}

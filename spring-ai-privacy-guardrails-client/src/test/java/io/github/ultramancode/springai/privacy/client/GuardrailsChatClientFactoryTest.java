package io.github.ultramancode.springai.privacy.client;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionBlockedException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionExecution;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionObserver;
import io.github.ultramancode.springai.privacy.security.SpringSecurityToolBoundary;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationChatClientFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAction;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class GuardrailsChatClientFactoryTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "privacy={0}, inspection={1}, authorization={2}, streaming={3}")
    @CsvSource({
            "true,  false, false, false",
            "true,  false, false, true",
            "false, true,  false, false",
            "false, true,  false, true",
            "true,  true,  false, false",
            "true,  true,  false, true",
            "false, false, true,  false",
            "false, false, true,  true",
            "true,  false, true,  false",
            "true,  false, true,  true",
            "false, true,  true,  false",
            "false, true,  true,  true",
            "true,  true,  true,  false",
            "true,  true,  true,  true"
    })
    void appliesSelectedFeaturesToModelRequestsAndToolCalls(
            boolean privacyEnabled, boolean inspectionEnabled, boolean authorizationEnabled, boolean streaming) {
        PrivacyService privacy = privacyService();
        List<InspectionRequest> inspections = new CopyOnWriteArrayList<>();
        List<InspectionExecution> executions = new CopyOnWriteArrayList<>();
        AtomicInteger authorizations = new AtomicInteger();
        AtomicReference<String> toolInput = new AtomicReference<>();
        LoopModel model = new LoopModel();
        ToolCallback callback = tool("lookup", false, toolInput);
        GuardrailsChatClientFactory factory = GuardrailsChatClientFactory.builder()
                .privacyService(privacy).inspectionService(inspectionService(inspections))
                .inspectionObserver((report, execution) -> executions.add(execution))
                .toolAuthorization(authorizationFactory(authorizations)::builder).build();
        GuardrailsPolicy.Builder policy = GuardrailsPolicy.builder().toolAuthorization(authorizationEnabled);
        if (privacyEnabled) {
            policy.privacy(settings -> settings
                    .toolDisclosurePolicy(ToolDisclosurePolicy.byToolName(Map.of("lookup", List.of("PERSON"))))
                    .outputAction(PrivacyOutputAction.REDACT));
        }
        if (inspectionEnabled) {
            policy.inspection(settings -> settings.applicationOutput(true));
        }
        ChatClient.Builder builder = factory.builder(model, policy.build());
        if (!streaming) {
            builder.defaultTools(callback);
        }
        ChatClient.ChatClientRequestSpec request = builder.build().prompt().user("Alice");
        if (streaming) {
            request.tools(callback);
        }
        authenticate();

        String output = invoke(request, streaming);

        assertThat(toolInput.get()).isEqualTo("{\"value\":\"Alice\"}");
        assertThat(model.requests).hasSize(2);
        if (privacyEnabled) {
            assertThat(model.requests.get(0).getUserMessage().getText())
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
            assertThat(output).isEqualTo("[REDACTED_PERSON]");
        } else {
            assertThat(model.requests.get(0).getUserMessage().getText()).isEqualTo("Alice");
            assertThat(output).isEqualTo("Alice");
        }
        if (inspectionEnabled) {
            assertThat(inspections).hasSize(3);
            assertThat(executions).containsExactly(
                    new InspectionExecution(InspectionExecution.Boundary.MODEL_REQUEST, streaming),
                    new InspectionExecution(InspectionExecution.Boundary.MODEL_REQUEST, streaming),
                    new InspectionExecution(InspectionExecution.Boundary.APPLICATION_OUTPUT, streaming));
            if (privacyEnabled) {
                assertThat(inspections.get(0).segments()).allSatisfy(segment ->
                        assertThat(segment.privacyProcessingStatus()).isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
            }
        } else {
            assertThat(inspections).isEmpty();
        }
        if (authorizationEnabled) {
            assertThat(authorizations).hasValueGreaterThan(0);
        } else {
            assertThat(authorizations).hasValue(0);
        }
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refreshesProviderToolsAcrossClonedAndMutatedClients(boolean streaming) {
        PrivacyService privacy = privacyService();
        AtomicReference<String> firstInput = new AtomicReference<>();
        AtomicReference<String> secondInput = new AtomicReference<>();
        AtomicReference<ToolCallback[]> tools = new AtomicReference<>(
                new ToolCallback[]{tool("first", true, firstInput)});
        ToolCallbackProvider provider = tools::get;
        GuardrailsPolicy policy = GuardrailsPolicy.builder().privacy(settings ->
                settings.outputAction(PrivacyOutputAction.REDACT)).build();
        GuardrailsChatClientFactory factory = GuardrailsChatClientFactory.builder()
                .privacyService(privacy).defaultPolicy(policy).build();
        ChatClient.Builder builder = factory.builder(new LoopModel()).defaultTools(provider);
        ChatClient first = builder.clone().build();
        assertThat(invoke(first.prompt().user("Alice"), streaming)).isEqualTo("[REDACTED_PERSON]");
        tools.set(new ToolCallback[]{tool("second", true, secondInput)});
        ChatClient second = first.mutate().build();
        assertThat(invoke(second.prompt().user("Alice"), streaming)).isEqualTo("[REDACTED_PERSON]");

        assertThat(firstInput.get()).contains("[[PII_PERSON_").doesNotContain("Alice");
        assertThat(secondInput.get()).contains("[[PII_PERSON_").doesNotContain("Alice");
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @Test
    void explicitPoliciesDoNotInheritDisclosureOrOutputSettings() {
        PrivacyService privacy = privacyService();
        GuardrailsPolicy defaults = GuardrailsPolicy.builder().privacy(settings -> settings
                .toolDisclosurePolicy(ToolDisclosurePolicy.byToolName(Map.of("lookup", List.of("PERSON"))))
                .outputAction(PrivacyOutputAction.REDACT)).build();
        GuardrailsChatClientFactory factory = GuardrailsChatClientFactory.builder()
                .privacyService(privacy).defaultPolicy(defaults).build();
        AtomicReference<String> disclosed = new AtomicReference<>();
        AtomicReference<String> protectedInput = new AtomicReference<>();
        ChatClient first = factory.builder(new LoopModel()).defaultTools(tool("lookup", true, disclosed)).build();
        ChatClient second = factory.builder(new LoopModel(), GuardrailsPolicy.builder().privacy().build())
                .defaultTools(tool("lookup", true, protectedInput)).build();

        assertThat(first.prompt().user("Alice").call().content()).isEqualTo("[REDACTED_PERSON]");
        assertThat(second.prompt().user("Alice").call().content())
                .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
        assertThat(disclosed.get()).contains("Alice");
        assertThat(protectedInput.get()).doesNotContain("Alice").contains("[[PII_PERSON_");
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @Test
    void rejectsMissingDefaultPolicyOrPrivacyService() {
        GuardrailsChatClientFactory empty = GuardrailsChatClientFactory.builder().build();
        assertThatThrownBy(() -> empty.builder(new LoopModel())).hasMessageContaining("No default GuardrailsPolicy");
        assertThatThrownBy(() -> empty.builder(new LoopModel(), GuardrailsPolicy.builder().privacy().build()))
                .hasMessageContaining("no PrivacyService");
    }

    @Test
    void rejectsAlreadyWrappedTools() {
        PrivacyService privacy = privacyService();
        ToolCallback wrappedTool = new PrivacyToolCallbackFactory(privacy, ToolDisclosurePolicy.denyAll())
                .wrap(tool("lookup", false, new AtomicReference<>()));
        ChatClient client = GuardrailsChatClientFactory.builder().privacyService(privacy).build()
                .builder(new LoopModel(), GuardrailsPolicy.builder().privacy().build()).defaultTools(wrappedTool).build();
        assertThatThrownBy(() -> client.prompt().user("Alice").call().content())
                .hasMessageContaining("already be privacy wrapped");
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protectsToolSearchCallbacksWithACustomToolOrder(boolean streaming) {
        PrivacyService privacy = privacyService();
        AtomicReference<String> toolInput = new AtomicReference<>();
        ToolIndex index = mock(ToolIndex.class);
        when(index.search(any())).thenReturn(ToolSearchResponse.builder()
                .addToolReference(ToolReference.builder().toolName("lookup").summary("Look up a person").build())
                .build());
        ToolCallingAdvisor.Builder<?> search = ToolSearchToolCallingAdvisor.builder().toolIndex(index)
                .systemMessageSuffix("Search for the tool before calling it.").advisorOrder(100);
        GuardrailsPolicy policy = GuardrailsPolicy.builder().privacy(settings -> settings
                .toolDisclosurePolicy(ToolDisclosurePolicy.byToolName(Map.of("lookup", List.of("PERSON")))))
                .inspection().toolAuthorization(true).build();
        GuardrailsChatClientFactory factory = GuardrailsChatClientFactory.builder().privacyService(privacy)
                .inspectionService(inspectionService(new CopyOnWriteArrayList<>()))
                .toolAuthorization(authorizationFactory(new AtomicInteger())::builder).build();
        ChatModel model = new ChatModel() {
            public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
            public ChatResponse call(Prompt prompt) {
                List<String> respondedTools = prompt.getInstructions().stream()
                        .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                        .flatMap(message -> message.getResponses().stream()).map(ToolResponseMessage.ToolResponse::name)
                        .toList();
                if (respondedTools.contains("lookup")) {
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                }
                ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
                String name = "lookup";
                String arguments = "{\"value\":\"" + prompt.getUserMessage().getText() + "\"}";
                if (!respondedTools.contains("toolSearchTool")) {
                    ToolDefinition definition = options.getToolCallbacks().stream().map(ToolCallback::getToolDefinition)
                            .filter(tool -> tool.name().equals("toolSearchTool")).findFirst().orElseThrow();
                    name = definition.name();
                    String queryParameter = "arg0";
                    if (definition.inputSchema().contains("\"query\"")) {
                        queryParameter = "query";
                    }
                    arguments = "{\"" + queryParameter + "\":\"lookup\"}";
                }
                AssistantMessage message = AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + name, "function", name, arguments))).build();
                return new ChatResponse(List.of(new Generation(message)));
            }
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> Flux.just(call(prompt)));
            }
        };
        ChatClient client = factory.builder(model, policy, search).defaultTools(tool("lookup", false, toolInput)).build();
        authenticate();

        String output = invoke(client.prompt().user("Alice")
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "guardrails-search")), streaming);

        assertThat(output).isEqualTo("done");
        assertThat(toolInput.get()).isEqualTo("{\"value\":\"Alice\"}");
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void connectsPrivacyTokenSimplificationToInputAndOutputInspection(boolean streaming) {
        PrivacyService privacy = privacyService();
        List<InspectionRequest> inspections = new CopyOnWriteArrayList<>();
        InspectionService inspection = inspectionService(inspections).withPiiTokenSimplification(Set.of("guard"));
        GuardrailsChatClientFactory factory = GuardrailsChatClientFactory.builder().privacyService(privacy)
                .inspectionService(inspection).build();
        GuardrailsPolicy policy = GuardrailsPolicy.builder()
                .privacy(settings -> settings.outputAction(PrivacyOutputAction.TOKENIZE))
                .inspection(settings -> settings.applicationOutput(true)).build();
        ChatClient client = factory.builder(new LoopModel(), policy)
                .defaultTools(tool("lookup", true, new AtomicReference<>())).build();

        String output = invoke(client.prompt().user("Alice"), streaming);

        assertThat(output).matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
        assertThat(inspections).hasSize(2).allSatisfy(request -> {
            assertThat(request.segments()).extracting(ContentSegment::text).contains("[PERSON]");
            assertThat(request.segments()).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                    .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
        });
        assertThat(privacy.activeSessionCount()).isZero();
        assertThatThrownBy(() -> factory.builder(new LoopModel(), GuardrailsPolicy.builder().inspection().build()))
                .hasMessageContaining("requires privacy");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputOnlyInspectionBlocksBeforeDeliveringContent(boolean streaming) {
        InspectionRule rule = InspectionRule.literal("blocked", InspectionFinding.Category.PROMPT_INJECTION, "attack");
        InspectionService inspection = new InspectionService(List.of(new RuleBasedContentInspector("rules", List.of(rule))));
        List<InspectionExecution> executions = new CopyOnWriteArrayList<>();
        GuardrailsChatClientFactory factory = GuardrailsChatClientFactory.builder().inspectionService(inspection)
                .inspectionObserver((report, execution) -> executions.add(execution)).build();
        GuardrailsPolicy policy = GuardrailsPolicy.builder().inspection(settings -> settings
                .modelRequests(false).applicationOutput(true)).build();
        ChatClient client = factory.builder(responseModel(Flux.just(response("attack"))), policy).build();

        if (streaming) {
            StepVerifier.create(client.prompt().user("hello").stream().content())
                    .expectError(InspectionBlockedException.class).verify(Duration.ofSeconds(5));
        } else {
            assertThatThrownBy(() -> client.prompt().user("hello").call().content())
                    .isInstanceOf(InspectionBlockedException.class);
        }
        assertThat(executions).containsExactly(
                new InspectionExecution(InspectionExecution.Boundary.APPLICATION_OUTPUT, streaming));
    }

    @Test
    void outputFrameLimitReportsItsBoundaryAndClosesPrivacySession() {
        PrivacyService privacy = privacyService();
        AtomicReference<InspectionExecution> failureContext = new AtomicReference<>();
        GuardrailsChatClientFactory factory = GuardrailsChatClientFactory.builder().privacyService(privacy)
                .inspectionService(inspectionService(new CopyOnWriteArrayList<>()))
                .inspectionObserver(new InspectionObserver() {
                    public void onInspection(InspectionReport report, InspectionExecution execution) {}
                    public void onFailure(InspectionException failure, InspectionExecution execution) {
                        failureContext.set(execution);
                    }
                }).build();
        GuardrailsPolicy policy = GuardrailsPolicy.builder().privacy().inspection(settings -> settings
                .modelRequests(false).applicationOutput(true).maxOutputFrames(1)).build();
        ChatClient client = factory.builder(responseModel(Flux.just(response("one"), response("two"))), policy).build();

        StepVerifier.create(client.prompt().user("Alice").stream().content())
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED)))
                .verify(Duration.ofSeconds(5));
        assertThat(failureContext.get()).isEqualTo(
                new InspectionExecution(InspectionExecution.Boundary.APPLICATION_OUTPUT, true));
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @Test
    void concurrentStreamsHaveSeparateSessionsAndCloseThemOnErrorOrCancellation() {
        PrivacyService privacy = privacyService();
        GuardrailsChatClientFactory factory = GuardrailsChatClientFactory.builder().privacyService(privacy)
                .defaultPolicy(GuardrailsPolicy.builder().privacy().build()).build();
        ChatClient waiting = factory.builder(responseModel(Flux.never())).build();
        ChatClient failing = factory.builder(responseModel(Flux.error(new IllegalStateException("model unavailable"))))
                .build();

        StepVerifier.create(waiting.prompt().user("Alice").stream().content())
                .then(() -> {
                    assertThat(privacy.activeSessionCount()).isEqualTo(1);
                    StepVerifier.create(waiting.prompt().user("Alice").stream().content())
                            .then(() -> assertThat(privacy.activeSessionCount()).isEqualTo(2))
                            .thenCancel().verify(Duration.ofSeconds(5));
                    assertThat(privacy.activeSessionCount()).isEqualTo(1);
                    StepVerifier.create(failing.prompt().user("Alice").stream().content())
                            .expectErrorMessage("model unavailable").verify(Duration.ofSeconds(5));
                    assertThat(privacy.activeSessionCount()).isEqualTo(1);
                })
                .thenCancel().verify(Duration.ofSeconds(5));
        assertThat(privacy.activeSessionCount()).isZero();
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatModel responseModel(Flux<ChatResponse> stream) {
        return new ChatModel() {
            public ChatResponse call(Prompt prompt) { return response("attack"); }
            public Flux<ChatResponse> stream(Prompt prompt) { return stream; }
        };
    }

    private static PrivacyService privacyService() {
        return new PrivacyService(List.of((text, options, limits) -> {
            int start = text.indexOf("Alice");
            if (start < 0) return List.of();
            return List.of(new PiiSpan("PERSON", start, start + 5, 1.0));
        }), PiiAnalysisOptions.defaults());
    }

    private static InspectionService inspectionService(List<InspectionRequest> requests) {
        ContentInspector inspector = new ContentInspector() {
            public String inspectorId() { return "guard"; }
            public boolean requiresPrivacyProcessedContent() { return false; }
            public InspectionResult inspect(InspectionRequest request) {
                requests.add(request);
                return InspectionResult.completed(request.segments().stream()
                        .map(ContentSegment::id).collect(Collectors.toSet()), List.of());
            }
        };
        return new InspectionService(List.of(inspector));
    }

    private static ToolAuthorizationChatClientFactory authorizationFactory(AtomicInteger checks) {
        SpringSecurityToolBoundary boundary = SpringSecurityToolBoundary.builder(
                ToolCallingManager.builder().build(), (authentication, context) -> {
                    checks.incrementAndGet();
                    return new AuthorizationDecision(true);
                }).build();
        return new ToolAuthorizationChatClientFactory(boundary);
    }

    private static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("operator", "unused", List.of()));
    }

    private static String invoke(ChatClient.ChatClientRequestSpec request, boolean streaming) {
        if (streaming) {
            return request.stream().content().collectList().map(parts -> String.join("", parts))
                    .block(Duration.ofSeconds(10));
        }
        return request.call().content();
    }

    private static ToolCallback tool(String name, boolean returnDirect, AtomicReference<String> input) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("Look up a person")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}").build();
            }
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(returnDirect).build();
            }
            public String call(String toolInput) {
                input.set(toolInput);
                return "Alice";
            }
        };
    }

    private static final class LoopModel implements ChatModel {
        private final List<Prompt> requests = new CopyOnWriteArrayList<>();

        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        public ChatResponse call(Prompt prompt) {
            requests.add(prompt);
            boolean hasToolResult = prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
            if (hasToolResult) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("Alice"))));
            }
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            String name = options.getToolCallbacks().get(0).getToolDefinition().name();
            String arguments = "{\"value\":\"" + prompt.getUserMessage().getText() + "\"}";
            AssistantMessage message = AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, arguments))).build();
            return new ChatResponse(List.of(new Generation(message)));
        }

        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }
    }
}

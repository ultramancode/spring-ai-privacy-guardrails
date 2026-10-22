package io.github.ultramancode.springai.privacy.chatclient;

import io.github.ultramancode.springai.privacy.boundary.ToolAuthorizationConfiguration;
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
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationPhase;
import io.github.ultramancode.springai.privacy.springai.PrivacyEnforcementBoundary;
import io.github.ultramancode.springai.privacy.springai.PrivacyEnforcementEvent;
import io.github.ultramancode.springai.privacy.springai.PrivacyEnforcementOutcome;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputBlockedException;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.AdvisorParams;
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
import org.springframework.security.authorization.AuthorizationDeniedException;
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
import static org.assertj.core.api.Assertions.assertThatNoException;
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
        PrivacyService privacyService = privacyService();
        List<InspectionRequest> inspections = new CopyOnWriteArrayList<>();
        InspectionService inspectionService = inspectionService(inspections);
        List<InspectionExecution> executions = new CopyOnWriteArrayList<>();
        AtomicInteger authorizations = new AtomicInteger();
        AtomicReference<String> toolInput = new AtomicReference<>();
        LoopModel chatModel = new LoopModel();
        ToolCallback callback = tool("lookup", false, toolInput);
        GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder()
            .privacyService(privacyService)
            .inputInspectionService(inspectionService)
            .outputInspectionService(inspectionService)
            .inspectionObserver((report, execution) -> executions.add(execution))
            .toolAuthorization(toolAuthorizationFactory(authorizations)::prepareToolAuthorization).build();
        GuardrailsPolicy.Builder guardrailsPolicyBuilder = GuardrailsPolicy.builder().toolAuthorization(authorizationEnabled);
        if (privacyEnabled) {
            guardrailsPolicyBuilder.privacy(privacyPolicyBuilder -> privacyPolicyBuilder
                .toolDisclosurePolicy(ToolDisclosurePolicy.allowEntityTypesByToolName(
                    Map.of("lookup", List.of("PERSON"))))
                .outputProtection().redact());
        }
        if (inspectionEnabled) {
            guardrailsPolicyBuilder.inspection(inspectionPolicyBuilder -> inspectionPolicyBuilder
                .finalOutputInspectionEnabled(true));
        }
        ChatClient.Builder chatClientBuilder = guardrailsChatClientFactory.builder(chatModel, guardrailsPolicyBuilder.build());
        if (!streaming) {
            chatClientBuilder.defaultTools(callback);
        }
        ChatClient.ChatClientRequestSpec request = chatClientBuilder.build().prompt().user("Alice");
        if (streaming) {
            request.tools(callback);
        }
        authenticate();

        String output = invoke(request, streaming);

        assertThat(toolInput.get()).isEqualTo("{\"value\":\"Alice\"}");
        assertThat(chatModel.requests).hasSize(2);
        if (privacyEnabled) {
            assertThat(chatModel.requests.get(0).getUserMessage().getText())
                .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
            assertThat(output).isEqualTo("[REDACTED_PERSON]");
        } else {
            assertThat(chatModel.requests.get(0).getUserMessage().getText()).isEqualTo("Alice");
            assertThat(output).isEqualTo("Alice");
        }
        if (inspectionEnabled) {
            assertThat(inspections).hasSize(3);
            assertThat(executions).containsExactly(
                new InspectionExecution(InspectionExecution.Phase.MODEL_REQUEST, streaming),
                new InspectionExecution(InspectionExecution.Phase.MODEL_REQUEST, streaming),
                new InspectionExecution(InspectionExecution.Phase.FINAL_OUTPUT, streaming));
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
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deniedToolExecutionDoesNotDisclosePiiAndClosesPrivacySession(boolean streaming) {
        PrivacyService privacyService = privacyService();
        List<PrivacyEnforcementEvent> privacyEvents = new CopyOnWriteArrayList<>();
        List<ToolAuthorizationPhase> authorizationPhases = new CopyOnWriteArrayList<>();
        AtomicInteger toolExecutions = new AtomicInteger();
        ToolCallback callback = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("Look up a person")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}").build();
            }

            public String call(String input) {
                toolExecutions.incrementAndGet();
                return "Alice";
            }
        };
        SpringSecurityToolBoundary toolAuthorizationBoundary = SpringSecurityToolBoundary.builder(
            ToolCallingManager.builder().build(), (authentication, context) -> {
                assertThat(authentication.get().getName()).isEqualTo("operator");
                assertThat(context.toolDefinition().name()).isEqualTo("lookup");
                authorizationPhases.add(context.phase());
                return new AuthorizationDecision(context.phase() == ToolAuthorizationPhase.DEFINITION);
            }).build();
        ToolAuthorizationChatClientFactory toolAuthorizationChatClientFactory = ToolAuthorizationChatClientFactory.builder()
            .toolAuthorizationBoundary(toolAuthorizationBoundary)
            .build();
        GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder()
            .privacyService(privacyService)
            .privacyObserver(privacyEvents::add)
            .toolAuthorization(toolAuthorizationChatClientFactory::prepareToolAuthorization)
            .build();
        GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder()
            .privacy(privacyPolicyBuilder -> privacyPolicyBuilder.toolDisclosurePolicy(
                ToolDisclosurePolicy.allowEntityTypesByToolName(Map.of("lookup", List.of("PERSON")))))
            .toolAuthorization(true).build();
        LoopModel chatModel = new LoopModel();
        ChatClient chatClient = guardrailsChatClientFactory.builder(chatModel, guardrailsPolicy).defaultTools(callback).build();
        authenticate();

        assertThatThrownBy(() -> invoke(chatClient.prompt().user("Alice"), streaming))
            .isInstanceOf(AuthorizationDeniedException.class);

        assertThat(authorizationPhases).contains(ToolAuthorizationPhase.DEFINITION, ToolAuthorizationPhase.EXECUTION);
        assertThat(chatModel.requests).singleElement().satisfies(prompt -> {
            assertThat(prompt.getUserMessage().getText())
                .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
            assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
                .extracting(tool -> tool.getToolDefinition().name()).containsExactly("lookup");
        });
        assertThat(toolExecutions).hasValue(0);
        assertThat(privacyEvents)
            .contains(new PrivacyEnforcementEvent(
                PrivacyEnforcementBoundary.MODEL, PrivacyEnforcementOutcome.PROTECTED))
            .noneMatch(event -> event.outcome() == PrivacyEnforcementOutcome.DISCLOSED);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clientsRejectToolAdvisorsPreparedForAnotherClient(boolean streaming) {
        ToolAuthorizationChatClientFactory toolAuthorizationChatClientFactory =
            toolAuthorizationFactory(new AtomicInteger());
        AtomicReference<ToolCallingAdvisor> firstClientToolAdvisor = new AtomicReference<>();
        GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder()
            .toolAuthorization(toolAdvisorBuilder -> {
                ToolAuthorizationConfiguration toolAuthorizationConfiguration =
                    toolAuthorizationChatClientFactory.prepareToolAuthorization(toolAdvisorBuilder);
                if (firstClientToolAdvisor.get() == null) {
                    firstClientToolAdvisor.set(toolAuthorizationConfiguration.toolAdvisorBuilder().build());
                }
                return toolAuthorizationConfiguration;
            })
            .defaultPolicy(GuardrailsPolicy.builder().toolAuthorization(true).build()).build();
        LoopModel chatModel = new LoopModel();
        AtomicReference<String> firstToolInput = new AtomicReference<>();
        ChatClient firstChatClient = guardrailsChatClientFactory.builder(chatModel)
            .defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
            .defaultAdvisors(firstClientToolAdvisor.get())
            .defaultTools(tool("lookup", false, firstToolInput)).build();
        authenticate();

        assertThat(invoke(firstChatClient.prompt().user("Alice"), streaming)).isEqualTo("Alice");
        assertThat(firstToolInput.get()).isEqualTo("{\"value\":\"Alice\"}");
        assertThat(chatModel.requests).hasSize(2);

        AtomicReference<String> secondToolInput = new AtomicReference<>();
        ChatClient secondChatClient = guardrailsChatClientFactory.builder(chatModel)
            .defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
            .defaultAdvisors(firstClientToolAdvisor.get())
            .defaultTools(tool("lookup", false, secondToolInput)).build();

        assertThatThrownBy(() -> invoke(secondChatClient.prompt().user("Alice"), streaming))
            .isInstanceOf(AuthorizationDeniedException.class).hasMessageContaining("managed tool advisor");
        assertThat(secondToolInput.get()).isNull();
        assertThat(chatModel.requests).hasSize(2);
    }

    @ParameterizedTest(name = "authorization={0}, streaming={1}")
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void refreshesProviderToolsAcrossClonedAndMutatedClients(boolean authorizationEnabled, boolean streaming) {
        PrivacyService privacyService = privacyService();
        AtomicReference<String> firstInput = new AtomicReference<>();
        AtomicReference<String> secondInput = new AtomicReference<>();
        AtomicReference<ToolCallback[]> tools = new AtomicReference<>(
            new ToolCallback[]{tool("first", true, firstInput)});
        ToolCallbackProvider provider = tools::get;
        GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder().privacy(privacyPolicyBuilder ->
            privacyPolicyBuilder.outputProtection().redact())
            .toolAuthorization(authorizationEnabled).build();
        GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder()
            .toolAuthorization(toolAuthorizationFactory(new AtomicInteger())::prepareToolAuthorization)
            .privacyService(privacyService).defaultPolicy(guardrailsPolicy).build();
        ChatClient.Builder chatClientBuilder = guardrailsChatClientFactory.builder(new LoopModel()).defaultTools(provider);
        ChatClient clonedChatClient = chatClientBuilder.clone().build();
        authenticate();
        assertThat(invoke(clonedChatClient.prompt().user("Alice"), streaming)).isEqualTo("[REDACTED_PERSON]");
        tools.set(new ToolCallback[]{tool("second", true, secondInput)});
        ChatClient mutatedChatClient = clonedChatClient.mutate().build();
        assertThat(invoke(mutatedChatClient.prompt().user("Alice"), streaming)).isEqualTo("[REDACTED_PERSON]");

        assertThat(firstInput.get()).containsPattern(OpaquePiiTokenFormat.patternForEntityType("PERSON"))
            .doesNotContain("Alice");
        assertThat(secondInput.get()).containsPattern(OpaquePiiTokenFormat.patternForEntityType("PERSON"))
            .doesNotContain("Alice");
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void explicitPoliciesDoNotInheritDisclosureOrOutputSettings() {
        PrivacyService privacyService = privacyService();
        GuardrailsPolicy defaultPolicy = GuardrailsPolicy.builder().privacy(privacyPolicyBuilder -> privacyPolicyBuilder
            .toolDisclosurePolicy(ToolDisclosurePolicy.allowEntityTypesByToolName(Map.of("lookup", List.of("PERSON"))))
            .outputProtection().redact()).build();
        GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder()
            .privacyService(privacyService).defaultPolicy(defaultPolicy).build();
        AtomicReference<String> disclosed = new AtomicReference<>();
        AtomicReference<String> protectedInput = new AtomicReference<>();
        ChatClient defaultPolicyClient = guardrailsChatClientFactory.builder(new LoopModel())
            .defaultTools(tool("lookup", true, disclosed)).build();
        ChatClient explicitPolicyClient = guardrailsChatClientFactory.builder(new LoopModel(), GuardrailsPolicy.builder().privacy().build())
            .defaultTools(tool("lookup", true, protectedInput)).build();

        assertThat(defaultPolicyClient.prompt().user("Alice").call().content()).isEqualTo("[REDACTED_PERSON]");
        assertThat(explicitPolicyClient.prompt().user("Alice").call().content())
            .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
        assertThat(disclosed.get()).contains("Alice");
        assertThat(protectedInput.get()).doesNotContain("Alice")
            .containsPattern(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputProtectionSettingsControlBlockingAndCanBeRemoved(boolean streaming) {
        PrivacyService privacyService = privacyService();
        ChatModel chatModel = new ChatModel() {
            public ChatResponse call(Prompt prompt) { return response("Alice"); }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        };
        GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder()
            .privacy(GuardrailsPolicy.Privacy.builder()
                .outputProtection().block("Private response blocked")
                .build())
            .build();
        GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder()
            .privacyService(privacyService).defaultPolicy(guardrailsPolicy).build();

        assertThatThrownBy(() -> invoke(guardrailsChatClientFactory.builder(chatModel).build().prompt().user("hello"), streaming))
            .isInstanceOf(PrivacyOutputBlockedException.class).hasMessage("Private response blocked");
        assertThat(privacyService.activeSessionCount()).isZero();

        GuardrailsPolicy inputOnlyPolicy = guardrailsPolicy.mutate()
            .privacy(guardrailsPolicy.privacy().mutate().outputProtection().disable().build()).build();
        ChatClient chatClient = guardrailsChatClientFactory.builder(chatModel, inputOnlyPolicy).build();
        assertThat(invoke(chatClient.prompt().user("hello"), streaming)).isEqualTo("Alice");
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void rejectsMissingDefaultPolicyOrPrivacyService() {
        GuardrailsChatClientFactory unconfiguredFactory = GuardrailsChatClientFactory.builder().build();
        assertThatThrownBy(() -> unconfiguredFactory.builder(new LoopModel())).hasMessageContaining("No default GuardrailsPolicy");
        assertThatThrownBy(() -> unconfiguredFactory.builder(new LoopModel(), GuardrailsPolicy.builder().privacy().build()))
            .hasMessageContaining("no PrivacyService");
    }

    @Test
    void rejectsAlreadyWrappedTools() {
        PrivacyService privacyService = privacyService();
        ToolCallback wrappedTool = new PrivacyToolCallbackFactory(privacyService, ToolDisclosurePolicy.denyAll())
            .wrap(tool("lookup", false, new AtomicReference<>()));
        ChatClient chatClient = GuardrailsChatClientFactory.builder().privacyService(privacyService).build()
            .builder(new LoopModel(), GuardrailsPolicy.builder().privacy().build()).defaultTools(wrappedTool).build();
        assertThatThrownBy(() -> chatClient.prompt().user("Alice").call().content())
            .hasMessageContaining("already be privacy wrapped");
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protectsToolSearchCallbacksWithACustomToolOrder(boolean streaming) {
        PrivacyService privacyService = privacyService();
        AtomicReference<String> toolInput = new AtomicReference<>();
        ToolIndex toolIndex = mock(ToolIndex.class);
        when(toolIndex.search(any())).thenReturn(ToolSearchResponse.builder()
            .addToolReference(ToolReference.builder().toolName("lookup").summary("Look up a person").build())
            .build());
        ToolCallingAdvisor.Builder<?> toolSearchAdvisorBuilder = ToolSearchToolCallingAdvisor.builder().toolIndex(toolIndex)
            .systemMessageSuffix("Search for the tool before calling it.").advisorOrder(100);
        GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder().privacy(privacyPolicyBuilder -> privacyPolicyBuilder
            .toolDisclosurePolicy(ToolDisclosurePolicy.allowEntityTypesByToolName(Map.of("lookup", List.of("PERSON")))))
            .inspection().toolAuthorization(true).build();
        GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder().privacyService(privacyService)
            .inputInspectionService(inspectionService(new CopyOnWriteArrayList<>()))
            .toolAuthorization(toolAuthorizationFactory(new AtomicInteger())::prepareToolAuthorization).build();
        ChatModel chatModel = new ChatModel() {
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
        ChatClient chatClient = guardrailsChatClientFactory.builder(chatModel, guardrailsPolicy, toolSearchAdvisorBuilder)
            .defaultTools(tool("lookup", false, toolInput)).build();
        authenticate();

        String output = invoke(chatClient.prompt().user("Alice")
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "guardrails-search")), streaming);

        assertThat(output).isEqualTo("done");
        assertThat(toolInput.get()).isEqualTo("{\"value\":\"Alice\"}");
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalOutputProtectionRequiresAnEnabledPolicy(boolean streaming) {
        PrivacyService privacyService = privacyService();
        GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder()
            .privacyService(privacyService).build();
        GuardrailsPolicy disabledPolicy = GuardrailsPolicy.builder().privacy().build();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return response("Alice");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }
        };
        ChatClient unprotectedOutputClient = guardrailsChatClientFactory.builder(chatModel, disabledPolicy).build();
        GuardrailsPolicy enabledPolicy = disabledPolicy.mutate()
            .privacy(privacyPolicyBuilder -> privacyPolicyBuilder.outputProtection().tokenize()).build();
        ChatClient protectedOutputClient = guardrailsChatClientFactory.builder(chatModel, enabledPolicy).build();

        assertThat(invoke(unprotectedOutputClient.prompt().user("hello"), streaming)).isEqualTo("Alice");
        assertThat(invoke(protectedOutputClient.prompt().user("hello"), streaming))
            .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simplifiesInputAndOutputWithTheSameService(boolean streaming) {
        PrivacyService privacyService = privacyService();
        List<InspectionRequest> inspections = new CopyOnWriteArrayList<>();
        InspectionService inspectionService = inspectionService(inspections).withPiiTokenSimplification(Set.of("guard"));
        GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder()
            .privacyService(privacyService)
            .inputInspectionService(inspectionService)
            .outputInspectionService(inspectionService)
            .build();
        GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder()
            .privacy(GuardrailsPolicy.Privacy.builder().outputProtection().tokenize().build())
            .inspection(GuardrailsPolicy.Inspection.builder().finalOutputInspectionEnabled(true).build()).build();
        ChatClient chatClient = guardrailsChatClientFactory.builder(new LoopModel(), guardrailsPolicy)
            .defaultTools(tool("lookup", true, new AtomicReference<>())).build();

        String output = invoke(chatClient.prompt().user("Alice"), streaming);

        assertThat(output).matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
        assertThat(inspections).hasSize(2).allSatisfy(request -> {
            assertThat(request.segments()).extracting(ContentSegment::text).contains("[PII_PERSON_1]");
            assertThat(request.segments()).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
        });
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputOnlyInspectionBlocksBeforeDeliveringContent(boolean streaming) {
        InspectionRule rule = InspectionRule.literal("blocked", InspectionFinding.Category.PROMPT_INJECTION, "attack");
        InspectionService inspectionService = new InspectionService(List.of(new RuleBasedContentInspector("rules", List.of(rule))));
        List<InspectionExecution> executions = new CopyOnWriteArrayList<>();
        GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder()
            .outputInspectionService(inspectionService)
            .inspectionObserver((report, execution) -> executions.add(execution)).build();
        GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder().inspection(inspectionPolicyBuilder -> inspectionPolicyBuilder
            .modelRequestInspectionEnabled(false).finalOutputInspectionEnabled(true)).build();
        ChatClient chatClient = guardrailsChatClientFactory
            .builder(responseModel(Flux.just(response("attack"))), guardrailsPolicy).build();

        if (streaming) {
            StepVerifier.create(chatClient.prompt().user("hello").stream().content())
                .expectError(InspectionBlockedException.class).verify(Duration.ofSeconds(5));
        } else {
            assertThatThrownBy(() -> chatClient.prompt().user("hello").call().content())
                .isInstanceOf(InspectionBlockedException.class);
        }
        assertThat(executions).containsExactly(
            new InspectionExecution(InspectionExecution.Phase.FINAL_OUTPUT, streaming));
    }

    @Test
    void outputFrameLimitReportsFinalOutputPhaseAndClosesPrivacySession() {
        PrivacyService privacyService = privacyService();
        AtomicReference<InspectionExecution> failureContext = new AtomicReference<>();
        GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder().privacyService(privacyService)
            .outputInspectionService(inspectionService(new CopyOnWriteArrayList<>()))
            .inspectionObserver(new InspectionObserver() {
                public void onInspection(InspectionReport report, InspectionExecution execution) {}
                public void onFailure(InspectionException failure, InspectionExecution execution) {
                    failureContext.set(execution);
                }
            }).build();
        GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder().privacy()
            .inspection(inspectionPolicyBuilder -> inspectionPolicyBuilder
                .modelRequestInspectionEnabled(false).finalOutputInspectionEnabled(true)
                .maxOutputFrames(1)).build();
        ChatClient chatClient = guardrailsChatClientFactory
            .builder(responseModel(Flux.just(response("one"), response("two"))), guardrailsPolicy).build();

        StepVerifier.create(chatClient.prompt().user("Alice").stream().content())
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(InspectionException.class,
                failure -> assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED)))
            .verify(Duration.ofSeconds(5));
        assertThat(failureContext.get()).isEqualTo(
            new InspectionExecution(InspectionExecution.Phase.FINAL_OUTPUT, true));
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void concurrentStreamsHaveSeparateSessionsAndCloseThemOnErrorOrCancellation() {
        PrivacyService privacyService = privacyService();
        GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder().privacyService(privacyService)
            .defaultPolicy(GuardrailsPolicy.builder().privacy().build()).build();
        ChatClient waitingChatClient = guardrailsChatClientFactory.builder(responseModel(Flux.never())).build();
        ChatClient failingChatClient = guardrailsChatClientFactory
            .builder(responseModel(Flux.error(new IllegalStateException("model unavailable"))))
            .build();

        StepVerifier.create(waitingChatClient.prompt().user("Alice").stream().content())
            .then(() -> {
                assertThat(privacyService.activeSessionCount()).isEqualTo(1);
                StepVerifier.create(waitingChatClient.prompt().user("Alice").stream().content())
                    .then(() -> assertThat(privacyService.activeSessionCount()).isEqualTo(2))
                    .thenCancel().verify(Duration.ofSeconds(5));
                assertThat(privacyService.activeSessionCount()).isEqualTo(1);
                StepVerifier.create(failingChatClient.prompt().user("Alice").stream().content())
                    .expectErrorMessage("model unavailable").verify(Duration.ofSeconds(5));
                assertThat(privacyService.activeSessionCount()).isEqualTo(1);
            })
            .thenCancel().verify(Duration.ofSeconds(5));
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void routesDirectionalInspectionThroughToolCalls(boolean streaming, boolean returnDirect) {
        List<InspectionRequest> inputInspectionRequests = new CopyOnWriteArrayList<>();
        List<InspectionRequest> outputInspectionRequests = new CopyOnWriteArrayList<>();
        List<InspectionExecution> inspectionExecutions = new CopyOnWriteArrayList<>();
        LoopModel chatModel = new LoopModel();
        GuardrailsChatClientFactory guardrailsFactory = GuardrailsChatClientFactory.builder()
            .inputInspectionService(inspectionService(inputInspectionRequests))
            .outputInspectionService(inspectionService(outputInspectionRequests))
            .inspectionObserver((report, execution) -> inspectionExecutions.add(execution))
            .build();
        GuardrailsPolicy inputAndOutputInspectionPolicy = GuardrailsPolicy.builder()
            .inspection(GuardrailsPolicy.Inspection.builder().finalOutputInspectionEnabled(true).build()).build();
        ChatClient chatClient = guardrailsFactory.builder(chatModel, inputAndOutputInspectionPolicy)
            .defaultTools(tool("lookup", returnDirect, new AtomicReference<>())).build();

        assertThat(invoke(chatClient.prompt().user("request"), streaming)).isEqualTo("Alice");

        int expectedModelCalls = returnDirect ? 1 : 2;
        assertThat(chatModel.requests).hasSize(expectedModelCalls);
        assertThat(inputInspectionRequests).hasSize(expectedModelCalls);
        assertThat(inputInspectionRequests.get(0).segments()).extracting(ContentSegment::text).contains("request");
        assertThat(outputInspectionRequests).hasSize(1);
        assertThat(outputInspectionRequests.get(0).segments()).extracting(ContentSegment::text).containsExactly("Alice");

        InspectionExecution inputExecution = new InspectionExecution(InspectionExecution.Phase.MODEL_REQUEST, streaming);
        InspectionExecution outputExecution = new InspectionExecution(InspectionExecution.Phase.FINAL_OUTPUT, streaming);
        assertThat(inspectionExecutions).containsExactlyElementsOf(returnDirect
            ? List.of(inputExecution, outputExecution)
            : List.of(inputExecution, inputExecution, outputExecution));
    }

    @ParameterizedTest
    @EnumSource(InspectionDirection.class)
    void requiresServicesOnlyForEnabledDirections(InspectionDirection inspectionDirection) {
        InspectionService selectedInspectionService = inspectionService(new CopyOnWriteArrayList<>());
        GuardrailsChatClientFactory.Builder guardrailsFactoryBuilder = GuardrailsChatClientFactory.builder();
        if (inspectionDirection == InspectionDirection.INPUT) {
            guardrailsFactoryBuilder.inputInspectionService(selectedInspectionService);
        } else {
            guardrailsFactoryBuilder.outputInspectionService(selectedInspectionService);
        }
        GuardrailsChatClientFactory guardrailsFactory = guardrailsFactoryBuilder.build();
        GuardrailsPolicy singleDirectionPolicy = GuardrailsPolicy.builder()
            .inspection(GuardrailsPolicy.Inspection.builder()
                .modelRequestInspectionEnabled(inspectionDirection == InspectionDirection.INPUT)
                .finalOutputInspectionEnabled(inspectionDirection == InspectionDirection.OUTPUT)
                .build())
            .build();
        ChatModel chatModel = mock(ChatModel.class);

        assertThatNoException().isThrownBy(() -> guardrailsFactory.builder(chatModel, singleDirectionPolicy));

        GuardrailsPolicy bothDirectionsPolicy = singleDirectionPolicy.mutate()
            .inspection(GuardrailsPolicy.Inspection.builder().finalOutputInspectionEnabled(true).build()).build();
        assertThatThrownBy(() -> guardrailsFactory.builder(chatModel, bothDirectionsPolicy))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(inspectionDirection == InspectionDirection.INPUT
                ? "no output InspectionService" : "no input InspectionService");
    }

    @ParameterizedTest
    @CsvSource({"INPUT, false", "INPUT, true", "OUTPUT, false", "OUTPUT, true"})
    void ignoresInspectionServiceForDisabledDirection(InspectionDirection inspectionDirection, boolean streaming) {
        List<InspectionRequest> activeInspectionRequests = new CopyOnWriteArrayList<>();
        List<InspectionRequest> inactiveInspectionRequests = new CopyOnWriteArrayList<>();
        InspectionService activeInspectionService = inspectionService(activeInspectionRequests);
        InspectionService inactiveInspectionService = inspectionService(inactiveInspectionRequests)
            .withPiiTokenSimplification(Set.of("guard"));
        GuardrailsChatClientFactory guardrailsFactory = GuardrailsChatClientFactory.builder()
            .inputInspectionService(inspectionDirection == InspectionDirection.INPUT
                ? activeInspectionService : inactiveInspectionService)
            .outputInspectionService(inspectionDirection == InspectionDirection.OUTPUT
                ? activeInspectionService : inactiveInspectionService)
            .build();
        GuardrailsPolicy singleDirectionPolicy = GuardrailsPolicy.builder()
            .inspection(GuardrailsPolicy.Inspection.builder()
                .modelRequestInspectionEnabled(inspectionDirection == InspectionDirection.INPUT)
                .finalOutputInspectionEnabled(inspectionDirection == InspectionDirection.OUTPUT)
                .build())
            .build();
        ChatModel chatModel = responseModel(Flux.just(response("attack")));
        ChatClient chatClient = guardrailsFactory.builder(chatModel, singleDirectionPolicy).build();

        assertThat(invoke(chatClient.prompt().user("request"), streaming)).isEqualTo("attack");
        assertThat(activeInspectionRequests).hasSize(1);
        assertThat(inactiveInspectionRequests).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(InspectionDirection.class)
    void validatesSimplificationRequirementsPerDirection(InspectionDirection simplificationDirection) {
        InspectionService inspectionService = inspectionService(new CopyOnWriteArrayList<>());
        InspectionService tokenSimplifyingInspectionService = inspectionService.withPiiTokenSimplification(Set.of("guard"));
        GuardrailsChatClientFactory guardrailsFactory = GuardrailsChatClientFactory.builder()
            .privacyService(privacyService())
            .inputInspectionService(simplificationDirection == InspectionDirection.INPUT
                ? tokenSimplifyingInspectionService : inspectionService)
            .outputInspectionService(simplificationDirection == InspectionDirection.OUTPUT
                ? tokenSimplifyingInspectionService : inspectionService)
            .build();
        GuardrailsPolicy inspectionOnlyPolicy = GuardrailsPolicy.builder()
            .inspection(GuardrailsPolicy.Inspection.builder()
                .finalOutputInspectionEnabled(true)
                .build())
            .build();
        ChatModel chatModel = mock(ChatModel.class);
        assertThatThrownBy(() -> guardrailsFactory.builder(chatModel, inspectionOnlyPolicy))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("requires privacy");

        GuardrailsPolicy privacyEnabledPolicy = inspectionOnlyPolicy.mutate().privacy().build();
        if (simplificationDirection == InspectionDirection.OUTPUT) {
            assertThatThrownBy(() -> guardrailsFactory.builder(chatModel, privacyEnabledPolicy))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("requires privacy output protection");
        }
        GuardrailsPolicy policyWithRequiredProtection = switch (simplificationDirection) {
            case INPUT -> privacyEnabledPolicy;
            case OUTPUT -> privacyEnabledPolicy.mutate()
                .privacy(GuardrailsPolicy.Privacy.builder().outputProtection().tokenize().build())
                .build();
        };
        assertThatNoException().isThrownBy(() -> guardrailsFactory.builder(chatModel, policyWithRequiredProtection));
    }

    private enum InspectionDirection {
        INPUT,
        OUTPUT
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

    private static ToolAuthorizationChatClientFactory toolAuthorizationFactory(AtomicInteger authorizationChecks) {
        SpringSecurityToolBoundary toolAuthorizationBoundary = SpringSecurityToolBoundary.builder(
            ToolCallingManager.builder().build(), (authentication, context) -> {
                authorizationChecks.incrementAndGet();
                return new AuthorizationDecision(true);
            }).build();
        return ToolAuthorizationChatClientFactory.builder()
            .toolAuthorizationBoundary(toolAuthorizationBoundary)
            .build();
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

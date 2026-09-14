package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.advisor.observation.DefaultAdvisorObservationConvention;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.client.observation.DefaultChatClientObservationConvention;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ToolAuthorizationChatClientFactoryIntegrationTest extends ToolAuthorizationIntegrationTestSupport {

    @ParameterizedTest(name = "streaming={0}, registerOnRequest={1}")
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void rejectsSeparatelyRegisteredToolAdvisors(boolean streaming, boolean registerOnRequest) throws IOException {
        startModelServer(finalResponse());
        AtomicInteger calls = new AtomicInteger();
        contextRunner().run(context -> {
            ToolAuthorizationChatClientFactory factory = context.getBean(ToolAuthorizationChatClientFactory.class);
            // Use the earliest allowed tool order to verify rejection before tool loop entry.
            ToolCallingAdvisor separateToolAdvisor = ToolCallingAdvisor.builder()
                    .toolCallingManager(context.getBean(ToolCallingManager.class))
                    .advisorOrder(Ordered.HIGHEST_PRECEDENCE + 1)
                    .build();
            ChatClient.Builder builder = factory.builder(context.getBean(OpenAiChatModel.class))
                    .defaultTools(tool("customerLookup", calls));
            if (!registerOnRequest) {
                builder.defaultAdvisors(separateToolAdvisor);
            }
            ChatClient.ChatClientRequestSpec request = builder.build().prompt().user("Run lookup");
            if (registerOnRequest) {
                request.advisors(separateToolAdvisor);
            }
            authenticate();

            assertThatThrownBy(() -> executeRequest(request, streaming))
                    .isInstanceOf(AuthorizationDeniedException.class)
                    .hasMessageContaining("managed tool advisor");
            assertThat(this.modelRequests).isEmpty();
            assertThat(calls).hasValue(0);
        });
    }

    @Test
    void rejectsToolSearchFromACustomizerBeforeItCanIndexDefinitions() throws IOException {
        startModelServer(finalResponse());
        ToolIndex index = mock(ToolIndex.class);
        ChatClientBuilderCustomizer toolSearchCustomizer = builder -> {
            // Use the earliest allowed tool order to verify rejection before Tool Search initialization.
            ToolSearchToolCallingAdvisor separateToolSearchAdvisor = ToolSearchToolCallingAdvisor.builder()
                    .toolIndex(index)
                    .advisorOrder(Ordered.HIGHEST_PRECEDENCE + 1)
                    .systemMessageSuffix("Search for tools before using them.")
                    .build();
            builder.defaultAdvisors(separateToolSearchAdvisor);
        };
        contextRunner().withBean(ChatClientBuilderCustomizer.class, () -> toolSearchCustomizer)
                .run(context -> {
                    ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                            .builder(context.getBean(OpenAiChatModel.class))
                            .defaultTools(tool("adminDelete", new AtomicInteger())).build();
                    authenticate();

                    assertThatThrownBy(() -> client.prompt().user("Find tool").call().content())
                            .isInstanceOf(AuthorizationDeniedException.class)
                            .hasMessageContaining("managed tool advisor");
                    verifyNoInteractions(index);
                    assertThat(this.modelRequests).isEmpty();
                });
    }

    @ParameterizedTest(name = "streaming={0}, disableViaProperty={1}")
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void rejectsDisablingAutomaticRegistrationForProtectedTools(boolean streaming, boolean disableViaProperty)
            throws IOException {
        startModelServer(finalResponse());
        // When testing the request option, leave automatic registration enabled in configuration.
        contextRunner().withPropertyValues("spring.ai.chat.client.tool-calling.enabled=" + !disableViaProperty)
                .run(context -> {
                    ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                            .builder(context.getBean(OpenAiChatModel.class))
                            .defaultTools(tool("customerLookup", new AtomicInteger())).build();
                    ChatClient.ChatClientRequestSpec request = client.prompt().user("Run lookup");
                    if (!disableViaProperty) {
                        request.advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false));
                    }
                    authenticate();

                    assertThatThrownBy(() -> executeRequest(request, streaming))
                            .isInstanceOf(AuthorizationDeniedException.class)
                            .hasMessageContaining("managed tool advisor");
                    assertThat(this.modelRequests).isEmpty();
                });
    }

    @Test
    void builderCloningAndClientMutationPreserveToolAuthorization() throws IOException {
        startModelServer(toolResponse("customerLookup"), finalResponse(),
                toolResponse("customerLookup"), finalResponse());
        AtomicInteger calls = new AtomicInteger();
        contextRunner().run(context -> {
            ChatClient.Builder builder = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class))
                    .defaultTools(tool("customerLookup", calls), tool("adminDelete", new AtomicInteger()));
            ChatClient clonedClient = builder.clone().build();
            ChatClient mutatedClient = builder.build().mutate().build();
            authenticate();
            assertThat(clonedClient.prompt().user("Lookup").call().content()).isEqualTo("done");
            assertThat(mutatedClient.prompt().user("Lookup").call().content()).isEqualTo("done");
            assertThat(calls).hasValue(2);
            // Each client calls the model once to request customerLookup and once to produce the final answer.
            assertThat(this.modelRequests).hasSize(4).allSatisfy(request ->
                    assertThat(request).doesNotContain("adminDelete"));
        });
    }

    @Test
    void preservesTheCopiedBuildersExecutionChecker() throws IOException {
        startModelServer(toolResponse("customerLookup"));
        AtomicInteger calls = new AtomicInteger();
        contextRunner().run(context -> {
            ToolCallingAdvisor.Builder<?> toolAdvisorBuilder = ToolCallingAdvisor.builder()
                    .toolExecutionEligibilityChecker(response -> false);
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class), toolAdvisorBuilder)
                    .defaultTools(tool("customerLookup", calls)).build();
            // Later changes to the caller-owned builder must not affect the client.
            toolAdvisorBuilder.toolExecutionEligibilityChecker(response -> true);
            authenticate();

            ChatResponse response = client.prompt().user("Lookup").call().chatResponse();

            assertThat(response.hasToolCalls()).isTrue();
            assertThat(calls).hasValue(0);
            assertThat(this.modelRequests).hasSize(1);
        });
    }

    @Test
    @SuppressWarnings("removal")
    void preservesBootCustomizerOrderAndObservationConventions() throws IOException {
        startModelServer(finalResponse());
        List<String> customizerCalls = new CopyOnWriteArrayList<>();
        List<String> observationNames = new CopyOnWriteArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                observationNames.add(context.getName());
            }
        });
        ChatClientCustomizer legacyCustomizer = builder -> {
            customizerCalls.add("legacy");
            builder.defaultSystem("legacy system");
        };
        contextRunner()
                .withBean(ObservationRegistry.class, () -> registry)
                .withBean(ChatClientObservationConvention.class, () -> new DefaultChatClientObservationConvention() {
                    @Override
                    public String getName() {
                        return "review.client";
                    }
                })
                .withBean(AdvisorObservationConvention.class, () -> new DefaultAdvisorObservationConvention() {
                    @Override
                    public String getName() {
                        return "review.advisor";
                    }
                })
                .withBean("legacyCustomizer", ChatClientCustomizer.class, () -> legacyCustomizer)
                .withBean("laterCustomizer", ChatClientBuilderCustomizer.class,
                        () -> new OrderedCustomizer(20, "later", customizerCalls))
                .withBean("earlierCustomizer", ChatClientBuilderCustomizer.class,
                        () -> new OrderedCustomizer(10, "earlier", customizerCalls))
                .run(context -> {
                    ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                            .builder(context.getBean(OpenAiChatModel.class)).build();
                    String response = client.prompt(new Prompt("Hello")).call().content();

                    assertThat(response).isEqualTo("done");
                    assertThat(customizerCalls).containsExactly("legacy", "earlier", "later");
                    assertThat(this.modelRequests).singleElement().asString().contains("later system");
                    assertThat(observationNames).contains("review.client", "review.advisor");
                });
    }

    @Test
    void streamingRequestsUseReactiveAuthenticationAcrossThreadChanges() throws IOException {
        startModelServer(toolStreamResponse("customerLookup"), finalStreamResponse());
        AtomicInteger calls = new AtomicInteger();
        contextRunner().run(context -> {
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class))
                    .defaultTools(tool("customerLookup", calls)).build();
            SecurityContextHolder.clearContext();
            UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken
                    .authenticated("reactive-user", "unused", List.of());
            List<String> responseParts = client.prompt().user("Lookup").stream().content().collectList()
                    .contextWrite(ReactiveSecurityContextHolder
                            .withAuthentication(authentication))
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofSeconds(10));

            assertThat(responseParts).containsExactly("done");
            assertThat(calls).hasValue(1);
        });
    }

    @Test
    void requestsWithoutToolsCanDisableAutomaticRegistration() throws IOException {
        startModelServer(finalResponse());
        contextRunner().withPropertyValues("spring.ai.chat.client.tool-calling.enabled=false").run(context -> {
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class)).build();
            assertThat(client.prompt().user("Hello").call().content()).isEqualTo("done");
        });
    }

    @ParameterizedTest(name = "streaming={0}")
    @ValueSource(booleans = {false, true})
    void rejectsSeparatelyRegisteredPriorityToolAdvisorsBeforeTheirLoopStarts(boolean streaming) throws IOException {
        startModelServer(finalResponse());
        AtomicInteger toolLoopStarts = new AtomicInteger();
        contextRunner().run(context -> {
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class))
                    .defaultAdvisors(new PriorityToolAdvisor(toolLoopStarts))
                    .defaultTools(tool("customerLookup", new AtomicInteger())).build();
            authenticate();

            assertThatThrownBy(() -> executeRequest(client.prompt().user("Lookup"), streaming))
                    .isInstanceOf(AuthorizationDeniedException.class)
                    .hasMessageContaining("managed tool advisor");
            assertThat(toolLoopStarts).hasValue(0);
            assertThat(this.modelRequests).isEmpty();
        });
    }

    private static final class PriorityToolAdvisor extends ToolCallingAdvisor
            implements PriorityOrdered {
        private final AtomicInteger toolLoopStarts;

        PriorityToolAdvisor(AtomicInteger toolLoopStarts) {
            super(ToolCallingManager.builder().build(), response -> response != null && response.hasToolCalls(),
                    Ordered.HIGHEST_PRECEDENCE + 1, true);
            this.toolLoopStarts = toolLoopStarts;
        }

        @Override
        public ChatClientResponse adviseCall(
                ChatClientRequest request,
                CallAdvisorChain chain) {
            this.toolLoopStarts.incrementAndGet();
            return super.adviseCall(request, chain);
        }

        @Override
        public Flux<ChatClientResponse> adviseStream(
                ChatClientRequest request,
                StreamAdvisorChain chain) {
            this.toolLoopStarts.incrementAndGet();
            return super.adviseStream(request, chain);
        }
    }

    private record OrderedCustomizer(int order, String name, List<String> calls)
            implements ChatClientBuilderCustomizer, Ordered {
        @Override
        public int getOrder() {
            return this.order;
        }

        @Override
        public void customize(ChatClient.Builder builder) {
            this.calls.add(this.name);
            builder.defaultSystem(this.name + " system");
        }
    }
}

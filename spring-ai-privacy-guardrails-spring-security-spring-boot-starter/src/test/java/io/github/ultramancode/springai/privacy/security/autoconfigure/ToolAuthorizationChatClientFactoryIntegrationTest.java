package io.github.ultramancode.springai.privacy.security.autoconfigure;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.PriorityOrdered;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.advisor.observation.DefaultAdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.client.observation.DefaultChatClientObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.core.Ordered;
import org.springframework.security.authorization.AuthorizationDeniedException;

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

    @Test
    void rejectsForeignToolAdvisorsOnTheBuilderAndOnIndividualRequests() throws IOException {
        startModelServer(finalResponse());
        AtomicInteger calls = new AtomicInteger();
        contextRunner().run(context -> {
            var factory = context.getBean(ToolAuthorizationChatClientFactory.class);
            for (boolean streaming : new boolean[]{false, true}) {
                for (boolean requestLevel : new boolean[]{false, true}) {
                    ToolCallingAdvisor foreign = ToolCallingAdvisor.builder()
                            .toolCallingManager(context.getBean(ToolCallingManager.class))
                            .advisorOrder(Ordered.HIGHEST_PRECEDENCE + 1).build();
                    ChatClient.Builder builder = factory.builder(context.getBean(OpenAiChatModel.class))
                            .defaultTools(tool("customerLookup", calls));
                    if (!requestLevel) {
                        builder.defaultAdvisors(foreign);
                    }
                    ChatClient.ChatClientRequestSpec request = builder.build().prompt().user("Run lookup");
                    if (requestLevel) {
                        request.advisors(foreign);
                    }
                    authenticate();
                    assertThatThrownBy(() -> invoke(request, streaming))
                            .isInstanceOf(AuthorizationDeniedException.class)
                            .hasMessageContaining("managed tool advisor");
                }
            }
            assertThat(this.modelRequests).isEmpty();
            assertThat(calls).hasValue(0);
        });
    }

    @Test
    void rejectsToolSearchFromACustomizerBeforeItCanIndexDefinitions() throws IOException {
        startModelServer(finalResponse());
        ToolIndex index = mock(ToolIndex.class);
        contextRunner().withBean(ChatClientBuilderCustomizer.class, () -> builder ->
                builder.defaultAdvisors(ToolSearchToolCallingAdvisor.builder().toolIndex(index)
                        .advisorOrder(Ordered.HIGHEST_PRECEDENCE + 1)
                        .systemMessageSuffix("Search for tools before using them.").build()))
                .run(context -> {
                    ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                            .builder(context.getBean(OpenAiChatModel.class))
                            .defaultTools(tool("adminDelete", new AtomicInteger())).build();
                    authenticate();
                    assertThatThrownBy(() -> client.prompt().user("Find tool").call().content())
                            .isInstanceOf(AuthorizationDeniedException.class);
                    verifyNoInteractions(index);
                    assertThat(this.modelRequests).isEmpty();
                });
    }

    @Test
    void rejectsDisablingAutomaticRegistrationForProtectedTools() throws IOException {
        startModelServer(finalResponse());
        for (boolean property : new boolean[]{false, true}) {
            contextRunner().withPropertyValues("spring.ai.chat.client.tool-calling.enabled=" + !property)
                    .run(context -> {
                        ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                                .builder(context.getBean(OpenAiChatModel.class))
                                .defaultTools(tool("customerLookup", new AtomicInteger())).build();
                        for (boolean streaming : new boolean[]{false, true}) {
                            var request = client.prompt().user("Run lookup");
                            if (!property) {
                                request.advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false));
                            }
                            authenticate();
                            assertThatThrownBy(() -> invoke(request, streaming))
                                    .isInstanceOf(AuthorizationDeniedException.class);
                        }
                        assertThat(this.modelRequests).isEmpty();
                    });
        }
    }

    @Test
    void clonedAndMutatedClientsKeepTheSecuredAutomaticToolLoop() throws IOException {
        startModelServer(toolResponse("customerLookup"), finalResponse(),
                toolResponse("customerLookup"), finalResponse());
        AtomicInteger calls = new AtomicInteger();
        contextRunner().run(context -> {
            ChatClient.Builder builder = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class))
                    .defaultTools(tool("customerLookup", calls), tool("adminDelete", new AtomicInteger()));
            ChatClient clone = builder.clone().build();
            ChatClient mutated = builder.build().mutate().build();
            authenticate();
            assertThat(clone.prompt().user("Lookup").call().content()).isEqualTo("done");
            assertThat(mutated.prompt().user("Lookup").call().content()).isEqualTo("done");
            assertThat(calls).hasValue(2);
            assertThat(this.modelRequests).hasSize(4).allSatisfy(request ->
                    assertThat(request).doesNotContain("adminDelete"));
        });
    }

    @Test
    void preservesTheCopiedTemplatesExecutionChecker() throws IOException {
        startModelServer(toolResponse("customerLookup"));
        AtomicInteger calls = new AtomicInteger();
        contextRunner().run(context -> {
            ToolCallingAdvisor.Builder<?> template = ToolCallingAdvisor.builder()
                    .toolExecutionEligibilityChecker(response -> false);
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class), template)
                    .defaultTools(tool("customerLookup", calls)).build();
            // Later changes to the caller-owned builder must not affect the client.
            template.toolExecutionEligibilityChecker(response -> true);
            authenticate();
            assertThat(client.prompt().user("Lookup").call().chatResponse().hasToolCalls()).isTrue();
            assertThat(calls).hasValue(0);
            assertThat(this.modelRequests).hasSize(1);
        });
    }

    @Test
    @SuppressWarnings("removal")
    void preservesBootCustomizerOrderAndObservationConventions() throws IOException {
        startModelServer(finalResponse());
        List<String> customization = new CopyOnWriteArrayList<>();
        List<String> observations = new CopyOnWriteArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                observations.add(context.getName());
            }
        });
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
                .withBean("legacyCustomizer", ChatClientCustomizer.class, () -> builder -> {
                    customization.add("legacy");
                    builder.defaultSystem("legacy system");
                })
                .withBean("laterCustomizer", ChatClientBuilderCustomizer.class,
                        () -> new OrderedCustomizer(20, "later", customization))
                .withBean("earlierCustomizer", ChatClientBuilderCustomizer.class,
                        () -> new OrderedCustomizer(10, "earlier", customization))
                .run(context -> {
                    ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                            .builder(context.getBean(OpenAiChatModel.class)).build();
                    assertThat(client.prompt(new Prompt("Hello")).call().content()).isEqualTo("done");
                    assertThat(customization).containsExactly("legacy", "earlier", "later");
                    assertThat(this.modelRequests).singleElement().asString().contains("later system");
                    assertThat(observations).contains("review.client", "review.advisor");
                });
    }

    @Test
    void streamingFactoryUsesReactiveAuthenticationAcrossThreadChanges() throws IOException {
        startModelServer(toolStreamResponse("customerLookup"), finalStreamResponse());
        AtomicInteger calls = new AtomicInteger();
        contextRunner().run(context -> {
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class))
                    .defaultTools(tool("customerLookup", calls)).build();
            SecurityContextHolder.clearContext();
            var authentication = UsernamePasswordAuthenticationToken
                    .authenticated("reactive-user", "unused", List.of());
            assertThat(client.prompt().user("Lookup").stream().content().collectList()
                    .contextWrite(ReactiveSecurityContextHolder
                            .withAuthentication(authentication))
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofSeconds(10))).containsExactly("done");
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

    @Test
    void rejectsPriorityOrderedToolAdvisorsBeforeTheirLoopStarts() throws IOException {
        startModelServer(finalResponse());
        contextRunner().run(context -> {
            for (boolean streaming : new boolean[]{false, true}) {
                AtomicInteger toolLoopStarts = new AtomicInteger();
                ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                        .builder(context.getBean(OpenAiChatModel.class))
                        .defaultAdvisors(new PriorityToolAdvisor(toolLoopStarts))
                        .defaultTools(tool("customerLookup", new AtomicInteger())).build();
                authenticate();
                assertThatThrownBy(() -> invoke(client.prompt().user("Lookup"), streaming))
                        .isInstanceOf(AuthorizationDeniedException.class);
                assertThat(toolLoopStarts).hasValue(0);
            }
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

    private static String invoke(ChatClient.ChatClientRequestSpec request, boolean streaming) {
        return streaming ? request.stream().content().collectList().map(parts -> String.join("", parts))
                .block(Duration.ofSeconds(10)) : request.call().content();
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

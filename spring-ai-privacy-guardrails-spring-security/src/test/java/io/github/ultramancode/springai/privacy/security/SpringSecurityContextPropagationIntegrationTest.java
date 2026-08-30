package io.github.ultramancode.springai.privacy.security;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.ResolvingToolLoopModel;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.authentication;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.boundary;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.privacyFactory;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.privacyService;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.securedClient;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.tool;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.useAuthentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringSecurityContextPropagationIntegrationTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void carriesReactiveSecurityContextAcrossStreamingToolExecution() {
        SpringSecurityToolBoundary boundary = boundary((authentication, context) ->
                new AuthorizationDecision(authentication.get().getName().equals("reactive-user")));
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        AtomicReference<String> input = new AtomicReference<>();
        ToolCallback tool = factory.wrap(tool("customerLookup", input::set));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, tool);
        SecurityContextHolder.clearContext();

        String result = chatClient.prompt()
                .user("Find Alice")
                .stream()
                .content()
                .collectList()
                .map(parts -> String.join("", parts))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        authentication("reactive-user")
                ))
                .block();

        assertThat(result).isEqualTo("done");
        assertThat(input.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void prefersReactiveSecurityContextOverThreadLocalContextForStreaming() {
        SpringSecurityToolBoundary boundary = boundary((authentication, context) ->
                new AuthorizationDecision(authentication.get().getName().equals("reactive-user")));
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        AtomicReference<String> input = new AtomicReference<>();
        ToolCallback tool = factory.wrap(tool("customerLookup", input::set));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, tool);
        useAuthentication(authentication("thread-user"));

        String result = chatClient.prompt()
                .user("Find Alice")
                .stream()
                .content()
                .collectList()
                .map(parts -> String.join("", parts))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        authentication("reactive-user")
                ))
                .block();

        assertThat(result).isEqualTo("done");
        assertThat(input.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void rejectsAnEmptyReactiveSecurityContextInsteadOfUsingThreadLocalAuthentication() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        ToolCallback tool = factory.wrap(tool("customerLookup", ignored -> {
        }));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, tool);
        useAuthentication(authentication("thread-user"));
        SecurityContext emptyReactiveContext = SecurityContextHolder.createEmptyContext();

        assertThatThrownBy(() -> chatClient.prompt()
                .user("Find Alice")
                .stream()
                .content()
                .collectList()
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(emptyReactiveContext)
                ))
                .block())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("Tool authorization requires an Authentication");
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void rejectsAnEmptyReactiveSecurityContextPublisherInsteadOfUsingThreadLocalAuthentication() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        AtomicInteger toolCalls = new AtomicInteger();
        ToolCallback tool = factory.wrap(tool("customerLookup", ignored ->
                toolCalls.incrementAndGet()));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, tool);
        useAuthentication(authentication("thread-user"));

        assertThatThrownBy(() -> chatClient.prompt()
                .user("Find Alice")
                .stream()
                .content()
                .collectList()
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.empty()))
                .block())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("Tool authorization requires an Authentication");
        assertThat(toolCalls).hasValue(0);
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void releasesTheAuthorizationSessionWhenAStreamIsCancelled() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        ChatClient chatClient = ChatClient.builder(new NeverStreamingModel())
                .defaultAdvisors(boundary.advisor())
                .defaultTools(tool("customerLookup", ignored -> {
                }))
                .build();
        useAuthentication(authentication("stream-user"));

        Disposable subscription = chatClient.prompt()
                .user("Keep streaming")
                .stream()
                .content()
                .subscribe();

        assertThat(boundary.activeSessionCount()).isOne();
        subscription.dispose();
        assertThat(boundary.activeSessionCount()).isZero();
    }

    @Test
    void failsClosedWhenNoSecurityContextIsAvailable() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        ToolCallback tool = factory.wrap(tool("customerLookup", ignored -> {
        }));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, tool);
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("Tool authorization requires an Authentication");
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void requiresExplicitSecurityContextPropagationAcrossAnExecutorBoundary() throws Exception {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(
                        authentication.get().getName().equals("executor-user")
                )
        );
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        ToolCallback tool = factory.wrap(tool("customerLookup", ignored -> {
        }));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, tool);
        useAuthentication(authentication("executor-user"));
        ExecutorService rawExecutor = Executors.newSingleThreadExecutor();
        ExecutorService propagatedExecutor = new DelegatingSecurityContextExecutorService(
                Executors.newSingleThreadExecutor()
        );

        try {
            Future<String> unpropagated = rawExecutor.submit(
                    () -> chatClient.prompt().user("Find Alice").call().content()
            );
            assertThatThrownBy(() -> unpropagated.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(AuthorizationDeniedException.class)
                    .rootCause()
                    .hasMessage("Tool authorization requires an Authentication");

            Future<String> propagated = propagatedExecutor.submit(
                    () -> chatClient.prompt().user("Find Alice").call().content()
            );
            assertThat(propagated.get(5, TimeUnit.SECONDS)).isEqualTo("done");
        }
        finally {
            rawExecutor.shutdownNow();
            propagatedExecutor.shutdownNow();
        }

        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void supportsExplicitSecurityContextPropagationOnVirtualThreads() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 21);
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(
                        authentication.get().getName().equals("virtual-user")
                )
        );
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        ToolCallback tool = factory.wrap(tool("customerLookup", ignored -> {
        }));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, tool);
        useAuthentication(authentication("virtual-user"));
        ExecutorService rawVirtualExecutor = newVirtualThreadPerTaskExecutor();
        ExecutorService propagatedVirtualExecutor = new DelegatingSecurityContextExecutorService(
                newVirtualThreadPerTaskExecutor()
        );

        try {
            Future<String> unpropagated = rawVirtualExecutor.submit(
                    () -> chatClient.prompt().user("Find Alice").call().content()
            );
            assertThatThrownBy(() -> unpropagated.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(AuthorizationDeniedException.class)
                    .rootCause()
                    .hasMessage("Tool authorization requires an Authentication");

            Future<String> propagated = propagatedVirtualExecutor.submit(
                    () -> chatClient.prompt().user("Find Alice").call().content()
            );
            assertThat(propagated.get(5, TimeUnit.SECONDS)).isEqualTo("done");
        }
        finally {
            rawVirtualExecutor.shutdownNow();
            propagatedVirtualExecutor.shutdownNow();
        }

        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    private static ExecutorService newVirtualThreadPerTaskExecutor() throws Exception {
        return (ExecutorService) Executors.class
                .getMethod("newVirtualThreadPerTaskExecutor")
                .invoke(null);
    }

    private static final class NeverStreamingModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new AssertionError("Streaming invocation was expected");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.never();
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}

package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class PrivacyToolContextAdvisorTest {

    @Test
    void defaultOrderRunsImmediatelyBeforeSpringToolCallingAdvisor() {
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(
                TestPrivacyServices.privacyService()
        );

        assertThat(advisor.getOrder()).isEqualTo(ToolCallingAdvisor.DEFAULT_ORDER - 1);
        assertThat(new PrivacyToolContextAdvisor(TestPrivacyServices.privacyService(), 123).getOrder())
                .isEqualTo(123);
    }

    @Test
    void adviseCallAttachesHandleToTheCurrentDynamicToolOptions() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(invocation -> {
            ChatClientRequest request = invocation.getArgument(0);
            ToolCallingChatOptions options = (ToolCallingChatOptions) request.prompt().getOptions();
            assertThat(options.getToolContext()).containsEntry("tenant", "acme");
            assertThat(options.getToolContext().get(PrivacyRequestContextSupport.CONTEXT_HANDLE))
                    .isInstanceOf(PrivacyContextHandle.class);
            return TestPrivacyServices.response("ok");
        });

        try (PrivacySession session = service.openSession()) {
            advisor.adviseCall(dynamicToolRequest(session.handle()), chain);
        }
    }

    @Test
    void adviseStreamAttachesHandleBeforeDelegating() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenAnswer(invocation -> {
            ChatClientRequest request = invocation.getArgument(0);
            ToolCallingChatOptions options = (ToolCallingChatOptions) request.prompt().getOptions();
            assertThat(options.getToolContext())
                    .containsKey(PrivacyRequestContextSupport.CONTEXT_HANDLE);
            return Flux.just(TestPrivacyServices.response("ok"));
        });

        try (PrivacySession session = service.openSession()) {
            List<ChatClientResponse> responses = advisor.adviseStream(
                    dynamicToolRequest(session.handle()),
                    chain
            ).collectList().block();

            assertThat(responses).hasSize(1);
        }
    }

    @Test
    void rawToolCallbackFailsClosedBeforeCallPropagation() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> advisor.adviseCall(
                    dynamicToolRequest(session.handle(), tool("rawTool")),
                    chain
            )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                assertThat(failure).hasMessage(
                        "PrivacyToolContextAdvisor rejected a tool callback outside the privacy boundary"
                );
            });
        }
        verify(chain, never()).nextCall(any());
    }

    @Test
    void rawToolCallbackFailsClosedBeforeStreamPropagation() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> advisor.adviseStream(
                    dynamicToolRequest(session.handle(), tool("rawTool")),
                    chain
            )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure ->
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT));
        }
        verify(chain, never()).nextStream(any());
    }

    @Test
    void privacyWrappedToolCallbackIsAttachedAndPropagated() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(service);
        ToolCallback wrapped = new PrivacyToolCallbackFactory(service, ToolDisclosurePolicy.denyAll())
                .wrap(tool("safeTool"));
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(TestPrivacyServices.response("ok"));

        try (PrivacySession session = service.openSession()) {
            assertThat(advisor.adviseCall(
                    dynamicToolRequest(session.handle(), wrapped),
                    chain
            )).isNotNull();
        }
        verify(chain).nextCall(any());
    }

    @Test
    void duplicateWrappedToolNamesFailClosedAtTheRuntimeRegistrationBoundary() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> advisor.adviseCall(
                    dynamicToolRequest(
                            session.handle(),
                            factory.wrap(tool("duplicate")),
                            factory.wrap(tool("duplicate"))
                    ),
                    chain
            ))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("PrivacyToolContextAdvisor rejected duplicate tool callback names");
        }
        verify(chain, never()).nextCall(any());
    }

    @Test
    void mixedReturnDirectToolsAreAcceptedAtTheRegistrationBoundary() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(TestPrivacyServices.response("ok"));

        try (PrivacySession session = service.openSession()) {
            assertThat(advisor.adviseCall(
                    dynamicToolRequest(
                            session.handle(),
                            factory.wrap(tool("normal")),
                            factory.wrap(tool("direct", true))
                    ),
                    chain
            )).isNotNull();
        }
        verify(chain).nextCall(any());
    }

    @Test
    void wrapperBoundToAnotherPrivacyServiceFailsClosed() {
        PrivacyService expectedService = TestPrivacyServices.privacyService();
        PrivacyService foreignService = TestPrivacyServices.privacyService();
        ToolCallback foreign = new PrivacyToolCallbackFactory(
                foreignService,
                ToolDisclosurePolicy.denyAll()
        ).wrap(tool("safeTool"));
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(expectedService);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = expectedService.openSession()) {
            assertThatThrownBy(() -> advisor.adviseCall(
                    dynamicToolRequest(session.handle(), foreign),
                    chain
            ))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("PrivacyToolContextAdvisor rejected a tool callback bound to another privacy service");
        }
        verify(chain, never()).nextCall(any());
    }

    @Test
    void optionalFactoryProvenanceIsStrictOnlyWhenConfigured() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory expectedFactory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyToolCallbackFactory otherFactory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        ToolCallback other = otherFactory.wrap(tool("safeTool"));
        CallAdvisorChain strictChain = mock(CallAdvisorChain.class);
        CallAdvisorChain directChain = mock(CallAdvisorChain.class);
        when(directChain.nextCall(any())).thenReturn(TestPrivacyServices.response("ok"));

        try (PrivacySession session = service.openSession()) {
            PrivacyToolContextAdvisor strict = new PrivacyToolContextAdvisor(service, expectedFactory);
            assertThatThrownBy(() -> strict.adviseCall(
                    dynamicToolRequest(session.handle(), other),
                    strictChain
            ))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("PrivacyToolContextAdvisor rejected a tool callback from another privacy factory");

            PrivacyToolContextAdvisor direct = new PrivacyToolContextAdvisor(service);
            assertThat(direct.adviseCall(dynamicToolRequest(session.handle(), other), directChain)).isNotNull();
        }
        verify(strictChain, never()).nextCall(any());
        verify(directChain).nextCall(any());
    }

    @Test
    void missingInputSessionFailsClosedBeforeDelegating() {
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(
                TestPrivacyServices.privacyService()
        );
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        assertThatThrownBy(() -> advisor.adviseCall(
                new ChatClientRequest(new Prompt("hello"), Map.of()),
                chain
        )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure ->
                assertThat(failure.code()).isEqualTo(PrivacyFailureCode.CONTEXT_REQUIRED));
        verify(chain, never()).nextCall(any());
    }

    @Test
    void toolAdvisorOrderIsApplicationOwned() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ToolCallingAdvisor unsafe = mock(ToolCallingAdvisor.class);
        when(unsafe.getOrder()).thenReturn(PrivacyToolContextAdvisor.DEFAULT_ORDER);
        when(chain.getCallAdvisors()).thenReturn(List.of(unsafe));
        when(chain.nextCall(any())).thenReturn(TestPrivacyServices.response("ok"));

        try (PrivacySession session = service.openSession()) {
            assertThat(advisor.adviseCall(dynamicToolRequest(session.handle()), chain)).isNotNull();
        }
        verify(chain).nextCall(any());
    }

    @Test
    void differentToolAdvisorImplementationIsAllowed() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        CallAdvisor unsupported = mock(
                CallAdvisor.class,
                withSettings().extraInterfaces(ToolAdvisor.class)
        );
        when(unsupported.getOrder()).thenReturn(ToolCallingAdvisor.DEFAULT_ORDER);
        when(chain.getCallAdvisors()).thenReturn(List.of(unsupported));
        when(chain.nextCall(any())).thenReturn(TestPrivacyServices.response("ok"));

        try (PrivacySession session = service.openSession()) {
            assertThat(advisor.adviseCall(dynamicToolRequest(session.handle()), chain)).isNotNull();
        }
        verify(chain).nextCall(any());
    }

    @Test
    void toolCallingAdvisorSubclassIsAllowed() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ToolCallingAdvisor unsupportedSubclass = new ToolCallingAdvisor(
                ToolCallingManager.builder().build(),
                response -> response != null && response.hasToolCalls(),
                ToolCallingAdvisor.DEFAULT_ORDER,
                true
        ) { };
        when(chain.getCallAdvisors()).thenReturn(List.of(
                unsupportedSubclass,
                new PrivacyToolCallValidationAdvisor(service)
        ));
        when(chain.nextCall(any())).thenReturn(TestPrivacyServices.response("ok"));

        try (PrivacySession session = service.openSession()) {
            assertThat(advisor.adviseCall(dynamicToolRequest(session.handle()), chain)).isNotNull();
        }
        verify(chain).nextCall(any());
    }

    @Test
    void largeToolRegistrationRemainsSupported() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        List<ToolCallback> callbacks = IntStream.range(0, 300)
                .mapToObj(index -> factory.wrap(tool("tool" + index)))
                .toList();
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .build();
        ChatClientRequest request = new ChatClientRequest(new Prompt("hello", options), Map.of());

        assertThat(PrivacyToolContextAdvisor.requirePrivacyWrappedToolNames(
                request,
                service,
                factory.provenance()
        )).hasSize(callbacks.size());
    }

    @Test
    void retainsTheToolSearchControlCallbackIdentityAcrossIterations() {
        PrivacyService service = TestPrivacyServices.privacyService();
        ToolCallback declared = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        ).wrap(tool("customerLookup"));
        ToolCallingChatOptions initialOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(declared))
                .build();
        ChatClientRequest validated = PrivacyToolExecutionContextSupport
                .attachValidatedToolCallbackSnapshot(new ChatClientRequest(
                        new Prompt("hello", initialOptions),
                        Map.of()
                ));
        ToolCallback control = tool("toolSearchTool");
        ChatClientRequest toolSearchIteration = toolSearchIteration(validated, control);

        PrivacyToolExecutionContextSupport.requireCallbacksMatchValidatedSnapshot(
                toolSearchIteration
        );

        ToolCallback replacement = tool("toolSearchTool");
        assertThatThrownBy(() -> PrivacyToolExecutionContextSupport
                .requireCallbacksMatchValidatedSnapshot(
                        toolSearchIteration(toolSearchIteration, replacement)
                ))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessage("Tool callbacks changed after the privacy tool-context boundary");
    }

    @Test
    void applicationAdvisorBetweenToolCallingAndExecutionBoundaryIsAllowed() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        CallAdvisor mutatingAdvisor = mock(CallAdvisor.class);
        when(mutatingAdvisor.getOrder()).thenReturn(PrivacyToolCallValidationAdvisor.DEFAULT_ORDER);
        when(chain.getCallAdvisors()).thenReturn(List.of(
                ToolCallingAdvisor.builder().build(),
                mutatingAdvisor,
                new PrivacyToolCallValidationAdvisor(service)
        ));
        when(chain.nextCall(any())).thenReturn(TestPrivacyServices.response("ok"));

        try (PrivacySession session = service.openSession()) {
            assertThat(advisor.adviseCall(dynamicToolRequest(session.handle()), chain)).isNotNull();
        }
        verify(chain).nextCall(any());
    }

    @Test
    void structuredOutputValidationMayRunInsideExecutionBoundary() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolContextAdvisor advisor = new PrivacyToolContextAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        StructuredOutputValidationAdvisor structured = StructuredOutputValidationAdvisor.builder()
                .outputJsonSchema("{\"type\":\"object\"}")
                .build();
        when(chain.getCallAdvisors()).thenReturn(List.of(
                ToolCallingAdvisor.builder().build(),
                new PrivacyToolCallValidationAdvisor(service),
                structured
        ));
        when(chain.nextCall(any())).thenReturn(TestPrivacyServices.response("ok"));

        try (PrivacySession session = service.openSession()) {
            assertThat(advisor.adviseCall(dynamicToolRequest(session.handle()), chain)).isNotNull();
        }
        verify(chain).nextCall(any());
    }

    private ChatClientRequest dynamicToolRequest(PrivacyContextHandle handle) {
        return dynamicToolRequest(handle, new ToolCallback[0]);
    }

    private ChatClientRequest dynamicToolRequest(
            PrivacyContextHandle handle,
            ToolCallback... callbacks
    ) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .toolContext("tenant", "acme")
                .build();
        return new ChatClientRequest(
                new Prompt("hello", options),
                Map.of(PrivacyRequestContextSupport.CONTEXT_HANDLE, handle)
        );
    }

    private ChatClientRequest toolSearchIteration(
            ChatClientRequest request,
            ToolCallback callback
    ) {
        ToolCallingChatOptions options = ((ToolCallingChatOptions) request.prompt().getOptions())
                .mutate()
                .toolCallbacks(List.of(callback))
                .toolContext("toolSearchToolSessionId", "test-session")
                .build();
        return request.mutate()
                .prompt(new Prompt(request.prompt().getInstructions(), options))
                .build();
    }

    private ToolCallback tool(String name) {
        return tool(name, false);
    }

    private ToolCallback tool(String name, boolean returnDirect) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(returnDirect).build();
            }
        };
    }
}

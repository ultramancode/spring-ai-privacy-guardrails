package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyModelRequestStageTest {


    @Test
    void stageTokenizesContentAddedByDownstreamRagAdvisorBeforeModelCall() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyModelRequestStage stage = new PrivacyModelRequestStage(service, null, PrivacyEnforcementObserver.noop());

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(
                    new ChatClientRequest(
                            new Prompt(List.of(new UserMessage("Retrieved customer: Alice"))),
                            Map.of("rag", true)
                    ),
                    session.handle()
            );
            ChatClientRequest protectedRequest = stage.apply(request);
            assertThat(PrivacyModelRequestStage.isModelContentProtected(protectedRequest)).isTrue();
            ChatClientRequest changed = protectedRequest.mutate().prompt(new Prompt("Alice added later")).build();
            assertThat(PrivacyModelRequestStage.isModelContentProtected(changed)).isFalse();
            assertThat(PrivacyRequestContextSupport.stripInternalPrivacyEntries(protectedRequest.context()))
                    .doesNotContainKey(PrivacyModelRequestStage.MODEL_CONTENT_PROTECTION);
            ChatClientResponse markedResponse = new ChatClientResponse(
                    TestPrivacyServices.response("ok").chatResponse(), protectedRequest.context());
            assertThat(PrivacyRequestContextSupport.stripInternalPrivacyEntries(markedResponse).context())
                    .doesNotContainKey(PrivacyModelRequestStage.MODEL_CONTENT_PROTECTION);
            String text = protectedRequest.prompt().getUserMessage().getText();
            assertThat(text).doesNotContain("Alice");
            assertThat(service.detokenize(session.handle(), text))
                    .isEqualTo("Retrieved customer: Alice");


        }
    }

    @Test
    void stageFailsClosedWhenInputSessionIsMissing() {
        PrivacyModelRequestStage stage = new PrivacyModelRequestStage(TestPrivacyServices.privacyService(), null, PrivacyEnforcementObserver.noop());
        ChatClientRequest request = new ChatClientRequest(new Prompt("Alice"), Map.of());

        assertThatThrownBy(() -> stage.apply(request))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("PrivacyLifecycleAdvisor");
    }


    @Test
    void finalBoundaryRejectsLateToolReplacementFromAnotherFactory() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory expectedFactory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyToolCallbackFactory otherFactory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyModelRequestStage stage = new PrivacyModelRequestStage(service, expectedFactory, PrivacyEnforcementObserver.noop());
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(otherFactory.wrap(tool("customerLookup"))))
                .build();

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(
                    new ChatClientRequest(new Prompt("hello", options), Map.of()),
                    session.handle()
            );

            assertThatThrownBy(() -> stage.apply(request))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("PrivacyToolContextAdvisor rejected a tool callback from another privacy factory");
        }
    }

    @Test
    void finalBoundaryRejectsLateCallbackReplacementFromTheSameFactory() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyModelRequestStage stage = new PrivacyModelRequestStage(service, factory, PrivacyEnforcementObserver.noop());
        ToolCallingChatOptions originalOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(factory.wrap(tool("customerLookup"))))
                .build();
        ToolCallingChatOptions replacementOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(factory.wrap(tool("replacementLookup"))))
                .build();

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest snapshotted = activeRequest(
                    new ChatClientRequest(new Prompt("hello", originalOptions), Map.of()),
                    session.handle()
            );
            ChatClientRequest replaced = snapshotted.mutate()
                    .prompt(new Prompt(snapshotted.prompt().getInstructions(), replacementOptions))
                    .build();

            assertThatThrownBy(() -> stage.apply(replaced))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("Tool callbacks changed after the privacy tool-context boundary");
        }
    }

    @Test
    void stageRejectsPiiInHistoricalToolResponseNameBeforeModelCall() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyModelRequestStage stage = new PrivacyModelRequestStage(service, null, PrivacyEnforcementObserver.noop());
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "Alice", "safe result"
                )))
                .build();

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(
                    new ChatClientRequest(new Prompt(List.of(response)), Map.of()),
                    session.handle()
            );
            assertThatThrownBy(() -> stage.apply(request))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("Tool control field rejected by privacy guardrail")
                    .hasMessageNotContaining("Alice");
        }
    }

    @Test
    void stageRejectsPiiInModelVisibleToolDefinitionsBeforeModelCall() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyModelRequestStage stage = new PrivacyModelRequestStage(service, null, PrivacyEnforcementObserver.noop());
        ToolCallback callback = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("customerLookup")
                        .description("Looks up Alice")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String input) {
                return "ok";
            }
        };
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(factory.wrap(callback)))
                .build();

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(
                    new ChatClientRequest(new Prompt("hello", options), Map.of()),
                    session.handle()
            );

            assertThatThrownBy(() -> stage.apply(request))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("Tool definition rejected by privacy guardrail")
                    .hasMessageNotContaining("Alice");
        }
    }

    @Test
    void stageRejectsNonblankMalformedJsonSchemasBeforeModelCall() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyModelRequestStage stage = new PrivacyModelRequestStage(service, null, PrivacyEnforcementObserver.noop());
        ToolCallingChatOptions objectShapedToolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(factory.wrap(tool("customerLookup", "{not-json"))))
                .build();
        ToolCallingChatOptions plainToolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(factory.wrap(tool("customerLookup", "not-json"))))
                .build();

        try (PrivacySession session = service.openSession()) {
            for (ChatClientRequest request : List.of(
                    new ChatClientRequest(new Prompt("hello", objectShapedToolOptions), Map.of()),
                    new ChatClientRequest(new Prompt("hello", plainToolOptions), Map.of()),
                    new ChatClientRequest(
                            new Prompt("hello"),
                            Map.of(
                                    ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey(),
                                    "{not-json"
                            )
                    ),
                    new ChatClientRequest(
                            new Prompt("hello"),
                            Map.of(
                                    ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey(),
                                    "not-json"
                            )
                    )
            )) {
                ChatClientRequest activeRequest = activeRequest(request, session.handle());

                assertThatThrownBy(() -> stage.apply(activeRequest))
                        .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                            assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                            assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOKENIZATION);
                            assertThat(failure).hasMessage("Structured JSON payload is invalid");
                        });
            }
        }
    }

    @Test
    void stageRejectsLateStructuredOutputContextBeforeModelCall() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyModelRequestStage stage = new PrivacyModelRequestStage(service, null, PrivacyEnforcementObserver.noop());

        try (PrivacySession session = service.openSession()) {
            for (Map.Entry<String, String> augmentation : Map.of(
                    ChatClientAttributes.OUTPUT_FORMAT.getKey(), "Return Alice",
                    ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey(),
                    "{\"description\":\"Alice\"}"
            ).entrySet()) {
                ChatClientRequest request = activeRequest(
                        new ChatClientRequest(
                                new Prompt("hello"),
                                Map.of(augmentation.getKey(), augmentation.getValue())
                        ),
                        session.handle()
                );

                assertThatThrownBy(() -> stage.apply(request))
                        .isInstanceOf(PrivacyGuardrailException.class)
                        .hasMessage("Terminal model augmentation rejected by privacy guardrail")
                        .hasMessageNotContaining("Alice");
            }
        }
    }




    private ChatClientRequest toolRequest(
            PrivacyService service,
            PrivacySession session,
            String text
    ) {
        ToolCallback callback = tool("customerLookup");
        ToolCallback wrapped = new PrivacyToolCallbackFactory(service, ToolDisclosurePolicy.denyAll())
                .wrap(callback);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(wrapped))
                .build();
        return activeRequest(
                new ChatClientRequest(new Prompt(List.of(new UserMessage(text)), options), Map.of()),
                session.handle()
        );
    }

    private ChatClientRequest activeRequest(
            ChatClientRequest request,
            PrivacyContextHandle handle
    ) {
        return PrivacyToolExecutionContextSupport.attachValidatedToolCallbackSnapshot(
                PrivacyRequestContextSupport.attachLifecycle(request, handle)
        );
    }

    private ToolCallback tool(String name) {
        return tool(name, "{}");
    }

    private ToolCallback tool(String name, String inputSchema) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("lookup")
                        .inputSchema(inputSchema)
                        .build();
            }

            @Override
            public String call(String input) {
                return "ok";
            }
        };
    }

}

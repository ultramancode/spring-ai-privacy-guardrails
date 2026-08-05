package io.github.ultramancode.springai.privacy.test;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the actual model and delegate-tool boundaries exercised by a Spring AI test,
 * including model-visible tool definitions and tool routing control fields.
 * Instances retain captured text until {@link #clear()} or {@link #close()} is called and must never be
 * registered as production application components.
 */
public final class PrivacyTestProbe implements AutoCloseable {

    // Keep test support loadable when the optional DeepSeek module is absent.
    private static final String DEEPSEEK_ASSISTANT_MESSAGE_CLASS_NAME =
            "org.springframework.ai.deepseek.DeepSeekAssistantMessage";

    private final PrivacyService privacyService;
    private final List<ModelRequestSnapshot> modelRequests = new CopyOnWriteArrayList<>();
    private final List<ToolCallSnapshot> toolCalls = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();
    private boolean closed;

    private PrivacyTestProbe(PrivacyService privacyService) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
    }

    /**
     * Creates an empty test-only recorder bound to one privacy service.
     *
     * @param privacyService service used by the system under test
     * @return a new empty probe
     */
    public static PrivacyTestProbe create(PrivacyService privacyService) {
        return new PrivacyTestProbe(privacyService);
    }

    /**
     * Wraps the model so the probe observes exactly the textual content sent to it.
     *
     * @param delegate unwrapped model used by the test
     * @return recording model wrapper
     */
    public ChatModel wrapModel(ChatModel delegate) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        if (delegate instanceof RecordingChatModel) {
            throw new IllegalArgumentException("delegate must be an unwrapped chat model");
        }
        return new RecordingChatModel(delegate, this);
    }

    /**
     * Wraps a raw delegate tool in the correct order for privacy boundary testing.
     * The recording wrapper is placed inside the privacy wrapper, so snapshots show
     * what the delegate actually receives and returns.
     *
     * @param delegate original unwrapped tool callback
     * @param toolCallbackFactory privacy factory used by the system under test
     * @return callback that records inside the privacy tool boundary
     */
    public ToolCallback wrapTool(ToolCallback delegate, PrivacyToolCallbackFactory toolCallbackFactory) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        Objects.requireNonNull(toolCallbackFactory, "toolCallbackFactory must not be null");
        if (toolCallbackFactory.isWrapped(delegate)) {
            throw new IllegalArgumentException("delegate must be an unwrapped tool callback");
        }
        return toolCallbackFactory.wrap(new RecordingToolCallback(delegate, this));
    }

    /**
     * Returns a point-in-time immutable copy of captured model requests.
     *
     * @return captured model requests in invocation order
     */
    public List<ModelRequestSnapshot> modelRequests() {
        return List.copyOf(this.modelRequests);
    }

    /**
     * Returns a point-in-time immutable copy of captured delegate tool calls.
     *
     * @return captured tool calls in delegate completion order
     */
    public List<ToolCallSnapshot> toolCalls() {
        return List.copyOf(this.toolCalls);
    }

    int activePrivacySessionCount() {
        return this.privacyService.activeSessionCount();
    }

    /** Removes every captured model and tool value from this probe. */
    public void clear() {
        synchronized (this.lifecycleLock) {
            this.modelRequests.clear();
            this.toolCalls.clear();
        }
    }

    /** Removes captured values when used with try-with-resources. */
    @Override
    public void close() {
        synchronized (this.lifecycleLock) {
            this.closed = true;
            this.modelRequests.clear();
            this.toolCalls.clear();
        }
    }

    private void recordModelRequest(ModelRequestSnapshot.Invocation invocation, Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        List<String> content = new ArrayList<>();
        List<ToolDefinitionSnapshot> toolDefinitions = new ArrayList<>();
        List<ToolControlFieldSnapshot> toolControlFields = new ArrayList<>();
        if (prompt.getOptions() instanceof ToolCallingChatOptions toolOptions
                && toolOptions.getToolCallbacks() != null) {
            for (ToolCallback callback : toolOptions.getToolCallbacks()) {
                ToolDefinition definition = callback.getToolDefinition();
                toolDefinitions.add(new ToolDefinitionSnapshot(
                        definition.name(),
                        definition.description(),
                        definition.inputSchema()
                ));
            }
        }
        for (Message message : prompt.getInstructions()) {
            addIfPresent(content, message.getText());
            if (message instanceof AssistantMessage assistantMessage) {
                addSupportedReasoningContent(content, assistantMessage);
                assistantMessage.getToolCalls().forEach(toolCall -> {
                    addIfPresent(content, toolCall.arguments());
                    toolControlFields.add(new ToolControlFieldSnapshot(
                            ToolControlFieldSnapshot.Source.ASSISTANT_TOOL_CALL,
                            toolCall.id(),
                            toolCall.type(),
                            toolCall.name()
                    ));
                });
            }
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                toolResponseMessage.getResponses().forEach(toolResponse -> {
                    addIfPresent(content, toolResponse.responseData());
                    toolControlFields.add(new ToolControlFieldSnapshot(
                            ToolControlFieldSnapshot.Source.TOOL_RESPONSE,
                            toolResponse.id(),
                            null,
                            toolResponse.name()
                    ));
                });
            }
        }
        synchronized (this.lifecycleLock) {
            if (!this.closed) {
                this.modelRequests.add(new ModelRequestSnapshot(
                        invocation,
                        content,
                        toolDefinitions,
                        toolControlFields
                ));
            }
        }
    }

    private static void addIfPresent(List<String> content, String value) {
        if (value != null) {
            content.add(value);
        }
    }

    private static void addSupportedReasoningContent(
            List<String> content,
            AssistantMessage assistantMessage
    ) {
        Map<String, Object> metadata = assistantMessage.getMetadata();
        addStringMetadata(content, metadata, "reasoningContent");
        addStringMetadata(content, metadata, "thinking");
        if (!assistantMessage.getClass().getName().equals(DEEPSEEK_ASSISTANT_MESSAGE_CLASS_NAME)) {
            return;
        }
        try {
            Object reasoningContent = assistantMessage.getClass()
                    .getMethod("getReasoningContent")
                    .invoke(assistantMessage);
            if (reasoningContent instanceof String text) {
                content.add(text);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Could not inspect supported DeepSeek reasoning content",
                    failure
            );
        }
    }

    private static void addStringMetadata(
            List<String> content,
            Map<String, Object> metadata,
            String key
    ) {
        Object value = metadata.get(key);
        if (value instanceof String text) {
            content.add(text);
        }
    }

    private void recordToolCall(String toolName, String input, String output, Throwable failure) {
        synchronized (this.lifecycleLock) {
            if (!this.closed) {
                this.toolCalls.add(new ToolCallSnapshot(
                        toolName,
                        input,
                        output,
                        failure == null ? null : failure.getClass().getName()
                ));
            }
        }
    }

    private record RecordingChatModel(ChatModel delegate, PrivacyTestProbe probe) implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            this.probe.recordModelRequest(ModelRequestSnapshot.Invocation.CALL, prompt);
            return this.delegate.call(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> {
                this.probe.recordModelRequest(ModelRequestSnapshot.Invocation.STREAM, prompt);
                return this.delegate.stream(prompt);
            });
        }

        @Override
        public ChatOptions getOptions() {
            return this.delegate.getOptions();
        }
    }

    private static final class RecordingToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final PrivacyTestProbe probe;
        private final ToolDefinition toolDefinition;
        private final ToolMetadata toolMetadata;
        private final String toolName;

        private RecordingToolCallback(ToolCallback delegate, PrivacyTestProbe probe) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
            this.probe = Objects.requireNonNull(probe, "probe must not be null");
            ToolDefinition definition = this.delegate.getToolDefinition();
            if (definition == null) {
                this.toolDefinition = null;
                this.toolName = null;
            } else {
                String name = definition.name();
                this.toolDefinition = new DefaultToolDefinition(
                        name,
                        definition.description(),
                        definition.inputSchema()
                );
                this.toolName = name;
            }
            ToolMetadata metadata = this.delegate.getToolMetadata();
            this.toolMetadata = metadata == null
                    ? null
                    : ToolMetadata.builder().returnDirect(metadata.returnDirect()).build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return this.toolDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return this.toolMetadata;
        }

        @Override
        public String call(String toolInput) {
            return callDelegate(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return callDelegate(toolInput, toolContext);
        }

        private String callDelegate(String toolInput, ToolContext toolContext) {
            try {
                String delegateOutput = toolContext == null
                        ? this.delegate.call(toolInput)
                        : this.delegate.call(toolInput, toolContext);
                this.probe.recordToolCall(this.toolName, toolInput, delegateOutput, null);
                return delegateOutput;
            } catch (RuntimeException | Error failure) {
                this.probe.recordToolCall(this.toolName, toolInput, null, failure);
                throw failure;
            }
        }
    }
}

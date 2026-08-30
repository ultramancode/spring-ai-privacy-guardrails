package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.security.autoconfigure.PrivacySecurityChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
import org.springframework.security.authorization.AuthorizationDeniedException;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class PrivacyDemoToolLoop {

    private static final PrivacyDemoScenario SCENARIO = PrivacyDemoScenario.DEFAULT;
    private static final List<String> RAW_VALUES = SCENARIO.originalValues();
    private static final List<String> DENIED_TOOL_VALUES = SCENARIO.deniedToolValues();
    private static final List<String> ALLOWED_TOOL_VALUES = SCENARIO.allowedToolValues();
    private static final Pattern EMPLOYEE_ID_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID");
    private static final Pattern EMAIL_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS");
    private static final Pattern PHONE_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("PHONE_NUMBER");
    private static final Pattern CUSTOMER_ID_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("CUSTOMER_ID");
    private static final List<Pattern> EXPECTED_TOKEN_PATTERNS = List.of(
            EMPLOYEE_ID_TOKEN_PATTERN,
            EMAIL_TOKEN_PATTERN,
            PHONE_TOKEN_PATTERN,
            CUSTOMER_ID_TOKEN_PATTERN
    );

    private final PrivacySecurityChatClientConfigurer securityConfigurer;
    private final PrivacyToolCallbackFactory toolCallbackFactory;
    private final ToolDisclosurePolicy toolDisclosurePolicy;
    private final ToolCallingManager toolCallingManager;
    private final PrivacyDemoSecurityPolicy securityPolicy;
    private final ObjectMapper objectMapper;

    PrivacyDemoToolLoop(
            PrivacySecurityChatClientConfigurer securityConfigurer,
            PrivacyToolCallbackFactory toolCallbackFactory,
            ToolDisclosurePolicy toolDisclosurePolicy,
            ToolCallingManager toolCallingManager,
            PrivacyDemoSecurityPolicy securityPolicy,
            ObjectMapper objectMapper
    ) {
        this.securityConfigurer = securityConfigurer;
        this.toolCallbackFactory = toolCallbackFactory;
        this.toolDisclosurePolicy = toolDisclosurePolicy;
        this.toolCallingManager = toolCallingManager;
        this.securityPolicy = securityPolicy;
        this.objectMapper = objectMapper;
    }

    Result run(String input, PrivacyDemoLocale locale) {
        Attempt attempt = this.securityPolicy.runAs(
                PrivacyDemoSecurityPolicy.Role.CUSTOMER_SUPPORT,
                () -> execute(input, locale)
        ).value();
        if (attempt.denial() != null) {
            throw attempt.denial();
        }
        return attempt.result();
    }

    SecurityRun runSecurity(
            String input,
            PrivacyDemoLocale locale,
            PrivacyDemoSecurityPolicy.Role role
    ) {
        PrivacyDemoSecurityPolicy.AuthorizedRun<Attempt> authorizedRun =
                this.securityPolicy.runAs(role, () -> execute(input, locale));
        Attempt attempt = authorizedRun.value();
        Result result = attempt.result();
        boolean denied = attempt.denial() != null;
        return new SecurityRun(
                role.authority(),
                attempt.model().exposedToolNames(),
                authorizedRun.checks(),
                attempt.model().issuedToolArguments() != null,
                denied,
                denied ? attempt.denial().getClass().getSimpleName() : null,
                attempt.delegate().calls(),
                denied && attempt.delegate().calls() == 0,
                result != null && result.toolReceivedOnlyAllowedOriginals(),
                result != null && result.toolResultRetokenizedBeforeModel(),
                result == null ? null : result.finalResponse()
        );
    }

    private Attempt execute(String input, PrivacyDemoLocale locale) {
        DemoToolLoopModel model = new DemoToolLoopModel(
                this.objectMapper,
                "CRM",
                locale,
                this.toolCallingManager
        );
        PrivacyDemoCrmTool delegate = new PrivacyDemoCrmTool(this.objectMapper);
        ToolCallback scopedTool = this.toolCallbackFactory.wrap(delegate);

        ChatClient.Builder builder = ChatClient.builder(
                model,
                ObservationRegistry.NOOP,
                null,
                null,
                ToolCallingAdvisor.builder().toolCallingManager(this.toolCallingManager)
        ).defaultTools(scopedTool);
        this.securityConfigurer.configure(builder);
        String finalResponse;
        try {
            finalResponse = builder.build().prompt().user(input).call().content();
        }
        catch (AuthorizationDeniedException denial) {
            return new Attempt(model, delegate, null, denial);
        }

        Result result = new Result(
                model.calls(),
                !model.rawPiiSeenByModel(),
                model.protectedModelInput(),
                model.issuedToolArguments(),
                this.toolDisclosurePolicy.scopeFor(delegate.getToolDefinition())
                        .entityTypes()
                        .stream()
                        .sorted()
                        .toList(),
                delegate.receivedOnlyAllowedOriginals(),
                delegate.lookupSucceededWithRestoredCustomerId(),
                model.protectedToolResultSeenByModel(),
                new BoundaryEvidence(
                        EvidenceCount.expectedNone(model.rawValueCount(), RAW_VALUES.size()),
                        EvidenceCount.expectedNone(
                                delegate.deniedRawValueCount(),
                                DENIED_TOOL_VALUES.size()
                        ),
                        EvidenceCount.expectedAll(
                                delegate.allowedRawValueCount(),
                                ALLOWED_TOOL_VALUES.size()
                        ),
                        EvidenceCount.expectedNone(
                                model.rawToolResultValueCountAtModel(),
                                RAW_VALUES.size()
                        )
                ),
                finalResponse
        );
        return new Attempt(model, delegate, result, null);
    }

    record SecurityRun(
            String role,
            List<String> exposedToolNames,
            List<PrivacyDemoSecurityPolicy.AuthorizationCheck> authorizationChecks,
            boolean modelRequestedTool,
            boolean toolCallDenied,
            String denialType,
            int callbackInvocations,
            boolean deniedCallStoppedBeforeCallback,
            boolean toolReceivedOnlyAllowedOriginals,
            boolean toolResultRetokenizedBeforeModel,
            String finalResponse
    ) {
    }

    private record Attempt(
            DemoToolLoopModel model,
            PrivacyDemoCrmTool delegate,
            Result result,
            AuthorizationDeniedException denial
    ) {
    }

    record Result(
            int modelCalls,
            boolean modelSawOnlyTokens,
            String protectedModelInput,
            String tokenizedToolArguments,
            List<String> allowedOriginalEntityTypes,
            boolean toolReceivedOnlyAllowedOriginals,
            boolean toolLookupSucceededWithRestoredCustomerId,
            boolean toolResultRetokenizedBeforeModel,
            BoundaryEvidence boundaryEvidence,
            String finalResponse
    ) {
    }

    record BoundaryEvidence(
            EvidenceCount modelRawValues,
            EvidenceCount deniedToolRawValues,
            EvidenceCount allowedToolRawValues,
            EvidenceCount rawToolResultValuesAtModel
    ) {
    }

    record EvidenceCount(int observed, int total, boolean passed) {

        EvidenceCount {
            if (observed < 0 || total < 0 || observed > total) {
                throw new IllegalArgumentException("evidence counts are invalid");
            }
        }

        static EvidenceCount expectedNone(int observed, int total) {
            return new EvidenceCount(observed, total, observed == 0);
        }

        static EvidenceCount expectedAll(int observed, int total) {
            return new EvidenceCount(observed, total, observed == total);
        }
    }

    static final class DemoToolLoopModel implements ChatModel {

        private final ObjectMapper objectMapper;
        private final String protectedResultSource;
        private final PrivacyDemoLocale locale;
        private final ToolCallingManager toolCallingManager;
        private final Set<String> rawValuesSeenByModel = new LinkedHashSet<>();
        private List<String> exposedToolNames = List.of();
        private int calls;
        private boolean protectedToolResultSeenByModel;
        private int rawToolResultValueCountAtModel;
        private String protectedModelInput;
        private String issuedToolArguments;

        DemoToolLoopModel(ObjectMapper objectMapper) {
            this(objectMapper, "CRM", PrivacyDemoLocale.EN, null);
        }

        DemoToolLoopModel(ObjectMapper objectMapper, String protectedResultSource) {
            this(objectMapper, protectedResultSource, PrivacyDemoLocale.EN, null);
        }

        DemoToolLoopModel(
                ObjectMapper objectMapper,
                String protectedResultSource,
                PrivacyDemoLocale locale
        ) {
            this(objectMapper, protectedResultSource, locale, null);
        }

        DemoToolLoopModel(
                ObjectMapper objectMapper,
                String protectedResultSource,
                PrivacyDemoLocale locale,
                ToolCallingManager toolCallingManager
        ) {
            this.objectMapper = objectMapper;
            this.protectedResultSource = protectedResultSource;
            this.locale = locale;
            this.toolCallingManager = toolCallingManager;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            resolveExposedTools(prompt);
            this.calls++;
            recordRawValues(prompt);

            if (this.calls == 1) {
                this.protectedModelInput = prompt.getInstructions().stream()
                        .map(Message::getText)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("\n"));
                CrmLookupArguments arguments = new CrmLookupArguments(
                        findToken(prompt, EMPLOYEE_ID_TOKEN_PATTERN),
                        findToken(prompt, EMAIL_TOKEN_PATTERN),
                        findToken(prompt, PHONE_TOKEN_PATTERN),
                        findToken(prompt, CUSTOMER_ID_TOKEN_PATTERN)
                );
                this.issuedToolArguments = this.objectMapper.writeValueAsString(arguments);

                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "demo-call-1",
                                "function",
                                "customerLookup",
                                this.issuedToolArguments
                        )))
                        .build();
                return response(toolCall);
            }

            String toolResult = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Tool response was not returned to the model"));
            this.rawToolResultValueCountAtModel = countContainedValues(toolResult, RAW_VALUES);
            this.protectedToolResultSeenByModel = this.rawToolResultValueCountAtModel == 0
                    && EXPECTED_TOKEN_PATTERNS.stream().allMatch(pattern -> pattern.matcher(toolResult).find());
            return response(new AssistantMessage(
                    this.locale.protectedResult(this.protectedResultSource, toolResult)
            ));
        }

        private void resolveExposedTools(Prompt prompt) {
            if (this.toolCallingManager == null) {
                return;
            }
            if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
                throw new IllegalStateException("Expected tool-calling options");
            }
            this.exposedToolNames = this.toolCallingManager.resolveToolDefinitions(options)
                    .stream()
                    .map(ToolDefinition::name)
                    .sorted()
                    .toList();
        }

        private void recordRawValues(Prompt prompt) {
            List<String> modelContent = new ArrayList<>();
            for (Message message : prompt.getInstructions()) {
                addIfPresent(modelContent, message.getText());
                if (message instanceof AssistantMessage assistantMessage) {
                    assistantMessage.getToolCalls().stream()
                            .map(AssistantMessage.ToolCall::arguments)
                            .forEach(value -> addIfPresent(modelContent, value));
                }
                if (message instanceof ToolResponseMessage toolResponseMessage) {
                    toolResponseMessage.getResponses().stream()
                            .map(ToolResponseMessage.ToolResponse::responseData)
                            .forEach(value -> addIfPresent(modelContent, value));
                }
            }
            RAW_VALUES.stream()
                    .filter(rawValue -> modelContent.stream().anyMatch(content -> content.contains(rawValue)))
                    .forEach(this.rawValuesSeenByModel::add);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private String findToken(Prompt prompt, Pattern pattern) {
            return prompt.getInstructions().stream()
                    .map(Message::getText)
                    .filter(Objects::nonNull)
                    .map(pattern::matcher)
                    .filter(Matcher::find)
                    .map(Matcher::group)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Expected protected token was not sent to the model"));
        }

        private ChatResponse response(AssistantMessage message) {
            return new ChatResponse(List.of(new Generation(message)));
        }

        int calls() {
            return this.calls;
        }

        boolean rawPiiSeenByModel() {
            return !this.rawValuesSeenByModel.isEmpty();
        }

        int rawValueCount() {
            return this.rawValuesSeenByModel.size();
        }

        boolean protectedToolResultSeenByModel() {
            return this.protectedToolResultSeenByModel;
        }

        String issuedToolArguments() {
            return this.issuedToolArguments;
        }

        int rawToolResultValueCountAtModel() {
            return this.rawToolResultValueCountAtModel;
        }

        String protectedModelInput() {
            return this.protectedModelInput;
        }

        List<String> exposedToolNames() {
            return this.exposedToolNames;
        }
    }

    private static int countContainedValues(String text, List<String> values) {
        if (text == null) {
            return 0;
        }
        return Math.toIntExact(values.stream().filter(text::contains).count());
    }

    private static void addIfPresent(List<String> values, String value) {
        if (value != null) {
            values.add(value);
        }
    }
}

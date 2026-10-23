package io.github.ultramancode.springai.privacy.sample.mcp.service;

import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.sample.mcp.infrastructure.McpCustomerLookupTool;
import io.github.ultramancode.springai.privacy.sample.mcp.infrastructure.McpDemoClient;
import io.github.ultramancode.springai.privacy.sample.shared.model.CustomerDemoData;
import io.github.ultramancode.springai.privacy.sample.shared.model.DemoLanguage;
import io.github.ultramancode.springai.privacy.sample.shared.security.authorization.DemoToolAuthorization;
import io.github.ultramancode.springai.privacy.sample.shared.security.model.DemoRole;
import io.github.ultramancode.springai.privacy.sample.shared.security.model.RoleExecutionResult;
import io.github.ultramancode.springai.privacy.sample.shared.tool.dto.response.ToolLoopDemoResponse;
import io.github.ultramancode.springai.privacy.sample.shared.tool.infrastructure.DemoToolLoopChatModel;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.CustomerLookupArguments;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolArgumentEvidence;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolLoopResult.BoundaryEvidence;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolLoopResult.EvidenceCount;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolLoopResult;
import io.github.ultramancode.springai.privacy.springai.AllowedEntityTypes;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static io.github.ultramancode.springai.privacy.sample.shared.tool.support.DemoPiiChecks.checkToolArguments;

@Service
public final class McpToolLoopDemoService {

    private static final CustomerDemoData CUSTOMER_DATA = CustomerDemoData.DEFAULT;
    private static final List<String> RAW_VALUES = CUSTOMER_DATA.originalValues();
    private static final List<String> DENIED_TOOL_VALUES = CUSTOMER_DATA.deniedToolValues();
    private static final List<String> ALLOWED_TOOL_VALUES = CUSTOMER_DATA.allowedToolValues();
    private final GuardrailsChatClientFactory guardrailsChatClientFactory;
    private final ToolDisclosurePolicy toolDisclosurePolicy;
    private final ToolCallingManager toolCallingManager;
    private final DemoToolAuthorization toolAuthorization;
    private final ObjectMapper objectMapper;
    private final PrivacyService privacyService;
    private final McpCustomerLookupTool customerLookupTool;
    private final McpDemoClient mcpDemoClient;

    public McpToolLoopDemoService(
        GuardrailsChatClientFactory guardrailsChatClientFactory,
        ToolDisclosurePolicy toolDisclosurePolicy,
        ToolCallingManager toolCallingManager,
        DemoToolAuthorization toolAuthorization,
        ObjectMapper objectMapper,
        PrivacyService privacyService,
        McpCustomerLookupTool customerLookupTool,
        McpDemoClient mcpDemoClient
    ) {
        this.guardrailsChatClientFactory = guardrailsChatClientFactory;
        this.toolDisclosurePolicy = toolDisclosurePolicy;
        this.toolCallingManager = toolCallingManager;
        this.toolAuthorization = toolAuthorization;
        this.objectMapper = objectMapper;
        this.privacyService = privacyService;
        this.customerLookupTool = customerLookupTool;
        this.mcpDemoClient = mcpDemoClient;
    }

    public synchronized ToolLoopDemoResponse runMcpToolLoopDemo(Locale locale) {
        DemoLanguage language = DemoLanguage.from(locale);
        String input = CustomerDemoData.getByLanguage(language).input();
        this.customerLookupTool.replaceCustomerRecords(createCrmRecords(language));
        ToolCallbackProvider mcpTools = this.mcpDemoClient.getToolCallbackProvider();
        RoleExecutionResult<ToolLoopResult> execution =
                this.toolAuthorization.executeAsRole(
                        DemoRole.CUSTOMER_SUPPORT,
                        () -> executeToolLoop(input, language, mcpTools)
                );
        return ToolLoopDemoResponse.from(execution.result(), this.privacyService.activeSessionCount());
    }

    // Wait for the running demo, then close the MCP connection before the HTTP server stops.
    @EventListener(ContextClosedEvent.class)
    public synchronized void closeMcpConnection() {
        this.mcpDemoClient.close();
    }

    private ToolLoopResult executeToolLoop(
            String input,
            DemoLanguage language,
            ToolCallbackProvider mcpTools
    ) {
        ToolDefinition toolDefinition = requireSingleToolDefinition(mcpTools);
        AllowedEntityTypes allowedEntityTypes = Objects.requireNonNull(
                this.toolDisclosurePolicy.getAllowedEntityTypes(toolDefinition),
                "tool disclosure policy must return allowed entity types"
        );
        List<String> allowedOriginalEntityTypes = allowedEntityTypes.entityTypes().stream().sorted().toList();
        DemoToolLoopChatModel toolLoopModel =
            new DemoToolLoopChatModel(
                this.objectMapper,
                "MCP",
                language,
                this.toolCallingManager
            );

        ChatClient.Builder chatClientBuilder = this.guardrailsChatClientFactory.builder(toolLoopModel)
            .defaultTools(mcpTools);

        int callsBeforeRequest = this.customerLookupTool.getCallState().totalCallCount();
        String finalResponse = chatClientBuilder.build().prompt().user(input).call().content();
        McpCustomerLookupTool.ToolCallState callState = this.customerLookupTool.getCallState();
        if (callState.totalCallCount() - callsBeforeRequest != 1) {
            throw new IllegalStateException("MCP demo expected exactly one tool call");
        }

        return createToolLoopResult(toolLoopModel, callState, allowedOriginalEntityTypes, finalResponse);
    }

    private ToolLoopResult createToolLoopResult(
            DemoToolLoopChatModel toolLoopModel,
            McpCustomerLookupTool.ToolCallState callState,
            List<String> allowedOriginalEntityTypes,
            String finalResponse
    ) {
        CustomerLookupArguments arguments = callState.requireLastReceivedArguments();
        ToolArgumentEvidence argumentEvidence = checkToolArguments(arguments);
        BoundaryEvidence boundaryEvidence = createBoundaryEvidence(toolLoopModel, argumentEvidence);
        return new ToolLoopResult(
            toolLoopModel.calls(),
            toolLoopModel.protectedModelInput(),
            toolLoopModel.issuedToolArguments(),
            allowedOriginalEntityTypes,
            argumentEvidence.receivedOnlyAllowedOriginals(),
            callState.lastLookupSucceeded(),
            toolLoopModel.protectedToolResultSeenByModel(),
            boundaryEvidence,
            finalResponse
        );
    }

    private static BoundaryEvidence createBoundaryEvidence(
            DemoToolLoopChatModel toolLoopModel,
            ToolArgumentEvidence argumentEvidence
    ) {
        EvidenceCount modelRawValues = EvidenceCount.expectedNone(
            toolLoopModel.rawValueCount(), RAW_VALUES.size()
        );
        EvidenceCount deniedToolRawValues = EvidenceCount.expectedNone(
            argumentEvidence.deniedRawValueCount(), DENIED_TOOL_VALUES.size()
        );
        EvidenceCount allowedToolRawValues = EvidenceCount.expectedAll(
            argumentEvidence.allowedRawValueCount(), ALLOWED_TOOL_VALUES.size()
        );
        EvidenceCount rawToolResultValuesAtModel = EvidenceCount.expectedNone(
            toolLoopModel.rawToolResultValueCountAtModel(), RAW_VALUES.size()
        );
        return new BoundaryEvidence(
            modelRawValues,
            deniedToolRawValues,
            allowedToolRawValues,
            rawToolResultValuesAtModel
        );
    }

    private static Map<String, String> createCrmRecords(DemoLanguage language) {
        String recordTemplate;
        if (language == DemoLanguage.KO) {
            recordTemplate = "CRM 결과: 직원번호는 %s / 이메일은 %s / 전화번호는 %s / 고객번호는 %s";
        }
        else {
            recordTemplate = "CRM result: Employee ID is %s / email is %s / phone is %s / customer ID is %s";
        }
        return Map.of(
                CUSTOMER_DATA.customerId(),
                recordTemplate.formatted(
                        CUSTOMER_DATA.employeeId(),
                        CUSTOMER_DATA.email(),
                        CUSTOMER_DATA.phone(),
                        CUSTOMER_DATA.customerId()
                )
        );
    }

    private static ToolDefinition requireSingleToolDefinition(ToolCallbackProvider toolCallbackProvider) {
        ToolCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
        if (toolCallbacks == null || toolCallbacks.length != 1 || toolCallbacks[0] == null) {
            throw new IllegalStateException("MCP demo expected exactly one tool definition");
        }
        return toolCallbacks[0].getToolDefinition();
    }
}

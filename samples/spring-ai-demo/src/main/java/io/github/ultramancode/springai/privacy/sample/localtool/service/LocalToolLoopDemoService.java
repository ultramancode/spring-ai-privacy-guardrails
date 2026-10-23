package io.github.ultramancode.springai.privacy.sample.localtool.service;

import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.sample.localtool.infrastructure.DemoCustomerLookupTool;
import io.github.ultramancode.springai.privacy.sample.shared.model.CustomerDemoData;
import io.github.ultramancode.springai.privacy.sample.shared.model.DemoLanguage;
import io.github.ultramancode.springai.privacy.sample.shared.security.authorization.DemoToolAuthorization;
import io.github.ultramancode.springai.privacy.sample.shared.security.model.DemoRole;
import io.github.ultramancode.springai.privacy.sample.shared.security.model.RoleExecutionResult;
import io.github.ultramancode.springai.privacy.sample.shared.tool.dto.response.ToolLoopDemoResponse;
import io.github.ultramancode.springai.privacy.sample.shared.tool.infrastructure.DemoToolLoopChatModel;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolLoopResult.BoundaryEvidence;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolLoopResult.EvidenceCount;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolLoopResult;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolSecurityResult;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

@Service
public final class LocalToolLoopDemoService {

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

    public LocalToolLoopDemoService(
        GuardrailsChatClientFactory guardrailsChatClientFactory,
        ToolDisclosurePolicy toolDisclosurePolicy,
        ToolCallingManager toolCallingManager,
        DemoToolAuthorization toolAuthorization,
        ObjectMapper objectMapper,
        PrivacyService privacyService
    ) {
        this.guardrailsChatClientFactory = guardrailsChatClientFactory;
        this.toolDisclosurePolicy = toolDisclosurePolicy;
        this.toolCallingManager = toolCallingManager;
        this.toolAuthorization = toolAuthorization;
        this.objectMapper = objectMapper;
        this.privacyService = privacyService;
    }

    public ToolLoopDemoResponse runLocalToolLoopDemo(Locale locale) {
        DemoLanguage language = DemoLanguage.from(locale);
        String input = CustomerDemoData.getByLanguage(language).input();
        RoleExecutionResult<ToolLoopAttempt> execution =
                this.toolAuthorization.executeAsRole(
                        DemoRole.CUSTOMER_SUPPORT,
                        () -> executeToolLoop(input, language)
                );
        ToolLoopAttempt attempt = execution.result();
        if (attempt.denial() != null) {
            throw attempt.denial();
        }
        return ToolLoopDemoResponse.from(attempt.result(), this.privacyService.activeSessionCount());
    }

    public ToolSecurityResult executeToolLoopAsRole(
            String input,
            DemoLanguage language,
            DemoRole role
    ) {
        RoleExecutionResult<ToolLoopAttempt> execution =
                this.toolAuthorization.executeAsRole(role, () -> executeToolLoop(input, language));
        ToolLoopAttempt attempt = execution.result();
        AuthorizationDeniedException denial = attempt.denial();
        String denialExceptionType = null;
        if (denial != null) {
            denialExceptionType = denial.getClass().getSimpleName();
        }
        return new ToolSecurityResult(
                role.authority(),
                attempt.toolLoopModel().exposedToolNames(),
                execution.authorizationChecks(),
                attempt.toolLoopModel().issuedToolArguments() != null,
                denialExceptionType,
                attempt.crmTool().calls(),
                attempt.result()
        );
    }

    private ToolLoopAttempt executeToolLoop(String input, DemoLanguage language) {
        DemoToolLoopChatModel toolLoopModel = new DemoToolLoopChatModel(
            this.objectMapper,
            "CRM",
            language,
            this.toolCallingManager
        );
        DemoCustomerLookupTool crmTool = new DemoCustomerLookupTool(this.objectMapper);

        ChatClient.Builder chatClientBuilder = this.guardrailsChatClientFactory.builder(toolLoopModel)
            .defaultTools(crmTool);
        String finalResponse;
        try {
            finalResponse = chatClientBuilder.build().prompt().user(input).call().content();
        }
        catch (AuthorizationDeniedException denial) {
            return new ToolLoopAttempt(toolLoopModel, crmTool, null, denial);
        }

        ToolLoopResult result = createToolLoopResult(toolLoopModel, crmTool, finalResponse);
        return new ToolLoopAttempt(toolLoopModel, crmTool, result, null);
    }

    private ToolLoopResult createToolLoopResult(
            DemoToolLoopChatModel toolLoopModel,
            DemoCustomerLookupTool crmTool,
            String finalResponse
    ) {
        List<String> allowedOriginalEntityTypes = this.toolDisclosurePolicy
            .getAllowedEntityTypes(crmTool.getToolDefinition())
            .entityTypes()
            .stream()
            .sorted()
            .toList();
        BoundaryEvidence boundaryEvidence = createBoundaryEvidence(toolLoopModel, crmTool);
        return new ToolLoopResult(
            toolLoopModel.calls(),
            toolLoopModel.protectedModelInput(),
            toolLoopModel.issuedToolArguments(),
            allowedOriginalEntityTypes,
            crmTool.receivedOnlyAllowedOriginals(),
            crmTool.lookupSucceeded(),
            toolLoopModel.protectedToolResultSeenByModel(),
            boundaryEvidence,
            finalResponse
        );
    }

    private static BoundaryEvidence createBoundaryEvidence(DemoToolLoopChatModel toolLoopModel, DemoCustomerLookupTool crmTool) {
        EvidenceCount modelRawValues = EvidenceCount.expectedNone(
            toolLoopModel.rawValueCount(), RAW_VALUES.size()
        );
        EvidenceCount deniedToolRawValues = EvidenceCount.expectedNone(
            crmTool.deniedRawValueCount(), DENIED_TOOL_VALUES.size()
        );
        EvidenceCount allowedToolRawValues = EvidenceCount.expectedAll(
            crmTool.allowedRawValueCount(), ALLOWED_TOOL_VALUES.size()
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

    private record ToolLoopAttempt(
        DemoToolLoopChatModel toolLoopModel,
        DemoCustomerLookupTool crmTool,
        ToolLoopResult result,
        AuthorizationDeniedException denial
    ) {
    }
}

package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.core.PiiResolutionReason;
import io.github.ultramancode.springai.privacy.core.PiiTokenizationResult;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.ResolvedPiiSpan;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationPhase;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/demo")
public class PrivacyDemoController {

    private final PrivacyService privacyService;
    private final ChatClient chatClient;
    private final PrivacyDemoRag rag;
    private final PrivacyDemoToolLoop toolLoop;
    private final PrivacyDemoMcpToolLoop mcpToolLoop;

    public PrivacyDemoController(
            PrivacyService privacyService,
            ChatClient chatClient,
            PrivacyDemoRag rag,
            PrivacyDemoToolLoop toolLoop,
            PrivacyDemoMcpToolLoop mcpToolLoop
    ) {
        this.privacyService = privacyService;
        this.chatClient = chatClient;
        this.rag = rag;
        this.toolLoop = toolLoop;
        this.mcpToolLoop = mcpToolLoop;
    }

    @GetMapping("/scenario")
    public DemoScenarioResponse scenario(Locale locale) {
        return new DemoScenarioResponse(scenarioFor(locale).input());
    }

    @GetMapping("/chat-client")
    public AdvisorChatResponse chatClient() {
        return chatClient(new DemoRequest(PrivacyDemoScenario.ENGLISH.input()));
    }

    @PostMapping("/chat-client")
    public AdvisorChatResponse chatClient(@RequestBody DemoRequest request) {
        String input = requireText(request);
        String modelResponse = this.chatClient.prompt().user(input).call().content();
        return new AdvisorChatResponse(modelResponse, this.privacyService.activeSessionCount());
    }

    @GetMapping("/protect")
    public ProtectedPromptResponse protect(Locale locale) {
        return protect(new DemoRequest(scenarioFor(locale).input()));
    }

    @PostMapping("/protect")
    public ProtectedPromptResponse protect(@RequestBody DemoRequest request) {
        String input = requireText(request);
        try (PrivacySession session = this.privacyService.openSession()) {
            PiiTokenizationResult tokenization = this.privacyService.analyzeAndTokenize(session.handle(), input);
            return new ProtectedPromptResponse(
                    tokenization.tokenizedText(),
                    tokenization.analysis().spans().stream().map(DetectedSpan::from).toList(),
                    tokenization.analysis().successfulProviders().stream().sorted().toList()
            );
        }
    }

    @GetMapping("/rag")
    public RagResponse rag(Locale locale) {
        PrivacyDemoRag.Result result = this.rag.run(PrivacyDemoLocale.from(locale));
        return new RagResponse(
                result.retrievedDocument(),
                result.modelVisibleContext(),
                result.retrievedDocumentContainsRawPii(),
                result.modelVisibleContextContainsRawPii(),
                result.modelVisibleContextContainsTokenizedPii(),
                this.privacyService.activeSessionCount()
        );
    }

    @GetMapping("/tool-loop")
    public ToolLoopResponse toolLoop(Locale locale) {
        PrivacyDemoLocale demoLocale = PrivacyDemoLocale.from(locale);
        PrivacyDemoToolLoop.Result toolLoopResult = this.toolLoop.run(
                PrivacyDemoScenario.forLocale(demoLocale).input(),
                demoLocale
        );
        return toolLoopResponse("actual-chat-client-tool-loop", toolLoopResult);
    }

    @GetMapping("/mcp-tool-loop")
    public ToolLoopResponse mcpToolLoop(Locale locale) {
        PrivacyDemoLocale demoLocale = PrivacyDemoLocale.from(locale);
        PrivacyDemoToolLoop.Result toolLoopResult = this.mcpToolLoop.run(
                PrivacyDemoScenario.forLocale(demoLocale).input(),
                demoLocale
        );
        return toolLoopResponse("actual-streamable-http-mcp-tool-loop", toolLoopResult);
    }

    @GetMapping("/security-tool-boundary")
    public SecurityBoundaryResponse securityToolBoundary(Locale locale) {
        PrivacyDemoLocale demoLocale = PrivacyDemoLocale.from(locale);
        String input = PrivacyDemoScenario.forLocale(demoLocale).input();
        PrivacyDemoToolLoop.SecurityRun generalEmployee = this.toolLoop.runSecurity(
                input,
                demoLocale,
                PrivacyDemoSecurityPolicy.Role.GENERAL_EMPLOYEE
        );
        PrivacyDemoToolLoop.SecurityRun customerSupport = this.toolLoop.runSecurity(
                input,
                demoLocale,
                PrivacyDemoSecurityPolicy.Role.CUSTOMER_SUPPORT
        );
        return new SecurityBoundaryResponse(
                "actual-spring-security-tool-boundary",
                securityRequestSummary(demoLocale),
                SecurityRoleEvidence.from(generalEmployee),
                SecurityRoleEvidence.from(customerSupport),
                this.privacyService.activeSessionCount()
        );
    }

    private static PrivacyDemoScenario scenarioFor(Locale locale) {
        return PrivacyDemoScenario.forLocale(locale);
    }

    private static String securityRequestSummary(PrivacyDemoLocale locale) {
        return locale == PrivacyDemoLocale.KO
                ? "고객정보를 조회해 주세요."
                : "Look up the customer information.";
    }

    private ToolLoopResponse toolLoopResponse(
            String mode,
            PrivacyDemoToolLoop.Result toolLoopResult
    ) {
        return new ToolLoopResponse(
                mode,
                toolLoopResult.modelCalls(),
                toolLoopResult.modelSawOnlyTokens(),
                toolLoopResult.protectedModelInput(),
                toolLoopResult.tokenizedToolArguments(),
                toolLoopResult.allowedOriginalEntityTypes(),
                toolLoopResult.toolReceivedOnlyAllowedOriginals(),
                toolLoopResult.toolLookupSucceededWithRestoredCustomerId(),
                toolLoopResult.toolResultRetokenizedBeforeModel(),
                BoundaryEvidence.from(toolLoopResult.boundaryEvidence()),
                toolLoopResult.finalResponse(),
                this.privacyService.activeSessionCount()
        );
    }

    private static String requireText(DemoRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must not be blank");
        }
        return request.text();
    }

    public record DemoRequest(String text) {
    }

    public record DemoScenarioResponse(String input) {
    }

    public record AdvisorChatResponse(
            String modelResponse,
            int activeSessionsAfterCall
    ) {
    }

    public record ProtectedPromptResponse(
            String protectedPrompt,
            List<DetectedSpan> detectedSpans,
            List<String> successfulProviders
    ) {
    }

    public record RagResponse(
            String retrievedDocument,
            String modelVisibleContext,
            boolean retrievedDocumentContainsRawPii,
            boolean modelVisibleContextContainsRawPii,
            boolean modelVisibleContextContainsTokenizedPii,
            int activeSessionsAfterCall
    ) {
    }

    public record DetectedSpan(
            String type,
            int start,
            int end,
            List<String> providers,
            PiiResolutionReason reason
    ) {

        private static DetectedSpan from(ResolvedPiiSpan span) {
            return new DetectedSpan(
                    span.entityType(),
                    span.start(),
                    span.end(),
                    span.evidence().stream()
                            .map(evidence -> evidence.provider())
                            .distinct()
                            .sorted()
                            .toList(),
                    span.reason()
            );
        }
    }

    public record ToolLoopResponse(
            String mode,
            int modelCalls,
            boolean modelSawOnlyTokens,
            String protectedModelInput,
            String tokenizedToolArguments,
            List<String> allowedOriginalEntityTypes,
            boolean toolReceivedOnlyAllowedOriginals,
            boolean toolLookupSucceededWithRestoredCustomerId,
            boolean toolResultRetokenizedBeforeModel,
            BoundaryEvidence boundaryEvidence,
            String finalResponse,
            int activeSessionsAfterCall
    ) {
    }

    public record SecurityBoundaryResponse(
            String mode,
            String requestSummary,
            SecurityRoleEvidence generalEmployee,
            SecurityRoleEvidence customerSupport,
            int activeSessionsAfterCall
    ) {
    }

    public record SecurityRoleEvidence(
            String role,
            List<String> exposedToolNames,
            List<AuthorizationCheck> authorizationChecks,
            boolean modelRequestedTool,
            boolean toolCallDenied,
            String denialType,
            int callbackInvocations,
            boolean deniedCallStoppedBeforeCallback,
            boolean toolReceivedOnlyAllowedOriginals,
            boolean toolResultRetokenizedBeforeModel,
            String finalResponse
    ) {

        private static SecurityRoleEvidence from(PrivacyDemoToolLoop.SecurityRun run) {
            return new SecurityRoleEvidence(
                    run.role(),
                    run.exposedToolNames(),
                    run.authorizationChecks().stream().map(AuthorizationCheck::from).toList(),
                    run.modelRequestedTool(),
                    run.toolCallDenied(),
                    run.denialType(),
                    run.callbackInvocations(),
                    run.deniedCallStoppedBeforeCallback(),
                    run.toolReceivedOnlyAllowedOriginals(),
                    run.toolResultRetokenizedBeforeModel(),
                    run.finalResponse()
            );
        }
    }

    public record AuthorizationCheck(
            String toolName,
            ToolAuthorizationPhase phase,
            boolean granted
    ) {

        private static AuthorizationCheck from(
                PrivacyDemoSecurityPolicy.AuthorizationCheck check
        ) {
            return new AuthorizationCheck(check.toolName(), check.phase(), check.granted());
        }
    }

    public record BoundaryEvidence(
            EvidenceCount modelRawValues,
            EvidenceCount deniedToolRawValues,
            EvidenceCount allowedToolRawValues,
            EvidenceCount rawToolResultValuesAtModel
    ) {

        private static BoundaryEvidence from(PrivacyDemoToolLoop.BoundaryEvidence evidence) {
            return new BoundaryEvidence(
                    EvidenceCount.from(evidence.modelRawValues()),
                    EvidenceCount.from(evidence.deniedToolRawValues()),
                    EvidenceCount.from(evidence.allowedToolRawValues()),
                    EvidenceCount.from(evidence.rawToolResultValuesAtModel())
            );
        }
    }

    public record EvidenceCount(int observed, int total, boolean passed) {

        private static EvidenceCount from(PrivacyDemoToolLoop.EvidenceCount evidence) {
            return new EvidenceCount(evidence.observed(), evidence.total(), evidence.passed());
        }
    }
}

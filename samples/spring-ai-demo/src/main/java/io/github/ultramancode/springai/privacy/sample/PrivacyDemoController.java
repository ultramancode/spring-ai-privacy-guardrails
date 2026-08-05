package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.core.PiiResolutionReason;
import io.github.ultramancode.springai.privacy.core.PiiTokenizationResult;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.ResolvedPiiSpan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/demo")
public class PrivacyDemoController {

    private static final PrivacyDemoScenario SCENARIO = PrivacyDemoScenario.DEFAULT;

    private final PrivacyService privacyService;
    private final ChatClient chatClient;
    private final PrivacyDemoToolLoop toolLoop;

    public PrivacyDemoController(
            PrivacyService privacyService,
            ChatClient chatClient,
            PrivacyDemoToolLoop toolLoop
    ) {
        this.privacyService = privacyService;
        this.chatClient = chatClient;
        this.toolLoop = toolLoop;
    }

    @GetMapping("/scenario")
    public DemoScenarioResponse scenario() {
        return new DemoScenarioResponse(SCENARIO.input());
    }

    @GetMapping("/chat-client")
    public AdvisorChatResponse chatClient() {
        return chatClient(new DemoRequest(SCENARIO.input()));
    }

    @PostMapping("/chat-client")
    public AdvisorChatResponse chatClient(@RequestBody DemoRequest request) {
        String input = requireText(request);
        String modelResponse = this.chatClient.prompt().user(input).call().content();
        return new AdvisorChatResponse(modelResponse, this.privacyService.activeSessionCount());
    }

    @GetMapping("/protect")
    public ProtectedPromptResponse protect() {
        return protect(new DemoRequest(SCENARIO.input()));
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

    @GetMapping("/tool-loop")
    public ToolLoopResponse toolLoop() {
        PrivacyDemoToolLoop.Result toolLoopResult = this.toolLoop.run(SCENARIO.input());
        return new ToolLoopResponse(
                "actual-chat-client-tool-loop",
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

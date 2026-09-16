package io.github.ultramancode.springai.privacy.sample.scenario;

import io.github.ultramancode.springai.privacy.core.PiiTokenizationResult;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.sample.dto.AdvisorChatResponse;
import io.github.ultramancode.springai.privacy.sample.dto.DemoScenarioResponse;
import io.github.ultramancode.springai.privacy.sample.dto.ProtectedPromptResponse;
import io.github.ultramancode.springai.privacy.sample.dto.RagResponse;
import io.github.ultramancode.springai.privacy.sample.dto.SecurityBoundaryResponse;
import io.github.ultramancode.springai.privacy.sample.dto.ToolLoopResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class PrivacyDemoService {

    private final PrivacyService privacyService;
    private final ChatClient chatClient;
    private final PrivacyDemoRag rag;
    private final PrivacyDemoToolLoop toolLoop;
    private final PrivacyDemoMcpToolLoop mcpToolLoop;

    PrivacyDemoService(
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

    public DemoScenarioResponse scenario(Locale locale) {
        return new DemoScenarioResponse(PrivacyDemoScenario.forLocale(locale).input());
    }

    public AdvisorChatResponse chatClient() {
        return chatClient(PrivacyDemoScenario.ENGLISH.input());
    }

    public AdvisorChatResponse chatClient(String input) {
        String modelResponse = this.chatClient.prompt().user(input).call().content();
        return new AdvisorChatResponse(modelResponse, this.privacyService.activeSessionCount());
    }

    public ProtectedPromptResponse protectScenario(Locale locale) {
        return protect(PrivacyDemoScenario.forLocale(locale).input());
    }

    public ProtectedPromptResponse protect(String input) {
        try (PrivacySession session = this.privacyService.openSession()) {
            PiiTokenizationResult tokenization = this.privacyService.analyzeAndTokenize(session.handle(), input);
            return PrivacyDemoResponseMapper.toProtectedPromptResponse(tokenization);
        }
    }

    public RagResponse rag(Locale locale) {
        PrivacyDemoRag.Result result = this.rag.run(PrivacyDemoLocale.from(locale));
        return PrivacyDemoResponseMapper.toRagResponse(result, this.privacyService.activeSessionCount());
    }

    public ToolLoopResponse toolLoop(Locale locale) {
        PrivacyDemoLocale demoLocale = PrivacyDemoLocale.from(locale);
        String input = PrivacyDemoScenario.forLocale(demoLocale).input();
        PrivacyDemoToolLoop.Result result = this.toolLoop.run(input, demoLocale);
        return PrivacyDemoResponseMapper.toToolLoopResponse(
                "actual-chat-client-tool-loop",
                result,
                this.privacyService.activeSessionCount()
        );
    }

    public ToolLoopResponse mcpToolLoop(Locale locale) {
        PrivacyDemoLocale demoLocale = PrivacyDemoLocale.from(locale);
        String input = PrivacyDemoScenario.forLocale(demoLocale).input();
        PrivacyDemoToolLoop.Result result = this.mcpToolLoop.run(input, demoLocale);
        return PrivacyDemoResponseMapper.toToolLoopResponse(
                "actual-streamable-http-mcp-tool-loop",
                result,
                this.privacyService.activeSessionCount()
        );
    }

    public SecurityBoundaryResponse securityToolBoundary(Locale locale) {
        PrivacyDemoLocale demoLocale = PrivacyDemoLocale.from(locale);
        String input = PrivacyDemoScenario.forLocale(demoLocale).input();
        PrivacyDemoToolLoop.SecurityRun generalEmployee = this.toolLoop.runSecurityScenario(
                input,
                demoLocale,
                PrivacyDemoSecurityPolicy.Role.GENERAL_EMPLOYEE
        );
        PrivacyDemoToolLoop.SecurityRun customerSupport = this.toolLoop.runSecurityScenario(
                input,
                demoLocale,
                PrivacyDemoSecurityPolicy.Role.CUSTOMER_SUPPORT
        );
        return PrivacyDemoResponseMapper.toSecurityBoundaryResponse(
                securityRequestSummary(demoLocale),
                generalEmployee,
                customerSupport,
                this.privacyService.activeSessionCount()
        );
    }

    private static String securityRequestSummary(PrivacyDemoLocale locale) {
        return locale == PrivacyDemoLocale.KO
                ? "고객정보를 조회해 주세요."
                : "Look up the customer information.";
    }
}

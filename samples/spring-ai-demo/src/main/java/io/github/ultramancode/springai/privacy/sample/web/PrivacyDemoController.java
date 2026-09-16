package io.github.ultramancode.springai.privacy.sample.web;

import io.github.ultramancode.springai.privacy.sample.dto.AdvisorChatResponse;
import io.github.ultramancode.springai.privacy.sample.dto.DemoRequest;
import io.github.ultramancode.springai.privacy.sample.dto.DemoScenarioResponse;
import io.github.ultramancode.springai.privacy.sample.dto.ProtectedPromptResponse;
import io.github.ultramancode.springai.privacy.sample.dto.RagResponse;
import io.github.ultramancode.springai.privacy.sample.dto.SecurityBoundaryResponse;
import io.github.ultramancode.springai.privacy.sample.dto.ToolLoopResponse;
import io.github.ultramancode.springai.privacy.sample.scenario.PrivacyDemoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@RestController
@RequestMapping("/demo")
public class PrivacyDemoController {

    private final PrivacyDemoService demoService;

    public PrivacyDemoController(PrivacyDemoService demoService) {
        this.demoService = demoService;
    }

    @GetMapping("/scenario")
    public DemoScenarioResponse scenario(Locale locale) {
        return this.demoService.scenario(locale);
    }

    @GetMapping("/chat-client")
    public AdvisorChatResponse chatClient() {
        return this.demoService.chatClient();
    }

    @PostMapping("/chat-client")
    public AdvisorChatResponse chatClient(@RequestBody DemoRequest request) {
        String input = requireText(request);
        return this.demoService.chatClient(input);
    }

    @GetMapping("/protect")
    public ProtectedPromptResponse protect(Locale locale) {
        return this.demoService.protectScenario(locale);
    }

    @PostMapping("/protect")
    public ProtectedPromptResponse protect(@RequestBody DemoRequest request) {
        String input = requireText(request);
        return this.demoService.protect(input);
    }

    @GetMapping("/rag")
    public RagResponse rag(Locale locale) {
        return this.demoService.rag(locale);
    }

    @GetMapping("/tool-loop")
    public ToolLoopResponse toolLoop(Locale locale) {
        return this.demoService.toolLoop(locale);
    }

    @GetMapping("/mcp-tool-loop")
    public ToolLoopResponse mcpToolLoop(Locale locale) {
        return this.demoService.mcpToolLoop(locale);
    }

    @GetMapping("/security-tool-boundary")
    public SecurityBoundaryResponse securityToolBoundary(Locale locale) {
        return this.demoService.securityToolBoundary(locale);
    }

    private static String requireText(DemoRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must not be blank");
        }
        return request.text();
    }
}

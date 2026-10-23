package io.github.ultramancode.springai.privacy.sample.privacy.controller;

import io.github.ultramancode.springai.privacy.sample.privacy.dto.request.PromptRequest;
import io.github.ultramancode.springai.privacy.sample.privacy.dto.response.ChatClientDemoResponse;
import io.github.ultramancode.springai.privacy.sample.privacy.dto.response.DemoInputResponse;
import io.github.ultramancode.springai.privacy.sample.privacy.dto.response.ProtectedPromptResponse;
import io.github.ultramancode.springai.privacy.sample.privacy.service.ChatClientDemoService;
import io.github.ultramancode.springai.privacy.sample.privacy.service.PromptProtectionDemoService;
import io.github.ultramancode.springai.privacy.sample.shared.model.CustomerDemoData;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    private final PromptProtectionDemoService promptProtectionService;
    private final ChatClientDemoService chatClientService;

    public PrivacyDemoController(
            PromptProtectionDemoService promptProtectionService,
            ChatClientDemoService chatClientService
    ) {
        this.promptProtectionService = promptProtectionService;
        this.chatClientService = chatClientService;
    }

    @GetMapping("/example-input")
    public ResponseEntity<DemoInputResponse> getDemoInput(Locale locale) {
        return ResponseEntity.ok(new DemoInputResponse(CustomerDemoData.getByLocale(locale).input()));
    }

    @GetMapping("/protect")
    public ResponseEntity<ProtectedPromptResponse> protectDemoPrompt(Locale locale) {
        return ResponseEntity.ok(this.promptProtectionService.runPromptProtectionDemo(locale));
    }

    @PostMapping("/protect")
    public ResponseEntity<ProtectedPromptResponse> protectPrompt(@RequestBody PromptRequest request) {
        String input = requireText(request);
        return ResponseEntity.ok(this.promptProtectionService.protect(input));
    }

    @GetMapping("/chat-client")
    public ResponseEntity<ChatClientDemoResponse> runChatClientDemo() {
        return ResponseEntity.ok(this.chatClientService.runChatClientDemo());
    }

    @PostMapping("/chat-client")
    public ResponseEntity<ChatClientDemoResponse> chat(@RequestBody PromptRequest request) {
        String input = requireText(request);
        return ResponseEntity.ok(this.chatClientService.chat(input));
    }

    private static String requireText(PromptRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must not be blank");
        }
        return request.text();
    }
}

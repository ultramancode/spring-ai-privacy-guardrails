package io.github.ultramancode.springai.privacy.sample.rag.controller;

import io.github.ultramancode.springai.privacy.sample.rag.dto.response.RagDemoResponse;
import io.github.ultramancode.springai.privacy.sample.rag.service.RagDemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/demo/rag")
public class RagDemoController {

    private final RagDemoService ragService;

    public RagDemoController(RagDemoService ragService) {
        this.ragService = ragService;
    }

    @GetMapping
    public ResponseEntity<RagDemoResponse> runRagDemo(Locale locale) {
        return ResponseEntity.ok(this.ragService.runRagDemo(locale));
    }
}

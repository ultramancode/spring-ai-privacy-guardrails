package io.github.ultramancode.springai.privacy.sample.localtool.controller;

import io.github.ultramancode.springai.privacy.sample.localtool.service.LocalToolLoopDemoService;
import io.github.ultramancode.springai.privacy.sample.shared.tool.dto.response.ToolLoopDemoResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/demo/tool-loop")
public class LocalToolLoopDemoController {

    private final LocalToolLoopDemoService toolLoopService;

    public LocalToolLoopDemoController(LocalToolLoopDemoService toolLoopService) {
        this.toolLoopService = toolLoopService;
    }

    @GetMapping
    public ResponseEntity<ToolLoopDemoResponse> runLocalToolLoopDemo(Locale locale) {
        return ResponseEntity.ok(this.toolLoopService.runLocalToolLoopDemo(locale));
    }
}

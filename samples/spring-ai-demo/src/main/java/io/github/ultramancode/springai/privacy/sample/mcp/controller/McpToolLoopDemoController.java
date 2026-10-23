package io.github.ultramancode.springai.privacy.sample.mcp.controller;

import io.github.ultramancode.springai.privacy.sample.mcp.service.McpToolLoopDemoService;
import io.github.ultramancode.springai.privacy.sample.shared.tool.dto.response.ToolLoopDemoResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/demo/mcp-tool-loop")
public class McpToolLoopDemoController {

    private final McpToolLoopDemoService mcpToolLoopService;

    public McpToolLoopDemoController(McpToolLoopDemoService mcpToolLoopService) {
        this.mcpToolLoopService = mcpToolLoopService;
    }

    @GetMapping
    public ResponseEntity<ToolLoopDemoResponse> runMcpToolLoopDemo(Locale locale) {
        return ResponseEntity.ok(this.mcpToolLoopService.runMcpToolLoopDemo(locale));
    }
}

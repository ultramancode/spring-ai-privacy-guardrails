package io.github.ultramancode.springai.privacy.sample.security.controller;

import io.github.ultramancode.springai.privacy.sample.security.dto.response.SecurityDemoResponse;
import io.github.ultramancode.springai.privacy.sample.security.service.SecurityDemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/demo/security-tool-boundary")
public class SecurityDemoController {

    private final SecurityDemoService securityService;

    public SecurityDemoController(SecurityDemoService securityService) {
        this.securityService = securityService;
    }

    @GetMapping
    public ResponseEntity<SecurityDemoResponse> runSecurityDemo(Locale locale) {
        return ResponseEntity.ok(this.securityService.runSecurityDemo(locale));
    }
}

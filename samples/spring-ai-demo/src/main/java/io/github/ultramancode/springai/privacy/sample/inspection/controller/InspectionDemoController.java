package io.github.ultramancode.springai.privacy.sample.inspection.controller;

import io.github.ultramancode.springai.privacy.sample.inspection.dto.response.InspectionDemoResponse;
import io.github.ultramancode.springai.privacy.sample.inspection.model.InspectionDemoCaseType;
import io.github.ultramancode.springai.privacy.sample.inspection.service.InspectionDemoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@RestController
@RequestMapping("/demo/inspection")
public class InspectionDemoController {

    private final InspectionDemoService inspectionService;

    public InspectionDemoController(InspectionDemoService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @GetMapping
    public ResponseEntity<InspectionDemoResponse> runInspectionDemo(
            Locale locale,
            @RequestParam(defaultValue = "block") String example
    ) {
        InspectionDemoCaseType caseType = switch (example) {
            case "block" -> InspectionDemoCaseType.BLOCK;
            case "allow" -> InspectionDemoCaseType.ALLOW;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "example must be block or allow");
        };
        return ResponseEntity.ok(this.inspectionService.runInspectionDemo(locale, caseType));
    }
}

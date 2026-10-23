package io.github.ultramancode.springai.privacy.sample.inspection.dto.response;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;

public record InspectionEvidenceResponse(String inspectedText, InspectionReport report) {
}

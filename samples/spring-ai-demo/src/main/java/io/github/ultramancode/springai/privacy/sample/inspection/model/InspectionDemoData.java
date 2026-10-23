package io.github.ultramancode.springai.privacy.sample.inspection.model;

public record InspectionDemoData(
        String userRequest,
        String modelResponse,
        InspectionScope inspectionScope
) {
}

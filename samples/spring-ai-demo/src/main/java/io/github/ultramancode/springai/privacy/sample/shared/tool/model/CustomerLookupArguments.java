package io.github.ultramancode.springai.privacy.sample.shared.tool.model;

public record CustomerLookupArguments(
        String employeeId,
        String email,
        String phone,
        String customerId
) {
}

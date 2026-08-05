package io.github.ultramancode.springai.privacy.sample;

import java.util.List;

record PrivacyDemoScenario(
        String employeeId,
        String email,
        String phone,
        String customerId
) {

    static final PrivacyDemoScenario DEFAULT = new PrivacyDemoScenario(
            "EMP-1234",
            "test@example.com",
            "010-1234-5678",
            "CUST-123456"
    );

    String input() {
        return "직원번호는 %s이고 이메일은 %s, 전화번호는 %s, 고객번호는 %s입니다."
                .formatted(this.employeeId, this.email, this.phone, this.customerId);
    }

    List<String> originalValues() {
        return List.of(this.employeeId, this.email, this.phone, this.customerId);
    }

    List<String> deniedToolValues() {
        return List.of(this.employeeId, this.email, this.phone);
    }

    List<String> allowedToolValues() {
        return List.of(this.customerId);
    }
}

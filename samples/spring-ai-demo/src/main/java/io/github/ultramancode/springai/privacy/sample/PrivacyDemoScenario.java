package io.github.ultramancode.springai.privacy.sample;

import java.util.List;
import java.util.Locale;

record PrivacyDemoScenario(
        String employeeId,
        String email,
        String phone,
        String customerId,
        String input
) {

    private static final String EMPLOYEE_ID = "EMP-1234";
    private static final String EMAIL = "test@example.com";
    private static final String PHONE = "010-1234-5678";
    private static final String CUSTOMER_ID = "CUST-123456";

    static final PrivacyDemoScenario ENGLISH = new PrivacyDemoScenario(
            EMPLOYEE_ID,
            EMAIL,
            PHONE,
            CUSTOMER_ID,
            "Employee ID is %s, email is %s, phone is %s, and customer ID is %s."
                    .formatted(EMPLOYEE_ID, EMAIL, PHONE, CUSTOMER_ID)
    );

    static final PrivacyDemoScenario KOREAN = new PrivacyDemoScenario(
            EMPLOYEE_ID,
            EMAIL,
            PHONE,
            CUSTOMER_ID,
            "직원번호는 %s이고, 이메일은 %s, 전화번호는 %s, 고객번호는 %s입니다."
                    .formatted(EMPLOYEE_ID, EMAIL, PHONE, CUSTOMER_ID)
    );

    static final PrivacyDemoScenario DEFAULT = ENGLISH;

    static PrivacyDemoScenario forLocale(Locale locale) {
        return PrivacyDemoLocale.from(locale) == PrivacyDemoLocale.KO ? KOREAN : ENGLISH;
    }

    static PrivacyDemoScenario forLocale(PrivacyDemoLocale locale) {
        return locale == PrivacyDemoLocale.KO ? KOREAN : ENGLISH;
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

enum PrivacyDemoLocale {
    EN,
    KO;

    static PrivacyDemoLocale from(Locale locale) {
        return locale != null && Locale.KOREAN.getLanguage().equals(locale.getLanguage()) ? KO : EN;
    }

    String protectedResult(String source, String result) {
        if (this == KO) {
            return "로컬 모델이 보호된 %s 결과를 수신했습니다: %s".formatted(source, result);
        }
        return "Local model received protected %s result: %s".formatted(source, result);
    }
}

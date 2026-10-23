package io.github.ultramancode.springai.privacy.sample.shared.model;

import java.util.List;
import java.util.Locale;

public record CustomerDemoData(
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

    private static final CustomerDemoData ENGLISH = new CustomerDemoData(
            EMPLOYEE_ID,
            EMAIL,
            PHONE,
            CUSTOMER_ID,
            "Employee ID is %s, email is %s, phone is %s, and customer ID is %s."
                    .formatted(EMPLOYEE_ID, EMAIL, PHONE, CUSTOMER_ID)
    );

    private static final CustomerDemoData KOREAN = new CustomerDemoData(
            EMPLOYEE_ID,
            EMAIL,
            PHONE,
            CUSTOMER_ID,
            "직원번호는 %s이고, 이메일은 %s, 전화번호는 %s, 고객번호는 %s입니다."
                    .formatted(EMPLOYEE_ID, EMAIL, PHONE, CUSTOMER_ID)
    );

    public static final CustomerDemoData DEFAULT = ENGLISH;

    public static CustomerDemoData getByLocale(Locale locale) {
        DemoLanguage language = DemoLanguage.from(locale);
        return getByLanguage(language);
    }

    public static CustomerDemoData getByLanguage(DemoLanguage language) {
        if (language == DemoLanguage.KO) {
            return KOREAN;
        }
        return ENGLISH;
    }

    public List<String> originalValues() {
        return List.of(this.employeeId, this.email, this.phone, this.customerId);
    }

    public List<String> deniedToolValues() {
        return List.of(this.employeeId, this.email, this.phone);
    }

    public List<String> allowedToolValues() {
        return List.of(this.customerId);
    }
}

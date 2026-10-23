package io.github.ultramancode.springai.privacy.sample.shared.security.model;

public enum DemoRole {

    GENERAL_EMPLOYEE("ROLE_EMPLOYEE"),
    CUSTOMER_SUPPORT("ROLE_CUSTOMER_SUPPORT");

    private final String authority;

    DemoRole(String authority) {
        this.authority = authority;
    }

    public String authority() {
        return this.authority;
    }
}

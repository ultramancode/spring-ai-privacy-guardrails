package io.github.ultramancode.springai.privacy.core.rules;

/** Selection IDs for the built-in PII rules. */
public final class BuiltInPiiRuleIds {
    /** Email address rule. */
    public static final String EMAIL = "email";

    /** Credit card number rule. */
    public static final String CREDIT_CARD = "credit-card";

    /** Korean mobile phone number rule. */
    public static final String KR_MOBILE_PHONE = "kr-mobile-phone";

    /** Korean resident registration number rule. */
    public static final String KR_RRN = "kr-rrn";

    /** Korean foreigner registration number rule. */
    public static final String KR_FRN = "kr-frn";

    /** Korean business registration number rule. */
    public static final String KR_BRN = "kr-brn";

    /** Korean driver's license number rule. */
    public static final String KR_DRIVER_LICENSE = "kr-driver-license";

    private BuiltInPiiRuleIds() {
    }
}

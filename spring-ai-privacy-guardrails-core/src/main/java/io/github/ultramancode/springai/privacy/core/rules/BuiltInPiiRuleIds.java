package io.github.ultramancode.springai.privacy.core.rules;

/** Selection IDs for the built-in PII rules. */
public final class BuiltInPiiRuleIds {
    /** Email address rule. */
    public static final String EMAIL = "email";

    /** Credit card number rule. */
    public static final String CREDIT_CARD = "credit-card";

    /** International bank account number rule. */
    public static final String IBAN = "iban";

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

    /** US phone number rule. */
    public static final String US_PHONE = "us-phone";

    /** US Social Security number rule. */
    public static final String US_SSN = "us-ssn";

    /** US Individual Taxpayer Identification Number rule. */
    public static final String US_ITIN = "us-itin";

    /** US Medicare Beneficiary Identifier rule. */
    public static final String US_MBI = "us-mbi";

    private BuiltInPiiRuleIds() {
    }
}

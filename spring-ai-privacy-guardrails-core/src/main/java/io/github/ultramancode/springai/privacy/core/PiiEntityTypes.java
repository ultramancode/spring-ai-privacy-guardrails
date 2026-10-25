package io.github.ultramancode.springai.privacy.core;

/**
 * Entity-type labels used by the library. APIs also accept application-defined labels
 * following the syntax defined by {@link EntityTypeRegistry#requireValidEntityType(String)}.
 */
public final class PiiEntityTypes {
    /** Generic personal information. */
    public static final String PII = "PII";

    /** Person name. */
    public static final String PERSON = "PERSON";

    /** Organization name. */
    public static final String ORGANIZATION = "ORGANIZATION";

    /** Geographic location. */
    public static final String LOCATION = "LOCATION";

    /** Email address. */
    public static final String EMAIL_ADDRESS = "EMAIL_ADDRESS";

    /** Phone number. */
    public static final String PHONE_NUMBER = "PHONE_NUMBER";

    /** National identification number. */
    public static final String NATIONAL_ID = "NATIONAL_ID";

    /** Payment card number. */
    public static final String CREDIT_CARD = "CREDIT_CARD";

    /** Date or time. */
    public static final String DATE_TIME = "DATE_TIME";

    /** IP address. */
    public static final String IP_ADDRESS = "IP_ADDRESS";

    /** Uniform resource locator. */
    public static final String URL = "URL";

    /** International bank account number. */
    public static final String IBAN_CODE = "IBAN_CODE";

    /** Cryptocurrency address. */
    public static final String CRYPTO = "CRYPTO";

    /** Nationality, religious affiliation, or political affiliation. */
    public static final String NRP = "NRP";

    /** Medical license identifier. */
    public static final String MEDICAL_LICENSE = "MEDICAL_LICENSE";

    /** Korean resident registration number. */
    public static final String KOREAN_RRN = "KOREAN_RRN";

    private PiiEntityTypes() {
    }
}

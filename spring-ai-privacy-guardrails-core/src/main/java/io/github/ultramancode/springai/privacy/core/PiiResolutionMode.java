package io.github.ultramancode.springai.privacy.core;

/** Strategies for selecting evidence when multiple analyzer providers are configured. */
public enum PiiResolutionMode {

    /** Resolves the union of evidence from every configured analyzer. */
    UNION,

    /** Resolves evidence only from the primary and explicitly supplemental providers. */
    PRIMARY,

    /**
     * Uses primary and supplemental providers normally, adding successful
     * fallback-only providers when the primary fails.
     */
    PRIMARY_WITH_FALLBACK
}

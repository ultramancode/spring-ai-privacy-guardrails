package io.github.ultramancode.springai.privacy.core;

import java.math.BigDecimal;

/**
 * Internal scalar text rules shared by core and integration modules.
 *
 * @hidden
 */
public final class ScalarAnalysisText {

    private ScalarAnalysisText() {
    }

    /** Converts a validated string or number after the caller checks expansion limits. */
    public static String toAnalysisText(Object scalar) {
        if (scalar instanceof String text) {
            return text;
        }
        if (scalar instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (scalar instanceof Number number) {
            return number.toString();
        }
        throw new IllegalArgumentException("scalar must be a JSON string or number");
    }

    /** Returns the plain decimal length without allocating the expanded string. */
    public static long plainDecimalLength(BigDecimal decimal) {
        long signCharacters = decimal.signum() < 0 ? 1L : 0L;
        long precision = decimal.precision();
        long scale = decimal.scale();

        // Zero with a nonpositive scale renders as just "0"
        if (decimal.signum() == 0 && scale <= 0) {
            return 1L;
        }

        // Nonpositive scale renders without a decimal point and may append trailing zeros
        if (scale <= 0) {
            return signCharacters + precision - scale;
        }

        // When digits reach the integer part, only the decimal point adds a character
        if (precision > scale) {
            return signCharacters + precision + 1L;
        }

        // Otherwise "0." precedes exactly scale fractional digits
        return signCharacters + 2L + scale;
    }
}

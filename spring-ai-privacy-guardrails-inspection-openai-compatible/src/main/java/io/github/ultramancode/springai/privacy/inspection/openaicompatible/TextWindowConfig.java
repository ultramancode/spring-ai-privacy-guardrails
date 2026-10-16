package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

/**
 * Character window limits for HTTP inspection. Lengths count Unicode code points.
 *
 * @param maxCharacters positive maximum number of code points in each window
 * @param overlapCharacters code points repeated between adjacent windows, from zero to maxCharacters minus one
 * @param maxWindows positive maximum number of HTTP windows across all request segments
 */
public record TextWindowConfig(int maxCharacters, int overlapCharacters, int maxWindows) {

    public TextWindowConfig {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        if (overlapCharacters < 0 || overlapCharacters >= maxCharacters) {
            throw new IllegalArgumentException("overlapCharacters must be non-negative and less than maxCharacters");
        }
        if (maxWindows < 1) {
            throw new IllegalArgumentException("maxWindows must be positive");
        }
    }
}

package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

/**
 * Character window settings for HTTP inspection. Lengths count Unicode code points.
 * The last window of each segment may be shorter than {@code windowSize}.
 *
 * @param windowSize positive number of characters per window
 * @param overlapSize number of characters repeated between adjacent windows,
 *        from {@code 0} to {@code windowSize - 1}
 * @param maxWindows positive maximum number of HTTP windows across all request segments
 */
public record TextWindowConfig(int windowSize, int overlapSize, int maxWindows) {

    public TextWindowConfig {
        if (windowSize < 1) {
            throw new IllegalArgumentException("windowSize must be positive");
        }
        if (overlapSize < 0 || overlapSize >= windowSize) {
            throw new IllegalArgumentException("overlapSize must be non-negative and less than windowSize");
        }
        if (maxWindows < 1) {
            throw new IllegalArgumentException("maxWindows must be positive");
        }
    }
}

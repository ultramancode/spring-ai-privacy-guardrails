package io.github.ultramancode.springai.privacy.inspection.rules;

import com.google.re2j.Pattern;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.util.Objects;

/** Configured literal or linear-time RE2 pattern. Never logs the pattern or matching text. */
public final class InspectionRule {

    private final String id;
    private final InspectionFinding.Category category;
    private final Pattern pattern;

    private InspectionRule(
            String id, InspectionFinding.Category category, String expression, boolean literal) {
        this.id = ContentSegment.requireIdentifier(id);
        this.category = Objects.requireNonNull(category, "category");
        if (expression == null || expression.isEmpty() || expression.length() > 4096) {
            throw new IllegalArgumentException("A rule needs a nonempty, bounded expression");
        }
        try {
            this.pattern = Pattern.compile(literal ? Pattern.quote(expression) : expression);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid RE2 rule expression");
        }
    }

    public static InspectionRule literal(String id, String text) {
        return new InspectionRule(id, InspectionFinding.Category.RULE_MATCH, text, true);
    }

    public static InspectionRule regex(
            String id, InspectionFinding.Category category, String expression) {
        return new InspectionRule(id, category, expression, false);
    }

    String id() {
        return id;
    }

    InspectionFinding.Category category() {
        return category;
    }

    boolean matches(String text) {
        return pattern.matcher(text).find();
    }

    @Override
    public String toString() {
        return "InspectionRule[id=" + id + ", expression=<redacted>]";
    }
}

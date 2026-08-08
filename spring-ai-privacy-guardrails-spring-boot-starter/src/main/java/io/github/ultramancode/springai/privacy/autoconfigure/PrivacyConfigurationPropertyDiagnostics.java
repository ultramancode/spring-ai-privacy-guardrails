package io.github.ultramancode.springai.privacy.autoconfigure;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Warns about high-confidence typos in the library-owned, fixed property surface. */
final class PrivacyConfigurationPropertyDiagnostics {

    private static final Log logger = LogFactory.getLog(PrivacyConfigurationPropertyDiagnostics.class);

    private static final ConfigurationPropertyName ROOT =
            ConfigurationPropertyName.of("spring.ai.privacy");
    private static final int ROOT_ELEMENTS = ROOT.getNumberOfElements();

    static final List<String> ROOT_PROPERTIES = List.of(
            "output",
            "response-inspection",
            "analysis",
            "regex",
            "tools",
            "enabled"
    );
    static final List<String> OUTPUT_PROPERTIES = List.of(
            "enabled",
            "action",
            "block-exception-message"
    );
    static final List<String> RESPONSE_INSPECTION_PROPERTIES = List.of(
            "max-stream-frames",
            "max-characters",
            "max-media-bytes",
            "stream-idle-timeout"
    );
    static final List<String> ANALYSIS_PROPERTIES = List.of(
            "language",
            "included-entity-types",
            "minimum-score",
            "mode",
            "primary-provider",
            "supplemental-providers",
            "failure-policy",
            "provider-minimum-scores",
            "entity-aliases",
            "type-conflict-fallback"
    );
    static final List<String> REGEX_PROPERTIES = List.of("enabled", "rules");
    static final List<String> REGEX_RULE_PROPERTIES = List.of(
            "entity-type",
            "pattern",
            "score",
            "capture-group"
    );
    static final List<String> TOOLS_PROPERTIES = List.of("disclosures");

    PrivacyConfigurationPropertyDiagnostics(Environment environment) {
        findLikelyTypos(environment).forEach(PrivacyConfigurationPropertyDiagnostics::warn);
    }

    static Set<Suggestion> findLikelyTypos(Environment environment) {
        Set<Suggestion> suggestions = new TreeSet<>();
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (source instanceof IterableConfigurationPropertySource iterableSource) {
                collectLikelyTypos(iterableSource, suggestions);
            }
        }
        return suggestions;
    }

    /**
     * Collects suggestions on a best-effort basis. If name enumeration fails, the
     * source contributes no partial results and cannot block host application startup.
     */
    private static void collectLikelyTypos(
            IterableConfigurationPropertySource source,
            Set<Suggestion> suggestions
    ) {
        Set<Suggestion> sourceSuggestions = new TreeSet<>();
        Iterator<ConfigurationPropertyName> names;
        try {
            names = source.iterator();
        } catch (RuntimeException ignored) {
            return;
        }
        while (true) {
            ConfigurationPropertyName name;
            try {
                if (!names.hasNext()) {
                    suggestions.addAll(sourceSuggestions);
                    return;
                }
                name = names.next();
            } catch (RuntimeException ignored) {
                return;
            }
            if (name == null) {
                return;
            }
            diagnosePropertyName(name).ifPresent(sourceSuggestions::add);
        }
    }

    private static Optional<Suggestion> diagnosePropertyName(ConfigurationPropertyName name) {
        if (!ROOT.isAncestorOf(name)) {
            return Optional.empty();
        }
        Optional<SegmentMatch> rootMatchCandidate = closestSegmentMatch(
                name,
                ROOT_ELEMENTS,
                ROOT_PROPERTIES
        );
        if (rootMatchCandidate.isEmpty()) {
            return Optional.empty();
        }
        SegmentMatch rootMatch = rootMatchCandidate.get();
        int propertyIndex = ROOT_ELEMENTS + rootMatch.consumedElements();
        if (!rootMatch.exact()) {
            // Only this typo can silently leave privacy auto-configuration inactive;
            // other near-root names may belong to provider or host extensions.
            if (propertyIndex == name.getNumberOfElements()
                    && rootMatch.expected().equals("enabled")) {
                return suggestion(ROOT.toString(), rootMatch);
            }
            return Optional.empty();
        }
        if (propertyIndex == name.getNumberOfElements()) {
            return Optional.empty();
        }
        String propertyRoot = ROOT + "." + rootMatch.expected();
        return switch (rootMatch.expected()) {
            case "enabled" -> Optional.empty();
            case "output" -> diagnoseFixedProperties(
                    name,
                    propertyIndex,
                    propertyRoot,
                    OUTPUT_PROPERTIES
            );
            case "response-inspection" -> diagnoseFixedProperties(
                    name,
                    propertyIndex,
                    propertyRoot,
                    RESPONSE_INSPECTION_PROPERTIES
            );
            case "analysis" -> diagnoseFixedProperties(
                    name,
                    propertyIndex,
                    propertyRoot,
                    ANALYSIS_PROPERTIES
            );
            case "regex" -> diagnoseRegex(name, propertyIndex, propertyRoot);
            case "tools" -> diagnoseFixedProperties(
                    name,
                    propertyIndex,
                    propertyRoot,
                    TOOLS_PROPERTIES
            );
            default -> Optional.empty();
        };
    }

    private static Optional<Suggestion> diagnoseFixedProperties(
            ConfigurationPropertyName name,
            int propertyIndex,
            String propertyRoot,
            List<String> knownProperties
    ) {
        Optional<SegmentMatch> match = closestSegmentMatch(name, propertyIndex, knownProperties);
        if (match.isPresent() && !match.get().exact()) {
            return suggestion(propertyRoot, match.get());
        }
        return Optional.empty();
    }

    private static Optional<Suggestion> diagnoseRegex(
            ConfigurationPropertyName name,
            int propertyIndex,
            String propertyRoot
    ) {
        Optional<SegmentMatch> propertyMatchCandidate = closestSegmentMatch(
                name,
                propertyIndex,
                REGEX_PROPERTIES
        );
        if (propertyMatchCandidate.isEmpty()) {
            return Optional.empty();
        }
        SegmentMatch propertyMatch = propertyMatchCandidate.get();
        if (!propertyMatch.exact()) {
            return suggestion(propertyRoot, propertyMatch);
        }
        int ruleIndex = propertyIndex + propertyMatch.consumedElements();
        if (!propertyMatch.expected().equals("rules") || name.getNumberOfElements() <= ruleIndex) {
            return Optional.empty();
        }
        if (!name.isNumericIndex(ruleIndex)) {
            return Optional.empty();
        }
        int rulePropertyIndex = ruleIndex + 1;
        if (name.getNumberOfElements() <= rulePropertyIndex) {
            return Optional.empty();
        }
        String rulesRoot = propertyRoot + ".rules[" + element(name, ruleIndex) + "]";
        return diagnoseFixedProperties(
                name,
                rulePropertyIndex,
                rulesRoot,
                REGEX_RULE_PROPERTIES
        );
    }

    private static Optional<Suggestion> suggestion(
            String propertyRoot,
            SegmentMatch match
    ) {
        return Optional.of(new Suggestion(
                propertyRoot + "." + match.actual(),
                propertyRoot + "." + match.expected()
        ));
    }

    private static String element(ConfigurationPropertyName name, int index) {
        return name.getElement(index, Form.DASHED);
    }

    /**
     * Returns the unique closest candidate within its typo threshold. Ambiguous ties
     * intentionally produce no match.
     */
    private static Optional<SegmentMatch> closestSegmentMatch(
            ConfigurationPropertyName name,
            int propertyIndex,
            List<String> candidates
    ) {
        SegmentMatch closest = null;
        int closestDistance = Integer.MAX_VALUE;
        boolean tied = false;
        for (String candidate : candidates) {
            for (SegmentVariant variant : candidateSegmentVariants(name, propertyIndex, candidate)) {
                if (variant.actual().equals(candidate)) {
                    return Optional.of(new SegmentMatch(
                            variant.actual(),
                            candidate,
                            variant.consumedElements(),
                            true
                    ));
                }
                int distance = levenshteinDistance(variant.actual(), candidate);
                SegmentMatch match = new SegmentMatch(
                        variant.actual(),
                        candidate,
                        variant.consumedElements(),
                        false
                );
                if (distance < closestDistance) {
                    closest = match;
                    closestDistance = distance;
                    tied = false;
                } else if (distance == closestDistance && !sameSuggestion(closest, match)) {
                    tied = true;
                }
            }
        }
        if (tied || closest == null || closestDistance > typoThreshold(closest.expected())) {
            return Optional.empty();
        }
        return Optional.of(closest);
    }

    /**
     * Builds comparable forms for one dashed element and for the equivalent element
     * sequence produced from relaxed operating-system environment variable names.
     */
    private static List<SegmentVariant> candidateSegmentVariants(
            ConfigurationPropertyName name,
            int propertyIndex,
            String candidate
    ) {
        List<SegmentVariant> variants = new ArrayList<>(2);
        String singleElement = element(name, propertyIndex);
        variants.add(new SegmentVariant(singleElement, 1));

        int candidateElements = candidate.split("-").length;
        if (candidateElements == 1
                || propertyIndex + candidateElements > name.getNumberOfElements()) {
            return variants;
        }
        StringBuilder joinedElements = new StringBuilder();
        for (int offset = 0; offset < candidateElements; offset++) {
            int elementIndex = propertyIndex + offset;
            if (name.isNumericIndex(elementIndex)) {
                return variants;
            }
            if (!joinedElements.isEmpty()) {
                joinedElements.append('-');
            }
            joinedElements.append(element(name, elementIndex));
        }
        String joined = joinedElements.toString();
        if (!joined.equals(singleElement)) {
            variants.add(new SegmentVariant(joined, candidateElements));
        }
        return variants;
    }

    private static boolean sameSuggestion(SegmentMatch left, SegmentMatch right) {
        return left != null
                && left.actual().equals(right.actual())
                && left.expected().equals(right.expected());
    }

    /** Returns the heuristic maximum distance, scaled by canonical segment length. */
    private static int typoThreshold(String expected) {
        if (expected.length() <= 4) {
            return 1;
        }
        if (expected.length() <= 8) {
            return 3;
        }
        return 4;
    }

    /**
     * Returns the Levenshtein distance used to identify likely property-name typos.
     * Counts single-character insertions, deletions, and substitutions.
     */
    private static int levenshteinDistance(String left, String right) {
        int[] previousCosts = new int[right.length() + 1];
        int[] currentCosts = new int[right.length() + 1];
        for (int rightIndex = 0; rightIndex <= right.length(); rightIndex++) {
            previousCosts[rightIndex] = rightIndex;
        }
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            currentCosts[0] = leftIndex;
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int substitutionPenalty = 1;
                if (left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1)) {
                    substitutionPenalty = 0;
                }

                int insertionCost = currentCosts[rightIndex - 1] + 1;
                int deletionCost = previousCosts[rightIndex] + 1;
                int substitutionCost = previousCosts[rightIndex - 1] + substitutionPenalty;

                int lowestCost = Math.min(insertionCost, deletionCost);
                currentCosts[rightIndex] = Math.min(lowestCost, substitutionCost);
            }
            int[] temporary = previousCosts;
            previousCosts = currentCosts;
            currentCosts = temporary;
        }
        return previousCosts[right.length()];
    }

    private static void warn(Suggestion suggestion) {
        logger.warn("Unrecognized Spring AI Privacy Guardrails configuration property '"
                + suggestion.actualName()
                + "'. Did you mean '"
                + suggestion.expectedName()
                + "'? No configuration value was included in this diagnostic.");
    }

    record Suggestion(String actualName, String expectedName) implements Comparable<Suggestion> {

        @Override
        public int compareTo(Suggestion other) {
            int actualComparison = this.actualName.compareTo(other.actualName);
            return actualComparison != 0
                    ? actualComparison
                    : this.expectedName.compareTo(other.expectedName);
        }

    }

    private record SegmentMatch(
            String actual,
            String expected,
            int consumedElements,
            boolean exact
    ) {
    }

    private record SegmentVariant(String actual, int consumedElements) {
    }

}

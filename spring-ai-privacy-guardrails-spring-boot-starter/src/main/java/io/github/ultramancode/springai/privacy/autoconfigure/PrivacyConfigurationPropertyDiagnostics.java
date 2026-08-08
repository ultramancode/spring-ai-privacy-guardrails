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

/** Warns about unrecognized names in the library-owned, fixed property surface. */
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
    static final Set<String> ANALYSIS_MAP_PROPERTIES = Set.of(
            "provider-minimum-scores",
            "entity-aliases"
    );
    static final Set<String> ANALYSIS_INDEXED_SCALAR_PROPERTIES = Set.of(
            "included-entity-types",
            "supplemental-providers"
    );
    static final List<String> REGEX_PROPERTIES = List.of("enabled", "rules");
    static final Set<String> REGEX_INDEXED_OBJECT_PROPERTIES = Set.of("rules");
    static final List<String> REGEX_RULE_PROPERTIES = List.of(
            "entity-type",
            "pattern",
            "score",
            "capture-group"
    );
    static final List<String> TOOLS_PROPERTIES = List.of("disclosures");
    static final Set<String> TOOLS_MAP_PROPERTIES = Set.of("disclosures");

    PrivacyConfigurationPropertyDiagnostics(Environment environment) {
        findDiagnostics(environment).forEach(PrivacyConfigurationPropertyDiagnostics::warn);
    }

    static Set<Diagnostic> findDiagnostics(Environment environment) {
        Set<Diagnostic> diagnostics = new TreeSet<>();
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (source instanceof IterableConfigurationPropertySource iterableSource) {
                collectDiagnostics(iterableSource, diagnostics);
            }
        }
        return diagnostics;
    }

    /**
     * Collects diagnostics on a best-effort basis. If name enumeration fails, the
     * source contributes no partial results and cannot block host application startup.
     */
    private static void collectDiagnostics(
            IterableConfigurationPropertySource source,
            Set<Diagnostic> diagnostics
    ) {
        Set<Diagnostic> sourceDiagnostics = new TreeSet<>();
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
                    diagnostics.addAll(sourceDiagnostics);
                    return;
                }
                name = names.next();
            } catch (RuntimeException ignored) {
                return;
            }
            if (name == null) {
                return;
            }
            diagnosePropertyName(name).ifPresent(sourceDiagnostics::add);
        }
    }

    private static Optional<Diagnostic> diagnosePropertyName(ConfigurationPropertyName name) {
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
            case "enabled" -> unrecognizedFixedProperty(propertyRoot);
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
                    ANALYSIS_PROPERTIES,
                    ANALYSIS_MAP_PROPERTIES,
                    ANALYSIS_INDEXED_SCALAR_PROPERTIES
            );
            case "regex" -> diagnoseRegex(name, propertyIndex, propertyRoot);
            case "tools" -> diagnoseFixedProperties(
                    name,
                    propertyIndex,
                    propertyRoot,
                    TOOLS_PROPERTIES,
                    TOOLS_MAP_PROPERTIES,
                    Set.of()
            );
            default -> Optional.empty();
        };
    }

    private static Optional<Diagnostic> diagnoseFixedProperties(
            ConfigurationPropertyName name,
            int propertyIndex,
            String propertyRoot,
            List<String> knownProperties
    ) {
        return diagnoseFixedProperties(
                name,
                propertyIndex,
                propertyRoot,
                knownProperties,
                Set.of(),
                Set.of()
        );
    }

    private static Optional<Diagnostic> diagnoseFixedProperties(
            ConfigurationPropertyName name,
            int propertyIndex,
            String propertyRoot,
            List<String> knownProperties,
            Set<String> mapProperties,
            Set<String> indexedScalarProperties
    ) {
        Optional<SegmentMatch> matchCandidate = closestSegmentMatch(
                name,
                propertyIndex,
                knownProperties
        );
        if (matchCandidate.isEmpty()) {
            return unrecognizedFixedProperty(propertyRoot);
        }
        SegmentMatch match = matchCandidate.get();
        if (!match.exact()) {
            return suggestion(propertyRoot, match);
        }
        int nextPropertyIndex = propertyIndex + match.consumedElements();
        if (nextPropertyIndex == name.getNumberOfElements()
                || mapProperties.contains(match.expected())) {
            return Optional.empty();
        }
        if (indexedScalarProperties.contains(match.expected())
                && name.isNumericIndex(nextPropertyIndex)
                && nextPropertyIndex + 1 == name.getNumberOfElements()) {
            return Optional.empty();
        }
        return unrecognizedFixedProperty(propertyRoot + "." + match.expected());
    }

    private static Optional<Diagnostic> diagnoseRegex(
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
            return unrecognizedFixedProperty(propertyRoot);
        }
        SegmentMatch propertyMatch = propertyMatchCandidate.get();
        if (!propertyMatch.exact()) {
            return suggestion(propertyRoot, propertyMatch);
        }
        int ruleIndex = propertyIndex + propertyMatch.consumedElements();
        if (!REGEX_INDEXED_OBJECT_PROPERTIES.contains(propertyMatch.expected())) {
            return name.getNumberOfElements() <= ruleIndex
                    ? Optional.empty()
                    : unrecognizedFixedProperty(
                            propertyRoot + "." + propertyMatch.expected()
                    );
        }
        if (name.getNumberOfElements() <= ruleIndex) {
            return Optional.empty();
        }
        if (!name.isNumericIndex(ruleIndex)) {
            return unrecognizedFixedProperty(propertyRoot + ".rules");
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

    private static Optional<Diagnostic> suggestion(
            String propertyRoot,
            SegmentMatch match
    ) {
        String actualName = propertyRoot + "." + match.actual();
        String expectedName = propertyRoot + "." + match.expected();
        return Optional.of(new Diagnostic(
                "Unrecognized Spring AI Privacy Guardrails configuration property '"
                        + actualName
                        + "'. Did you mean '"
                        + expectedName
                        + "'? No configuration value was included in this diagnostic."
        ));
    }

    private static Optional<Diagnostic> unrecognizedFixedProperty(String propertyRoot) {
        return Optional.of(new Diagnostic(
                "Unrecognized Spring AI Privacy Guardrails configuration property detected "
                        + "below fixed prefix '"
                        + propertyRoot
                        + "'. The unrecognized property name and configuration value were "
                        + "omitted from this diagnostic."
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

    private static void warn(Diagnostic diagnostic) {
        logger.warn(diagnostic.message());
    }

    record Diagnostic(String message) implements Comparable<Diagnostic> {

        @Override
        public int compareTo(Diagnostic other) {
            return this.message.compareTo(other.message);
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

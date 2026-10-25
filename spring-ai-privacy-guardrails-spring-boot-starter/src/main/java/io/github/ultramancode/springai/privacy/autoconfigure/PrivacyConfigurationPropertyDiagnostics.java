package io.github.ultramancode.springai.privacy.autoconfigure;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyConfigurationPropertyCandidateCollector.DiagnosticContext;
import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyConfigurationPropertyCandidateCollector.PropertyNameCandidate;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
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
        "processing",
        "analysis",
        "rules"
    );
    static final List<String> PROCESSING_PROPERTIES = List.of(
            "max-text-characters",
            "max-output-characters",
            "max-value-tree-characters",
            "max-value-tree-nodes",
            "max-depth",
            "max-analysis-segments",
            "max-result-spans"
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
    static final List<String> RULES_PROPERTIES = List.of("enabled", "presets", "include", "exclude");
    static final Set<String> RULES_INDEXED_SCALAR_PROPERTIES = Set.of("presets", "include", "exclude");

    PrivacyConfigurationPropertyDiagnostics(Environment environment) {
        findDiagnostics(environment).forEach(PrivacyConfigurationPropertyDiagnostics::warn);
    }

    static Set<Diagnostic> findDiagnostics(Environment environment) {
        Set<Diagnostic> diagnostics = new TreeSet<>();
        for (PropertyNameCandidate candidate :
                PrivacyConfigurationPropertyCandidateCollector.collect(environment)) {
            diagnosePropertyName(candidate.name(), candidate.context())
                    .ifPresent(diagnostics::add);
        }
        return diagnostics;
    }

    private static Optional<Diagnostic> diagnosePropertyName(
        ConfigurationPropertyName name,
        DiagnosticContext context
    ) {
        if (!ROOT.isAncestorOf(name)) {
            return Optional.empty();
        }
        Optional<SegmentMatch> rootMatchCandidate = closestSegmentMatch(
            name,
            ROOT_ELEMENTS,
            ROOT_PROPERTIES,
            context.systemEnvironmentMapping()
        );
        if (rootMatchCandidate.isEmpty()) {
            return Optional.empty();
        }
        SegmentMatch rootMatch = rootMatchCandidate.get();
        int propertyIndex = ROOT_ELEMENTS + rootMatch.consumedElements();
        // Unknown roots may belong to analyzer providers or application extensions.
        if (!rootMatch.exact() || propertyIndex == name.getNumberOfElements()) {
            return Optional.empty();
        }
        String propertyRoot = ROOT + "." + rootMatch.expected();
        return switch (rootMatch.expected()) {
            case "processing" -> diagnoseFixedPropertyPath(
                name,
                propertyIndex,
                propertyRoot,
                PROCESSING_PROPERTIES,
                context
            );
            case "analysis" -> diagnoseFixedPropertyPath(
                name,
                propertyIndex,
                propertyRoot,
                ANALYSIS_PROPERTIES,
                ANALYSIS_MAP_PROPERTIES,
                ANALYSIS_INDEXED_SCALAR_PROPERTIES,
                context
            );
            case "rules" -> diagnoseFixedPropertyPath(
                name,
                propertyIndex,
                propertyRoot,
                RULES_PROPERTIES,
                Set.of(),
                RULES_INDEXED_SCALAR_PROPERTIES,
                context
            );
            default -> Optional.empty();
        };
    }

    /** Diagnoses a fixed property path whose known properties have no descendants. */
    private static Optional<Diagnostic> diagnoseFixedPropertyPath(
            ConfigurationPropertyName name,
            int propertyIndex,
            String propertyRoot,
            List<String> knownProperties,
            DiagnosticContext context
    ) {
        return diagnoseFixedPropertyPath(
                name,
                propertyIndex,
                propertyRoot,
                knownProperties,
                Set.of(),
                Set.of(),
                context
        );
    }

    /**
     * Diagnoses one fixed property path while treating map descendants as opaque and
     * accepting one terminal numeric index for indexed scalar properties.
     */
    private static Optional<Diagnostic> diagnoseFixedPropertyPath(
            ConfigurationPropertyName name,
            int propertyIndex,
            String propertyRoot,
            List<String> knownProperties,
            Set<String> mapProperties,
            Set<String> indexedScalarProperties,
            DiagnosticContext context
    ) {
        Optional<SegmentMatch> matchCandidate = closestSegmentMatch(
                name,
                propertyIndex,
                knownProperties,
                context.systemEnvironmentMapping()
        );
        if (matchCandidate.isEmpty()) {
            return unrecognizedPropertyBelowPrefixDiagnostic(propertyRoot);
        }
        SegmentMatch match = matchCandidate.get();
        if (!match.exact()) {
            return typoSuggestionDiagnostic(propertyRoot, match);
        }
        int nextPropertyIndex = propertyIndex + match.consumedElements();
        if (mapProperties.contains(match.expected())) {
            // Dynamic map keys are application-controlled and may contain sensitive
            // identifiers. Leave aggregate binding details to Boot and do not diagnose
            // descendants from this best-effort scanner.
            return Optional.empty();
        }
        ConfigurationPropertyName canonicalProperty = ConfigurationPropertyName.of(propertyRoot)
                .append(match.expected());
        if (nextPropertyIndex == name.getNumberOfElements()) {
            return verifyCanonicalProperty(
                    context,
                    canonicalProperty,
                    propertyRoot,
                    match
            );
        }
        if (indexedScalarProperties.contains(match.expected())
                && name.isNumericIndex(nextPropertyIndex)
                && nextPropertyIndex + 1 == name.getNumberOfElements()) {
            return verifyCanonicalProperty(
                    context,
                    canonicalProperty.append(name.subName(nextPropertyIndex)),
                    propertyRoot,
                    match
            );
        }
        return unrecognizedPropertyBelowPrefixDiagnostic(
                propertyRoot + "." + match.expected()
        );
    }

    private static Optional<Diagnostic> verifyCanonicalProperty(
            DiagnosticContext context,
            ConfigurationPropertyName canonicalName,
            String propertyRoot,
            SegmentMatch match
    ) {
        if (canonicalPropertyResolution(context, canonicalName)
                != CanonicalPropertyResolution.UNRESOLVED) {
            return Optional.empty();
        }
        if (match.exact()
                && match.consumedElements() == 1
                && match.actual().equals(match.expected())) {
            return unrecognizedPropertyBelowPrefixDiagnostic(canonicalName.toString());
        }
        return typoSuggestionDiagnostic(propertyRoot, match);
    }

    /** Reduces a value-bearing lookup immediately to a presence-only state. */
    private static CanonicalPropertyResolution canonicalPropertyResolution(
            DiagnosticContext context,
            ConfigurationPropertyName canonicalName
    ) {
        if (!context.systemEnvironmentMapping()) {
            return CanonicalPropertyResolution.RESOLVED;
        }
        try {
            return context.source().getConfigurationProperty(canonicalName) != null
                    ? CanonicalPropertyResolution.RESOLVED
                    : CanonicalPropertyResolution.UNRESOLVED;
        } catch (RuntimeException ignored) {
            return CanonicalPropertyResolution.INDETERMINATE;
        }
    }

    private static Optional<Diagnostic> typoSuggestionDiagnostic(
            String propertyRoot,
            SegmentMatch match
    ) {
        String expectedName = propertyRoot + "." + match.expected();
        if (match.consumedElements() > 1) {
            return Optional.of(new Diagnostic(
                    "Unrecognized Spring AI Privacy Guardrails configuration property detected "
                            + "below fixed prefix '"
                            + propertyRoot
                            + "'. Did you mean '"
                            + expectedName
                            + "'? The unrecognized property name and configuration value were "
                            + "omitted from this diagnostic."
            ));
        }
        String actualName = propertyRoot + "." + match.actual();
        return Optional.of(new Diagnostic(
                "Unrecognized Spring AI Privacy Guardrails configuration property '"
                        + actualName
                        + "'. Did you mean '"
                        + expectedName
                        + "'? No configuration value was included in this diagnostic."
        ));
    }

    private static Optional<Diagnostic> unrecognizedPropertyBelowPrefixDiagnostic(
            String propertyRoot
    ) {
        return Optional.of(new Diagnostic(
                "Unrecognized Spring AI Privacy Guardrails configuration property detected "
                        + "below fixed prefix '"
                        + propertyRoot
                        + "'. The unrecognized property name and configuration value were "
                        + "omitted from this diagnostic."
        ));
    }

    private static String dashedElement(ConfigurationPropertyName name, int index) {
        return name.getElement(index, Form.DASHED);
    }

    private static String uniformElement(ConfigurationPropertyName name, int index) {
        return name.getElement(index, Form.UNIFORM);
    }

    private static String toUniformForm(String candidate) {
        return ConfigurationPropertyName.of(candidate).getElement(0, Form.UNIFORM);
    }

    /**
     * Returns the unique closest candidate within its typo threshold. Ambiguous ties
     * intentionally produce no match.
     */
    private static Optional<SegmentMatch> closestSegmentMatch(
            ConfigurationPropertyName name,
            int propertyIndex,
            List<String> candidates,
            boolean systemEnvironmentMapping
    ) {
        SegmentMatch closest = null;
        int closestDistance = Integer.MAX_VALUE;
        boolean tied = false;
        for (String candidate : candidates) {
            String candidateUniform = toUniformForm(candidate);
            for (SegmentVariant variant : candidateSegmentVariants(
                    name,
                    propertyIndex,
                    candidate,
                    systemEnvironmentMapping
            )) {
                if (variant.comparison().equals(candidateUniform)) {
                    return Optional.of(new SegmentMatch(
                            variant.display(),
                            candidate,
                            variant.consumedElements(),
                            true
                    ));
                }
                int distance = levenshteinDistance(
                        variant.comparison(),
                        candidateUniform
                );
                SegmentMatch match = new SegmentMatch(
                        variant.display(),
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
        if (tied || closest == null) {
            return Optional.empty();
        }
        String thresholdCandidate = toUniformForm(closest.expected());
        if (closestDistance > typoThreshold(thresholdCandidate)) {
            return Optional.empty();
        }
        return Optional.of(closest);
    }

    /**
     * Builds display and comparison forms for property elements, including compound
     * names split by Boot's system-environment mapping.
     */
    private static List<SegmentVariant> candidateSegmentVariants(
            ConfigurationPropertyName name,
            int propertyIndex,
            String candidate,
            boolean systemEnvironmentMapping
    ) {
        int candidateWordCount = candidate.split("-").length;
        List<SegmentVariant> variants = new ArrayList<>(candidateWordCount);
        String singleDisplay = dashedElement(name, propertyIndex);
        String singleComparison = uniformElement(name, propertyIndex);
        variants.add(new SegmentVariant(
                singleDisplay,
                singleComparison,
                1
        ));

        int elementsToJoin = Math.min(candidateWordCount, name.getNumberOfElements() - propertyIndex);
        if (!systemEnvironmentMapping || elementsToJoin < 2) {
            return variants;
        }
        StringBuilder joinedDisplay = new StringBuilder(singleDisplay);
        StringBuilder joinedComparison = new StringBuilder(singleComparison);
        for (int offset = 1; offset < elementsToJoin; offset++) {
            int elementIndex = propertyIndex + offset;
            if (name.isNumericIndex(elementIndex)) {
                return variants;
            }
            joinedDisplay.append('-').append(dashedElement(name, elementIndex));
            joinedComparison.append(uniformElement(name, elementIndex));
            variants.add(new SegmentVariant(
                    joinedDisplay.toString(),
                    joinedComparison.toString(),
                    offset + 1
            ));
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

    private record SegmentVariant(
            String display,
            String comparison,
            int consumedElements
    ) {
    }

    private enum CanonicalPropertyResolution {

        RESOLVED,
        UNRESOLVED,
        INDETERMINATE

    }

}

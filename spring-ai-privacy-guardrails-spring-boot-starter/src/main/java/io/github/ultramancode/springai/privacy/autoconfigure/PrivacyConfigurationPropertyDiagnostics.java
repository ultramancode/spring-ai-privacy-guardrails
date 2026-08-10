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
            "capture-group",
            "validator-id"
    );
    static final List<String> TOOLS_PROPERTIES = List.of("disclosures");
    static final Set<String> TOOLS_MAP_PROPERTIES = Set.of("disclosures");

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
        if (!rootMatch.exact()) {
            // Only this typo can silently leave privacy auto-configuration inactive;
            // other near-root names may belong to provider or host extensions.
            if (propertyIndex == name.getNumberOfElements()
                    && rootMatch.expected().equals("enabled")) {
                return typoSuggestionDiagnostic(ROOT.toString(), rootMatch);
            }
            return Optional.empty();
        }
        if (propertyIndex == name.getNumberOfElements()) {
            return rootMatch.expected().equals("enabled")
                    ? verifyCanonicalProperty(
                            context,
                            ROOT.append("enabled"),
                            ROOT.toString(),
                            rootMatch
                    )
                    : Optional.empty();
        }
        String propertyRoot = ROOT + "." + rootMatch.expected();
        return switch (rootMatch.expected()) {
            case "enabled" -> unrecognizedPropertyBelowPrefixDiagnostic(propertyRoot);
            case "output" -> diagnoseFixedPropertyPath(
                    name,
                    propertyIndex,
                    propertyRoot,
                    OUTPUT_PROPERTIES,
                    context
            );
            case "response-inspection" -> diagnoseFixedPropertyPath(
                    name,
                    propertyIndex,
                    propertyRoot,
                    RESPONSE_INSPECTION_PROPERTIES,
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
            case "regex" -> diagnoseRegexPropertyPath(
                    name,
                    propertyIndex,
                    propertyRoot,
                    context
            );
            case "tools" -> diagnoseFixedPropertyPath(
                    name,
                    propertyIndex,
                    propertyRoot,
                    TOOLS_PROPERTIES,
                    TOOLS_MAP_PROPERTIES,
                    Set.of(),
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

    /** Diagnoses fixed fields and indexed rule objects below the regex property root. */
    private static Optional<Diagnostic> diagnoseRegexPropertyPath(
            ConfigurationPropertyName name,
            int propertyIndex,
            String propertyRoot,
            DiagnosticContext context
    ) {
        Optional<SegmentMatch> propertyMatchCandidate = closestSegmentMatch(
                name,
                propertyIndex,
                REGEX_PROPERTIES,
                context.systemEnvironmentMapping()
        );
        if (propertyMatchCandidate.isEmpty()) {
            return unrecognizedPropertyBelowPrefixDiagnostic(propertyRoot);
        }
        SegmentMatch propertyMatch = propertyMatchCandidate.get();
        if (!propertyMatch.exact()) {
            return typoSuggestionDiagnostic(propertyRoot, propertyMatch);
        }
        int ruleIndex = propertyIndex + propertyMatch.consumedElements();
        if (!REGEX_INDEXED_OBJECT_PROPERTIES.contains(propertyMatch.expected())) {
            return name.getNumberOfElements() <= ruleIndex
                    ? verifyCanonicalProperty(
                            context,
                            ConfigurationPropertyName.of(propertyRoot)
                                    .append(propertyMatch.expected()),
                            propertyRoot,
                            propertyMatch
                    )
                    : unrecognizedPropertyBelowPrefixDiagnostic(
                            propertyRoot + "." + propertyMatch.expected()
                    );
        }
        if (name.getNumberOfElements() <= ruleIndex) {
            return Optional.empty();
        }
        if (!name.isNumericIndex(ruleIndex)) {
            return unrecognizedPropertyBelowPrefixDiagnostic(propertyRoot + ".rules");
        }
        int rulePropertyIndex = ruleIndex + 1;
        if (name.getNumberOfElements() <= rulePropertyIndex) {
            return Optional.empty();
        }
        String rulesRoot = propertyRoot + ".rules[" + dashedElement(name, ruleIndex) + "]";
        return diagnoseFixedPropertyPath(
                name,
                rulePropertyIndex,
                rulesRoot,
                REGEX_RULE_PROPERTIES,
                context
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
                String candidateComparison = variant.consumedElements() == 1
                        ? candidateUniform
                        : candidate;
                if (variant.comparison().equals(candidateComparison)) {
                    return Optional.of(new SegmentMatch(
                            variant.display(),
                            candidate,
                            variant.consumedElements(),
                            true
                    ));
                }
                int distance = levenshteinDistance(
                        variant.comparison(),
                        candidateComparison
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
        String thresholdCandidate = closest.consumedElements() == 1
                ? toUniformForm(closest.expected())
                : closest.expected();
        if (closestDistance > typoThreshold(thresholdCandidate)) {
            return Optional.empty();
        }
        return Optional.of(closest);
    }

    /**
     * Builds safe display and binding-compatible comparison forms for one element and
     * for the legacy sequence produced from operating-system environment variable names.
     */
    private static List<SegmentVariant> candidateSegmentVariants(
            ConfigurationPropertyName name,
            int propertyIndex,
            String candidate,
            boolean systemEnvironmentMapping
    ) {
        List<SegmentVariant> variants = new ArrayList<>(2);
        int candidateElements = candidate.split("-").length;
        String singleDisplay = dashedElement(name, propertyIndex);
        variants.add(new SegmentVariant(
                singleDisplay,
                uniformElement(name, propertyIndex),
                1
        ));

        if (!systemEnvironmentMapping
                || candidateElements == 1
                || propertyIndex + candidateElements > name.getNumberOfElements()) {
            return variants;
        }
        StringBuilder joinedDisplay = new StringBuilder();
        for (int offset = 0; offset < candidateElements; offset++) {
            int elementIndex = propertyIndex + offset;
            if (name.isNumericIndex(elementIndex)) {
                return variants;
            }
            if (!joinedDisplay.isEmpty()) {
                joinedDisplay.append('-');
            }
            joinedDisplay.append(dashedElement(name, elementIndex));
        }
        String display = joinedDisplay.toString();
        if (!display.equals(singleDisplay)) {
            variants.add(new SegmentVariant(
                    display,
                    display,
                    candidateElements
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

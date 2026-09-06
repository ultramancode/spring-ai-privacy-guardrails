package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Coordinates configured analyzers, failure policy, and evidence resolution. */
final class PiiAnalysisCoordinator {

    private static final PiiAnalysisResult EMPTY_ANALYSIS_RESULT =
            new PiiAnalysisResult(List.of(), Set.of(), List.of());

    private final List<ConfiguredAnalyzer> analyzers;
    private final PiiAnalysisOptions options;
    private final PiiResolutionPolicy resolutionPolicy;
    private final PiiEvidenceResolver evidenceResolver;
    private final PiiAnalyzerFailureObserver failureObserver;

    PiiAnalysisCoordinator(
            List<PiiAnalyzer> analyzers,
            PiiAnalysisOptions options,
            EntityTypeRegistry entityTypeRegistry,
            PiiResolutionPolicy resolutionPolicy,
            PiiAnalyzerFailureObserver failureObserver
    ) {
        this.analyzers = configuredAnalyzers(analyzers);
        PiiAnalysisOptions configuredOptions = Objects.requireNonNull(options, "options must not be null");
        this.resolutionPolicy = Objects.requireNonNull(
                resolutionPolicy,
                "resolutionPolicy must not be null"
        );
        EntityTypeRegistry configuredEntityTypeRegistry = Objects.requireNonNull(
                entityTypeRegistry,
                "entityTypeRegistry must not be null"
        );
        EntityTypeRegistry globalEntityTypeRegistry = configuredEntityTypeRegistry
                .withAdditionalTrustedTypes(Set.of(this.resolutionPolicy.typeConflictFallback()));
        Set<String> filterTrustedTypes = new LinkedHashSet<>();
        Map<String, EntityTypeRegistry> providerEntityTypeRegistries = new LinkedHashMap<>();
        for (ConfiguredAnalyzer configuredAnalyzer : this.analyzers) {
            Set<String> analyzerTypes = Objects.requireNonNull(
                    configuredAnalyzer.analyzer().trustedEntityTypes(),
                    "trustedEntityTypes must not be null"
            );
            filterTrustedTypes.addAll(analyzerTypes);
            providerEntityTypeRegistries.put(
                    configuredAnalyzer.provider(),
                    globalEntityTypeRegistry.withAdditionalTrustedTypes(analyzerTypes)
            );
        }
        EntityTypeRegistry filterEntityTypeRegistry = globalEntityTypeRegistry
                .withAdditionalTrustedTypes(filterTrustedTypes);
        List<String> canonicalIncludedEntityTypes = new ArrayList<>();
        Set<String> uniqueCanonicalEntityTypes = new LinkedHashSet<>();
        for (String entityType : configuredOptions.includedEntityTypes()) {
            String canonicalEntityType = filterEntityTypeRegistry.requireTrustedType(entityType);
            if (!uniqueCanonicalEntityTypes.add(canonicalEntityType)) {
                throw new IllegalArgumentException("includedEntityTypes contain canonical duplicates");
            }
            canonicalIncludedEntityTypes.add(canonicalEntityType);
        }
        this.options = new PiiAnalysisOptions(
                configuredOptions.language(),
                canonicalIncludedEntityTypes,
                configuredOptions.minimumScore()
        );
        this.evidenceResolver = new PiiEvidenceResolver(
                globalEntityTypeRegistry,
                providerEntityTypeRegistries,
                filterEntityTypeRegistry,
                this.resolutionPolicy
        );
        this.failureObserver = Objects.requireNonNull(failureObserver, "failureObserver must not be null");
        validateProviderConfiguration();
    }

    List<ResolvedPiiSpan> analyze(String text) {
        return analyzeDetailed(text).spans();
    }

    PiiAnalysisResult analyzeDetailed(String text) {
        requireTextInputWithinLimit(text);
        if (text == null || text.isBlank()) {
            return EMPTY_ANALYSIS_RESULT;
        }
        requireAnalyzerConfigured();

        List<PiiEvidence> evidence = new ArrayList<>();
        Set<String> successfulProviders = new LinkedHashSet<>();
        List<PiiAnalyzerFailure> failures = new ArrayList<>();
        if (this.resolutionPolicy.mode() == PiiResolutionMode.PRIMARY_WITH_FALLBACK) {
            Set<String> primaryPhaseProviders = new LinkedHashSet<>();
            primaryPhaseProviders.add(this.resolutionPolicy.primaryProvider());
            primaryPhaseProviders.addAll(this.resolutionPolicy.supplementalProviders());
            analyzeProviders(
                    text,
                    this.analyzers.stream()
                            .filter(item -> primaryPhaseProviders.contains(item.provider()))
                            .toList(),
                    evidence,
                    successfulProviders,
                    failures
            );
            if (!successfulProviders.contains(this.resolutionPolicy.primaryProvider())) {
                analyzeProviders(
                        text,
                        this.analyzers.stream()
                                .filter(item -> !primaryPhaseProviders.contains(item.provider()))
                                .toList(),
                        evidence,
                        successfulProviders,
                        failures
                );
            }
        } else {
            analyzeProviders(text, this.analyzers, evidence, successfulProviders, failures);
        }

        if (successfulProviders.isEmpty() && !failures.isEmpty()) {
            PiiAnalyzerFailure failure = failures.get(0);
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.ALL_ANALYZERS_FAILED,
                    PrivacyPhase.ANALYSIS,
                    "All PII analyzers failed. First failure was "
                            + failure.provider() + " (" + failure.code() + ")"
            );
        }

        List<ResolvedPiiSpan> resolved = this.evidenceResolver.resolve(
                text,
                evidence,
                Set.copyOf(successfulProviders),
                this.options
        );
        return new PiiAnalysisResult(resolved, Set.copyOf(successfulProviders), failures);
    }

    List<List<ResolvedPiiSpan>> analyzeSegments(List<String> texts) {
        return analyzeSegmentsDetailed(texts).stream()
                .map(PiiAnalysisResult::spans)
                .toList();
    }

    private List<PiiAnalysisResult> analyzeSegmentsDetailed(List<String> texts) {
        Objects.requireNonNull(texts, "texts must not be null");
        if (texts.size() > PiiAnalyzer.MAX_ANALYSIS_SEGMENTS) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                    PrivacyPhase.ANALYSIS,
                    "PII analysis segment count exceeded the bounded processing limit"
            );
        }
        List<String> sourceTexts = validatedSegmentedSourceTexts(texts);

        List<PiiAnalysisResult> results = new ArrayList<>(sourceTexts.size());
        List<String> nonBlankTexts = new ArrayList<>(sourceTexts.size());
        List<Integer> nonBlankSourceIndexes = new ArrayList<>(sourceTexts.size());
        for (int index = 0; index < sourceTexts.size(); index++) {
            String text = sourceTexts.get(index);
            results.add(EMPTY_ANALYSIS_RESULT);
            if (text != null && !text.isBlank()) {
                nonBlankTexts.add(text);
                nonBlankSourceIndexes.add(index);
            }
        }
        if (nonBlankTexts.isEmpty()) {
            return List.copyOf(results);
        }

        requireAnalyzerConfigured();
        List<PiiAnalysisResult> analyzedResults = analyzeNonBlankSegments(
                List.copyOf(nonBlankTexts)
        );
        for (int index = 0; index < analyzedResults.size(); index++) {
            results.set(nonBlankSourceIndexes.get(index), analyzedResults.get(index));
        }
        return List.copyOf(results);
    }

    private static List<String> validatedSegmentedSourceTexts(List<String> texts) {
        List<String> sourceTexts = new ArrayList<>(texts.size());
        long inputCharacters = 0L;
        for (String text : texts) {
            rejectInterruptedAnalysis();
            sourceTexts.add(text);
            if (text == null) {
                continue;
            }
            inputCharacters += text.length();
            if (inputCharacters > PrivacyService.MAX_TEXT_INPUT_CHARACTERS) {
                throw new PrivacyGuardrailException(
                        PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                        PrivacyPhase.ANALYSIS,
                        "PII analysis input exceeded the bounded processing limit"
                );
            }
        }
        return sourceTexts;
    }

    private List<PiiAnalysisResult> analyzeNonBlankSegments(List<String> texts) {
        SegmentedEvidenceAccumulator evidenceAccumulator =
                new SegmentedEvidenceAccumulator(texts.size());
        Set<String> successfulProviders = new LinkedHashSet<>();
        List<PiiAnalyzerFailure> failures = new ArrayList<>();
        if (this.resolutionPolicy.mode() == PiiResolutionMode.PRIMARY_WITH_FALLBACK) {
            Set<String> primaryPhaseProviders = new LinkedHashSet<>();
            primaryPhaseProviders.add(this.resolutionPolicy.primaryProvider());
            primaryPhaseProviders.addAll(this.resolutionPolicy.supplementalProviders());
            analyzeSegmentProviders(
                    texts,
                    this.analyzers.stream()
                            .filter(item -> primaryPhaseProviders.contains(item.provider()))
                            .toList(),
                    evidenceAccumulator,
                    successfulProviders,
                    failures
            );
            if (!successfulProviders.contains(this.resolutionPolicy.primaryProvider())) {
                analyzeSegmentProviders(
                        texts,
                        this.analyzers.stream()
                                .filter(item -> !primaryPhaseProviders.contains(item.provider()))
                                .toList(),
                        evidenceAccumulator,
                        successfulProviders,
                        failures
                );
            }
        } else {
            analyzeSegmentProviders(
                    texts,
                    this.analyzers,
                    evidenceAccumulator,
                    successfulProviders,
                    failures
            );
        }

        if (successfulProviders.isEmpty() && !failures.isEmpty()) {
            PiiAnalyzerFailure failure = failures.get(0);
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.ALL_ANALYZERS_FAILED,
                    PrivacyPhase.ANALYSIS,
                    "All PII analyzers failed. First failure was "
                            + failure.provider() + " (" + failure.code() + ")"
            );
        }

        Set<String> immutableSuccessfulProviders = Set.copyOf(successfulProviders);
        List<PiiAnalysisResult> results = new ArrayList<>(texts.size());
        for (int index = 0; index < texts.size(); index++) {
            List<ResolvedPiiSpan> resolved = this.evidenceResolver.resolve(
                    texts.get(index),
                    evidenceAccumulator.forSegment(index),
                    immutableSuccessfulProviders,
                    this.options
            );
            results.add(new PiiAnalysisResult(
                    resolved,
                    immutableSuccessfulProviders,
                    failures
            ));
        }
        return List.copyOf(results);
    }

    List<ResolvedPiiSpan> resolveSuppliedSpans(String text, List<PiiSpan> spans) {
        requireTextInputWithinLimit(text);
        requireSuppliedSpanCount(spans);
        return this.evidenceResolver.resolveSuppliedSpans(text, spans, this.options);
    }

    private static List<ConfiguredAnalyzer> configuredAnalyzers(List<PiiAnalyzer> analyzers) {
        List<PiiAnalyzer> suppliedAnalyzers = Objects.requireNonNull(analyzers, "analyzers must not be null");
        Set<String> providers = new LinkedHashSet<>();
        List<ConfiguredAnalyzer> validatedAnalyzers = new ArrayList<>(suppliedAnalyzers.size());
        for (PiiAnalyzer analyzer : suppliedAnalyzers) {
            PiiAnalyzer nonNullAnalyzer = Objects.requireNonNull(analyzer, "analyzers must not contain null values");
            String provider = PiiProviderId.canonicalize(nonNullAnalyzer.providerId());
            if (!providers.add(provider)) {
                throw new IllegalArgumentException("Duplicate PII analyzer provider " + provider);
            }
            validatedAnalyzers.add(new ConfiguredAnalyzer(nonNullAnalyzer, provider));
        }
        return List.copyOf(validatedAnalyzers);
    }

    private void validateProviderConfiguration() {
        Set<String> configuredProviders = this.analyzers.stream()
                .map(ConfiguredAnalyzer::provider)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String primaryProvider = this.resolutionPolicy.primaryProvider();
        if (primaryProvider != null && !configuredProviders.contains(primaryProvider)) {
            throw new IllegalArgumentException(
                    "Primary PII provider " + primaryProvider + " is not configured"
            );
        }
        for (String provider : this.resolutionPolicy.supplementalProviders()) {
            if (!configuredProviders.contains(provider)) {
                throw new IllegalArgumentException(
                        "Supplemental PII provider " + provider + " is not configured"
                );
            }
        }
        for (String provider : this.resolutionPolicy.providerMinimumScores().keySet()) {
            if (!configuredProviders.contains(provider)) {
                throw new IllegalArgumentException(
                        "PII provider score threshold " + provider + " has no configured analyzer"
                );
            }
        }
        if (this.resolutionPolicy.mode() == PiiResolutionMode.PRIMARY) {
            Set<String> selectedProviders = new LinkedHashSet<>(this.resolutionPolicy.supplementalProviders());
            selectedProviders.add(primaryProvider);
            Set<String> ignoredProviders = new LinkedHashSet<>(configuredProviders);
            ignoredProviders.removeAll(selectedProviders);
            if (!ignoredProviders.isEmpty()) {
                throw new IllegalArgumentException(
                        "PRIMARY policy does not select configured PII providers " + ignoredProviders
                );
            }
        }
        if (this.resolutionPolicy.mode() == PiiResolutionMode.PRIMARY_WITH_FALLBACK
                && configuredProviders.stream().allMatch(primaryProvider::equals)) {
            throw new IllegalArgumentException(
                    "PRIMARY_WITH_FALLBACK requires at least one configured non-primary provider"
            );
        }
    }

    private void analyzeProviders(
            String text,
            List<ConfiguredAnalyzer> configuredAnalyzers,
            List<PiiEvidence> evidence,
            Set<String> successfulProviders,
            List<PiiAnalyzerFailure> failures
    ) {
        for (ConfiguredAnalyzer configuredAnalyzer : configuredAnalyzers) {
            rejectInterruptedAnalysis();
            PiiAnalyzer analyzer = configuredAnalyzer.analyzer();
            String provider = configuredAnalyzer.provider();
            List<PiiSpan> reportedSpans;
            try {
                reportedSpans = analyzer.analyze(text, this.options);
            } catch (Throwable failure) {
                PrivacyFailureSanitizer.rethrowIfFatal(failure);
                rejectInterruptedAnalysis();
                handleAnalyzerFailure(
                        PiiAnalyzerFailure.executionFailure(provider, failure),
                        failures,
                        failure
                );
                continue;
            }
            rejectInterruptedAnalysis();

            List<PiiSpan> validatedSpans;
            try {
                validatedSpans = validateAnalyzerResult(
                        text,
                        reportedSpans,
                        PiiAnalyzer.MAX_RESULT_SPANS - evidence.size()
                );
            } catch (Throwable failure) {
                PrivacyFailureSanitizer.rethrowIfFatal(failure);
                rejectInterruptedAnalysis();
                handleAnalyzerFailure(
                        PiiAnalyzerFailure.contractViolation(provider),
                        failures,
                        failure
                );
                continue;
            }
            validatedSpans.stream()
                    .map(span -> PiiEvidence.from(span, provider))
                    .forEach(evidence::add);
            successfulProviders.add(provider);
        }
    }

    private void analyzeSegmentProviders(
            List<String> texts,
            List<ConfiguredAnalyzer> configuredAnalyzers,
            SegmentedEvidenceAccumulator evidenceAccumulator,
            Set<String> successfulProviders,
            List<PiiAnalyzerFailure> failures
    ) {
        for (ConfiguredAnalyzer configuredAnalyzer : configuredAnalyzers) {
            rejectInterruptedAnalysis();
            PiiAnalyzer analyzer = configuredAnalyzer.analyzer();
            String provider = configuredAnalyzer.provider();
            List<List<PiiSpan>> reportedSpans;
            try {
                reportedSpans = analyzer.analyzeSegments(texts, this.options);
            } catch (Throwable failure) {
                PrivacyFailureSanitizer.rethrowIfFatal(failure);
                rejectInterruptedAnalysis();
                handleAnalyzerFailure(
                        PiiAnalyzerFailure.executionFailure(provider, failure),
                        failures,
                        failure
                );
                continue;
            }
            rejectInterruptedAnalysis();

            List<List<PiiSpan>> validatedSpans;
            try {
                validatedSpans = validateSegmentedAnalyzerResult(
                        texts,
                        reportedSpans,
                        evidenceAccumulator.remainingSpanCapacity()
                );
            } catch (Throwable failure) {
                PrivacyFailureSanitizer.rethrowIfFatal(failure);
                rejectInterruptedAnalysis();
                handleAnalyzerFailure(
                        PiiAnalyzerFailure.contractViolation(provider),
                        failures,
                        failure
                );
                continue;
            }
            evidenceAccumulator.add(provider, validatedSpans);
            successfulProviders.add(provider);
        }
    }

    private void handleAnalyzerFailure(
            PiiAnalyzerFailure failure,
            List<PiiAnalyzerFailure> failures,
            Throwable boundaryFailure
    ) {
        failures.add(failure);
        notifyAnalyzerFailure(failure);
        rejectInterruptedAnalysis();
        if (mustFail(failure.provider())) {
            throw PrivacyFailureSanitizer.sanitize(
                    boundaryFailure,
                    failure.code(),
                    PrivacyPhase.ANALYSIS,
                    "PII analyzer " + failure.provider() + " failed (" + failure.code() + ")"
            );
        }
    }

    private void notifyAnalyzerFailure(PiiAnalyzerFailure failure) {
        try {
            this.failureObserver.onAnalyzerFailure(failure);
        } catch (Throwable observerFailure) {
            PrivacyFailureSanitizer.rethrowIfFatal(observerFailure);
        }
    }

    private static void rejectInterruptedAnalysis() {
        if (Thread.currentThread().isInterrupted()) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.ANALYSIS_INTERRUPTED,
                    PrivacyPhase.ANALYSIS,
                    "PII analysis interrupted"
            );
        }
    }

    private static List<PiiSpan> validateAnalyzerResult(
            String text,
            List<PiiSpan> spans,
            int remainingCapacity
    ) {
        if (spans == null) {
            throw new IllegalStateException("PII analyzer returned a null result");
        }
        if (spans.size() > remainingCapacity) {
            throw new IllegalStateException("PII analyzer result exceeded the safe span limit");
        }
        for (PiiSpan span : spans) {
            if (span == null) {
                throw new IllegalStateException("PII analyzer returned a null span");
            }
            if (span.end() > text.length()) {
                throw new IllegalStateException("PII analyzer returned a span outside the source text");
            }
        }
        return List.copyOf(spans);
    }

    private static List<List<PiiSpan>> validateSegmentedAnalyzerResult(
            List<String> texts,
            List<List<PiiSpan>> spansByText,
            int remainingCapacity
    ) {
        if (spansByText == null) {
            throw new IllegalStateException("PII analyzer returned a null segmented result");
        }
        if (spansByText.size() != texts.size()) {
            throw new IllegalStateException("PII analyzer returned the wrong segmented result count");
        }

        List<List<PiiSpan>> validatedResults = new ArrayList<>(texts.size());
        int validatedSpanCount = 0;
        for (int index = 0; index < texts.size(); index++) {
            rejectInterruptedAnalysis();
            List<PiiSpan> validatedSpans = validateAnalyzerResult(
                    texts.get(index),
                    spansByText.get(index),
                    remainingCapacity - validatedSpanCount
            );
            validatedSpanCount += validatedSpans.size();
            validatedResults.add(validatedSpans);
        }
        return List.copyOf(validatedResults);
    }

    private void requireAnalyzerConfigured() {
        if (this.analyzers.isEmpty()) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.NO_ANALYZER_CONFIGURED,
                    PrivacyPhase.ANALYSIS,
                    "No PII analyzer is configured for automatic analysis"
            );
        }
    }

    private static void requireSuppliedSpanCount(List<PiiSpan> spans) {
        Objects.requireNonNull(spans, "spans must not be null");
        if (spans.size() > PiiAnalyzer.MAX_RESULT_SPANS) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                    PrivacyPhase.ANALYSIS,
                    "Caller-supplied PII spans exceeded the bounded processing limit"
            );
        }
    }

    static void requireTextInputWithinLimit(String text) {
        if (text != null && text.length() > PrivacyService.MAX_TEXT_INPUT_CHARACTERS) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                    PrivacyPhase.ANALYSIS,
                    "Privacy text input exceeded the bounded processing limit"
            );
        }
    }

    private boolean mustFail(String provider) {
        if (this.resolutionPolicy.failurePolicy() == PiiAnalyzerFailurePolicy.REQUIRE_ALL) {
            return true;
        }
        return this.resolutionPolicy.failurePolicy() == PiiAnalyzerFailurePolicy.REQUIRE_PRIMARY
                && Objects.equals(this.resolutionPolicy.primaryProvider(), provider);
    }

    private record ConfiguredAnalyzer(PiiAnalyzer analyzer, String provider) {
    }

    private static final class SegmentedEvidenceAccumulator {

        private final List<List<PiiEvidence>> evidenceBySegment;
        private int reportedSpanCount;

        private SegmentedEvidenceAccumulator(int segmentCount) {
            this.evidenceBySegment = new ArrayList<>(segmentCount);
            for (int index = 0; index < segmentCount; index++) {
                this.evidenceBySegment.add(new ArrayList<>());
            }
        }

        private int remainingSpanCapacity() {
            return PiiAnalyzer.MAX_RESULT_SPANS - this.reportedSpanCount;
        }

        private void add(String provider, List<List<PiiSpan>> spansBySegment) {
            for (int index = 0; index < spansBySegment.size(); index++) {
                List<PiiEvidence> evidence = this.evidenceBySegment.get(index);
                for (PiiSpan span : spansBySegment.get(index)) {
                    evidence.add(PiiEvidence.from(span, provider));
                    this.reportedSpanCount++;
                }
            }
        }

        private List<PiiEvidence> forSegment(int segmentIndex) {
            return this.evidenceBySegment.get(segmentIndex);
        }
    }
}

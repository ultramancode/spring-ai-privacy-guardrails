package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyServiceResolutionTest {

    @Test
    void observerReceivesSafeMetadataForToleratedProviderFailure() {
        class RetriedTimeout extends PrivacyGuardrailException implements PiiAnalyzerFailureMetadata {
            RetriedTimeout() {
                super(
                        PrivacyFailureCode.ANALYZER_TIMEOUT,
                        PrivacyPhase.ANALYSIS,
                        "safe timeout"
                );
            }

            @Override
            public int attemptCount() {
                return 3;
            }
        }
        PiiAnalyzer timedOut = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                throw new RetriedTimeout();
            }

            @Override
            public String providerId() {
                return "PRESIDIO";
            }
        };
        List<PiiAnalyzerFailure> observed = new ArrayList<>();
        PrivacyService service = new PrivacyService(
                List.of(timedOut, namedAnalyzer("REGEX", List.of())),
                PiiAnalysisOptions.defaults(),
                EntityTypeRegistry.defaults(),
                PiiResolutionPolicy.builder()
                        .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                        .build(),
                observed::add
        );

        PiiAnalysisResult result = service.analyzeDetailed("Alice");

        PiiAnalyzerFailure expected = new PiiAnalyzerFailure(
                "PRESIDIO",
                PrivacyFailureCode.ANALYZER_TIMEOUT,
                PrivacyPhase.ANALYSIS,
                3
        );
        assertThat(result.failures()).containsExactly(expected);
        assertThat(observed).containsExactly(expected);
        assertThat(observed.toString()).doesNotContain("Alice");
    }

    @Test
    void analysisBoundaryPreservesSafeTypedAnalyzerFailureCode() {
        PrivacyGuardrailException typedFailure = new PrivacyGuardrailException(
                PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION,
                PrivacyPhase.ANALYSIS,
                "safe adapter contract failure"
        );

        PiiAnalyzerFailure failure = PiiAnalyzerFailure.executionFailure("OPENNLP", typedFailure);

        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION);
        assertThat(failure.attemptCount()).isEqualTo(1);
    }

    @Test
    void invalidOptionalFailureMetadataFallsBackWithoutRepairingIndividualFields() {
        class InvalidMetadata extends RuntimeException implements PiiAnalyzerFailureMetadata {

            @Override
            public PrivacyFailureCode code() {
                return PrivacyFailureCode.ANALYZER_TIMEOUT;
            }

            @Override
            public int attemptCount() {
                return 0;
            }
        }

        PiiAnalyzerFailure failure = PiiAnalyzerFailure.executionFailure(
                "CUSTOM",
                new InvalidMetadata()
        );

        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_EXECUTION_FAILED);
        assertThat(failure.attemptCount()).isEqualTo(1);
    }

    @Test
    void analyzerReceivesCanonicalEntityFilter() {
        List<PiiAnalysisOptions> received = new ArrayList<>();
        PiiAnalyzer analyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                received.add(options);
                return List.of(new PiiSpan("PERSON", 0, 5, 0.9));
            }
        };
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.builder().includedEntityTypes(List.of("PER")).build(),
                new EntityTypeRegistry(Map.of("PER", "PERSON")),
                PiiResolutionPolicy.defaults()
        );

        assertThat(service.analyze("Alice")).hasSize(1);
        assertThat(received).singleElement()
                .extracting(PiiAnalysisOptions::includedEntityTypes)
                .isEqualTo(List.of("PERSON"));
    }

    @Test
    void analyzeDetailedReportsPartialProviderFailureWithoutLosingEvidence() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        PrivacyService service = service(policy, namedAnalyzer("REGEX", List.of(
                new PiiSpan("EMAIL_ADDRESS", 0, 17, 0.95)
        )), failingAnalyzer("PRESIDIO"));

        PiiAnalysisResult result = service.analyzeDetailed("alice@example.com");

        assertThat(result.successfulProviders()).containsExactly("REGEX");
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.provider()).isEqualTo("PRESIDIO");
            assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_EXECUTION_FAILED);
            assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
            assertThat(failure.attemptCount()).isEqualTo(1);
            assertThat(failure.toString()).doesNotContain("alice@example.com");
        });
        assertThat(result.spans()).singleElement().satisfies(span -> {
            assertThat(span.entityType()).isEqualTo("EMAIL_ADDRESS");
            assertThat(span.evidence()).singleElement()
                    .extracting(PiiEvidence::provider)
                    .isEqualTo("REGEX");
        });
    }

    @Test
    void analyzeDetailedFailsWhenRequiredPrimaryProviderFails() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .primaryProvider("PRESIDIO")
                .failurePolicy(PiiAnalyzerFailurePolicy.REQUIRE_PRIMARY)
                .build();
        PrivacyService service = service(policy,
                namedAnalyzer("REGEX", List.of()),
                failingAnalyzer("PRESIDIO"));

        assertThatThrownBy(() -> service.analyzeDetailed("Alice"))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.getMessage())
                            .contains("PRESIDIO", "ANALYZER_EXECUTION_FAILED")
                            .doesNotContain("Alice");
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getStackTrace())
                            .anyMatch(element -> element.getClassName()
                                    .startsWith(PrivacyServiceResolutionTest.class.getName()));
                });
    }

    @Test
    void analyzeDetailedUsesFallbackWhenPrimaryFailureIsAllowed() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY_WITH_FALLBACK)
                .primaryProvider("PRESIDIO")
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        PrivacyService service = service(
                policy,
                failingAnalyzer("PRESIDIO"),
                namedAnalyzer("REGEX", List.of(
                        new PiiSpan("PERSON", 0, 5, 0.95)
                ))
        );

        PiiAnalysisResult result = service.analyzeDetailed("Alice");

        assertThat(result.successfulProviders()).containsExactly("REGEX");
        assertThat(result.failures()).singleElement()
                .extracting(PiiAnalyzerFailure::provider)
                .isEqualTo("PRESIDIO");
        assertThat(result.spans()).singleElement().satisfies(span -> {
            assertThat(span.start()).isZero();
            assertThat(span.end()).isEqualTo(5);
        });
    }

    @Test
    void analyzeSegmentsUsesFallbackWithoutSharingOffsetsBetweenTexts() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY_WITH_FALLBACK)
                .primaryProvider("PRESIDIO")
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        AtomicInteger primaryBatchCalls = new AtomicInteger();
        AtomicInteger fallbackBatchCalls = new AtomicInteger();
        PiiAnalyzer primary = segmentedAnalyzer("PRESIDIO", (texts, options) -> {
            primaryBatchCalls.incrementAndGet();
            throw new IllegalStateException("unavailable segmented provider");
        });
        PiiAnalyzer fallback = segmentedAnalyzer("REGEX", (texts, options) -> {
            fallbackBatchCalls.incrementAndGet();
            return List.of(
                    List.of(),
                    List.of(new PiiSpan("PERSON", 0, 5, 0.95))
            );
        });
        PrivacyService service = service(policy, primary, fallback);

        List<List<ResolvedPiiSpan>> results = service.analyzeSegments(
                List.of("safe", "Alice")
        );

        assertThat(primaryBatchCalls).hasValue(1);
        assertThat(fallbackBatchCalls).hasValue(1);
        assertThat(results.get(0)).isEmpty();
        assertThat(results.get(1)).singleElement().satisfies(span -> {
            assertThat(span.start()).isZero();
            assertThat(span.end()).isEqualTo(5);
        });
    }

    @Test
    void analyzeDetailedDoesNotCallFallbackOnlyProviderWhenPrimarySucceeds() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY_WITH_FALLBACK)
                .primaryProvider("PRESIDIO")
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        AtomicInteger fallbackCalls = new AtomicInteger();
        PiiAnalyzer fallback = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                fallbackCalls.incrementAndGet();
                return List.of(new PiiSpan("PERSON", 0, 5, 0.95));
            }

            @Override
            public String providerId() {
                return "OPENNLP";
            }
        };
        PrivacyService service = service(
                policy,
                namedAnalyzer("PRESIDIO", List.of()),
                fallback
        );

        PiiAnalysisResult result = service.analyzeDetailed("Alice");

        assertThat(result.successfulProviders()).containsExactly("PRESIDIO");
        assertThat(result.spans()).isEmpty();
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    void analyzeDetailedStopsFallbackWhenThePrimaryAnalyzerIsInterrupted() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY_WITH_FALLBACK)
                .primaryProvider("PRESIDIO")
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        AtomicInteger fallbackCalls = new AtomicInteger();
        PiiAnalyzer interruptedPrimary = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Alice leaked from an interrupted provider");
            }

            @Override
            public String providerId() {
                return "PRESIDIO";
            }
        };
        PiiAnalyzer fallback = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                fallbackCalls.incrementAndGet();
                return List.of();
            }

            @Override
            public String providerId() {
                return "REGEX";
            }
        };
        PrivacyService service = service(policy, interruptedPrimary, fallback);

        try {
            assertThatThrownBy(() -> service.analyzeDetailed("Alice"))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("PII analysis interrupted")
                    .hasMessageNotContaining("Alice")
                    .hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(fallbackCalls).hasValue(0);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void analyzeDetailedSanitizesFailureWhenEveryAnalyzerFails() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        PrivacyService service = service(policy, failingAnalyzer("PRESIDIO"));

        assertThatThrownBy(() -> service.analyzeDetailed("Alice"))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.getMessage())
                            .contains("PRESIDIO", "ANALYZER_EXECUTION_FAILED")
                            .doesNotContain("Alice");
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getStackTrace())
                            .anyMatch(element -> element.getClassName()
                                    .startsWith(PrivacyServiceResolutionTest.class.getName()));
                });
    }

    @Test
    void analyzeDetailedTreatsNullAnalyzerResultsAsProviderFailures() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        PrivacyService service = service(
                policy,
                namedAnalyzer("BROKEN", null),
                namedAnalyzer("REGEX", List.of(
                        new PiiSpan("PERSON", 0, 5, 0.95)
                ))
        );

        PiiAnalysisResult result = service.analyzeDetailed("Alice");

        assertThat(result.successfulProviders()).containsExactly("REGEX");
        assertThat(result.failures()).containsExactly(new PiiAnalyzerFailure(
                "BROKEN",
                PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION,
                PrivacyPhase.ANALYSIS,
                1
        ));
        assertThat(result.spans()).singleElement().satisfies(span -> {
            assertThat(span.start()).isZero();
            assertThat(span.end()).isEqualTo(5);
        });
    }

    @Test
    void analyzeDetailedRejectsNullAndOutOfBoundsSpans() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        PrivacyService nullSpanService = service(
                policy,
                namedAnalyzer("NULL_SPAN", Collections.singletonList(null))
        );
        PrivacyService outOfBoundsService = service(
                policy,
                namedAnalyzer("OUT_OF_BOUNDS", List.of(
                        new PiiSpan("PERSON", 0, 99, 0.95)
                ))
        );

        assertThatThrownBy(() -> nullSpanService.analyzeDetailed("Alice"))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("NULL_SPAN")
                .hasMessageContaining("ANALYZER_CONTRACT_VIOLATION")
                .hasMessageNotContaining("Alice");
        assertThatThrownBy(() -> outOfBoundsService.analyzeDetailed("Alice"))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("OUT_OF_BOUNDS")
                .hasMessageContaining("ANALYZER_CONTRACT_VIOLATION")
                .hasMessageNotContaining("Alice");
    }

    @Test
    void constructorFailsFastWhenPrimaryProviderIsNotConfigured() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY)
                .primaryProvider("PRESIDIO")
                .build();

        assertThatThrownBy(() -> service(policy, namedAnalyzer("REGEX", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRESIDIO")
                .hasMessageContaining("not configured");
    }

    @Test
    void constructorFailsFastForUnknownSupplementalAndThresholdProviders() {
        PiiResolutionPolicy supplementalPolicy = PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY)
                .primaryProvider("REGEX")
                .supplementalProviders(Set.of("PRESIDIO"))
                .build();
        PiiResolutionPolicy thresholdPolicy = PiiResolutionPolicy.builder()
                .providerMinimumScores(Map.of("PRESIDIO", 0.8))
                .build();

        assertThatThrownBy(() -> service(supplementalPolicy, namedAnalyzer("REGEX", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supplemental", "PRESIDIO", "not configured");
        assertThatThrownBy(() -> service(thresholdPolicy, namedAnalyzer("REGEX", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold", "PRESIDIO", "no configured analyzer");
    }

    @Test
    void constructorRejectsProvidersThatTheSelectedModeCanNeverUse() {
        PiiResolutionPolicy primaryPolicy = PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY)
                .primaryProvider("REGEX")
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        PiiResolutionPolicy fallbackWithoutCandidate = PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY_WITH_FALLBACK)
                .primaryProvider("REGEX")
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();

        assertThatThrownBy(() -> service(
                primaryPolicy,
                namedAnalyzer("REGEX", List.of()),
                namedAnalyzer("PRESIDIO", List.of())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not select", "PRESIDIO");
        assertThatThrownBy(() -> service(
                fallbackWithoutCandidate,
                namedAnalyzer("REGEX", List.of())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires at least one configured non-primary provider");
    }

    @Test
    void tokenizeCanonicalizesAliasesForCallerProvidedSpans() {
        PrivacyService service = new PrivacyService(
                List.of(namedAnalyzer("REGEX", List.of())),
                PiiAnalysisOptions.defaults(),
                new EntityTypeRegistry(Map.of("PER", "PERSON")),
                PiiResolutionPolicy.defaults()
        );

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenize(session.handle(), "Alice", List.of(
                    new PiiSpan("PER", 0, 5, 0.9)
            ))).matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
        }
    }

    private PrivacyService service(PiiResolutionPolicy policy, PiiAnalyzer... analyzers) {
        return new PrivacyService(
                List.of(analyzers),
                PiiAnalysisOptions.defaults(),
                EntityTypeRegistry.defaults(),
                policy
        );
    }

    private PiiAnalyzer namedAnalyzer(String providerId, List<PiiSpan> spans) {
        return new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                return spans;
            }

            @Override
            public String providerId() {
                return providerId;
            }
        };
    }

    private PiiAnalyzer segmentedAnalyzer(
            String providerId,
            BiFunction<List<String>, PiiAnalysisOptions, List<List<PiiSpan>>> operation
    ) {
        return new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                return List.of();
            }

            @Override
            public List<List<PiiSpan>> analyzeSegments(
                    List<String> texts,
                    PiiAnalysisOptions options
            ) {
                return operation.apply(texts, options);
            }

            @Override
            public String providerId() {
                return providerId;
            }
        };
    }

    private PiiAnalyzer failingAnalyzer(String providerId) {
        return new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                throw new IllegalStateException("unavailable for " + text);
            }

            @Override
            public String providerId() {
                return providerId;
            }
        };
    }
}

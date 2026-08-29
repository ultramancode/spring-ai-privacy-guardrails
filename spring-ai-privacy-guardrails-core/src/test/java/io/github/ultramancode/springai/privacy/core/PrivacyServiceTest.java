package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyServiceTest {

    @Test
    void analyzeSegmentsSkipsNullAndBlankTextsAndPreservesPerTextOffsets() {
        AtomicInteger analysisCalls = new AtomicInteger();
        List<String> analyzedTexts = new ArrayList<>();
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            analyzedTexts.add(text);
            return text.matches("^EMP-[0-9]{4}$")
                    ? List.of(new PiiSpan("EMPLOYEE_ID", 0, text.length(), 1.0))
                    : List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        List<List<ResolvedPiiSpan>> results = service.analyzeSegments(
                Arrays.asList(null, "  ", "safe", "EMP-1234")
        );

        assertThat(analysisCalls).hasValue(2);
        assertThat(analyzedTexts).containsExactly("safe", "EMP-1234");
        assertThat(results).hasSize(4);
        assertThat(results.get(0)).isEmpty();
        assertThat(results.get(1)).isEmpty();
        assertThat(results.get(2)).isEmpty();
        assertThat(results.get(3)).singleElement().satisfies(span -> {
            assertThat(span.start()).isZero();
            assertThat(span.end()).isEqualTo("EMP-1234".length());
        });
    }

    @Test
    void analyzeSegmentsPreservesEarlierResultsWhenAnalyzerReusesItsResultList() {
        ThreadLocal<ArrayList<PiiSpan>> resultBuffers =
                ThreadLocal.withInitial(ArrayList::new);
        PiiAnalyzer analyzer = (text, options) -> {
            List<PiiSpan> reusableResults = resultBuffers.get();
            reusableResults.clear();
            if (text.equals("Alice")) {
                reusableResults.add(new PiiSpan("PERSON", 0, text.length(), 1.0));
            }
            return reusableResults;
        };
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.defaults()
        );

        try {
            List<List<ResolvedPiiSpan>> results = service.analyzeSegments(
                    List.of("Alice", "safe")
            );

            assertThat(results.get(0)).singleElement().satisfies(span -> {
                assertThat(span.entityType()).isEqualTo("PERSON");
                assertThat(span.start()).isZero();
                assertThat(span.end()).isEqualTo("Alice".length());
            });
            assertThat(results.get(1)).isEmpty();
        } finally {
            resultBuffers.remove();
        }
    }

    @Test
    void analyzeSegmentsInvokesTheSegmentOverrideOnce() {
        AtomicInteger singleCalls = new AtomicInteger();
        AtomicInteger segmentCalls = new AtomicInteger();
        PiiAnalyzer analyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                singleCalls.incrementAndGet();
                return List.of();
            }

            @Override
            public List<List<PiiSpan>> analyzeSegments(
                    List<String> texts,
                    PiiAnalysisOptions options
            ) {
                segmentCalls.incrementAndGet();
                assertThat(texts).containsExactly("safe", "EMP-1234");
                return List.of(
                        List.of(),
                        List.of(new PiiSpan("EMPLOYEE_ID", 0, 8, 1.0))
                );
            }
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        List<List<ResolvedPiiSpan>> results = service.analyzeSegments(
                List.of("safe", "EMP-1234")
        );

        assertThat(singleCalls).hasValue(0);
        assertThat(segmentCalls).hasValue(1);
        assertThat(results.get(0)).isEmpty();
        assertThat(results.get(1)).hasSize(1);
    }

    @Test
    void analyzeSegmentsRejectsAnalyzerInputMutationWithoutCorruptingPositions() {
        PiiAnalyzer analyzer = segmentedAnalyzer((texts, options) -> {
            texts.clear();
            return List.of();
        });
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        List<String> sourceTexts = new ArrayList<>(List.of("first", "second"));

        assertThatThrownBy(() -> service.analyzeSegments(sourceTexts))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo(PrivacyFailureCode.ANALYZER_EXECUTION_FAILED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                });
        assertThat(sourceTexts).containsExactly("first", "second");
    }

    @Test
    void analyzeSegmentsRejectsAnalyzerResultCountMismatch() {
        PiiAnalyzer analyzer = segmentedAnalyzer(
                (texts, options) -> List.of(List.of())
        );
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        assertThatThrownBy(() -> service.analyzeSegments(List.of("first", "second")))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo(PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                });
    }

    @Test
    void tokenizeUsesStableTokenForRepeatedOriginalValue() {
        PrivacyService service = new PrivacyService(
                List.of(personAnalyzer()),
                PiiAnalysisOptions.defaults()
        );

        try (PrivacySession session = service.openSession()) {
            String tokenized = service.tokenize(session.handle(), "Alice called Alice");

            Matcher tokenMatcher = OpaquePiiTokenFormat.patternForEntityType("PERSON").matcher(tokenized);
            assertThat(tokenMatcher.find()).isTrue();
            String token = tokenMatcher.group();
            assertThat(tokenized).isEqualTo(token + " called " + token);
            assertThat(service.detokenize(session.handle(), tokenized)).isEqualTo("Alice called Alice");
        }
    }

    @Test
    void analyzeAndTokenizeReturnsResolvedSpansFromExactlyOneAnalysis() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            return List.of(new PiiSpan("PERSON", 0, 5, 0.95));
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            PiiTokenizationResult result = service.analyzeAndTokenize(
                    session.handle(),
                    "Alice called"
            );

            assertThat(analysisCalls).hasValue(1);
            assertThat(result.analysis().spans()).singleElement().satisfies(span -> {
                assertThat(span.entityType()).isEqualTo("PERSON");
                assertThat(span.start()).isZero();
                assertThat(span.end()).isEqualTo(5);
            });
            assertThat(result.tokenizedText())
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern() + " called");
            assertThat(service.detokenize(session.handle(), result.tokenizedText()))
                    .isEqualTo("Alice called");
        }
    }

    @Test
    void tokenizeUsesTheSameSingleAnalysisPath() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            return List.of(new PiiSpan("PERSON", 0, 5, 0.95));
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenize(session.handle(), "Alice"))
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
            assertThat(analysisCalls).hasValue(1);
        }
    }

    @Test
    void analyzeAndTokenizePreservesNullAndBlankButStillValidatesTheSession() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            return List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        PrivacyContextHandle closedHandle;

        try (PrivacySession session = service.openSession()) {
            closedHandle = session.handle();
            PiiTokenizationResult nullResult = service.analyzeAndTokenize(session.handle(), null);
            PiiTokenizationResult blankResult = service.analyzeAndTokenize(session.handle(), "   ");

            assertThat(nullResult.tokenizedText()).isNull();
            assertThat(nullResult.analysis().spans()).isEmpty();
            assertThat(blankResult.tokenizedText()).isEqualTo("   ");
            assertThat(blankResult.analysis().spans()).isEmpty();
            assertThat(analysisCalls).hasValue(0);
        }

        assertThatThrownBy(() -> service.analyzeAndTokenize(closedHandle, null))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.CONTEXT_NOT_ACTIVE);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.SESSION);
                });
        assertThat(analysisCalls).hasValue(0);
    }

    @Test
    void redactReplacesDetectedSpansWithoutKeepingOriginalValues() {
        PrivacyService service = new PrivacyService(
                List.of(personAnalyzer()),
                PiiAnalysisOptions.defaults()
        );

        assertThat(service.redact("Alice called")).isEqualTo("[REDACTED_PERSON] called");
    }

    @Test
    void tokenizeValueTreeProtectsNestedJsonLikeStringValues() {
        PrivacyService service = new PrivacyService(
                List.of(personAnalyzer()),
                PiiAnalysisOptions.defaults()
        );

        try (PrivacySession session = service.openSession()) {
            Object protectedInput = service.tokenizeValueTree(session.handle(), Map.of(
                    "name", "Alice",
                    "nested", List.of(Map.of("owner", "Alice"), 7)
            ));

            assertThat(protectedInput.toString()).doesNotContain("Alice");
            assertThat(service.detokenizeValueTree(session.handle(), protectedInput)).isEqualTo(Map.of(
                    "name", "Alice",
                    "nested", List.of(Map.of("owner", "Alice"), 7)
            ));
        }
    }

    @Test
    void recursiveTransformsProtectNumericPiiAndRestoreItsJsonTypeOnlyWhenAllowed() {
        long phoneNumber = 821012345678L;
        PiiAnalyzer analyzer = (text, options) -> text.equals(Long.toString(phoneNumber))
                ? List.of(new PiiSpan("PHONE_NUMBER", 0, text.length(), 0.99))
                : List.of();
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.defaults()
        );

        try (PrivacySession session = service.openSession()) {
            Map<String, Long> raw = Map.of("phone", phoneNumber);
            Object protectedInput = service.tokenizeValueTree(session.handle(), raw);

            assertThat(protectedInput.toString())
                    .doesNotContain(Long.toString(phoneNumber))
                    .containsPattern(OpaquePiiTokenFormat.patternForEntityType("PHONE_NUMBER"));
            assertThat(service.detokenizeValueTree(session.handle(), protectedInput, Set.of()))
                    .isEqualTo(protectedInput);
            assertThat(service.detokenizeValueTree(
                    session.handle(),
                    protectedInput,
                    Set.of("PHONE_NUMBER")
            )).isEqualTo(raw);
        }
    }

    @Test
    void preAnalyzedScalarOperationsReuseSpansAndPreserveNumericTypeMappings() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            return List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        long phoneNumber = 821012345678L;

        try (PrivacySession session = service.openSession()) {
            String nameToken = (String) service.tokenizeScalar(
                    session.handle(),
                    "Alice",
                    List.of(new PiiSpan("PERSON", 0, 5, 1.0))
            );
            Object phoneToken = service.tokenizeScalar(
                    session.handle(),
                    phoneNumber,
                    List.of(new PiiSpan("PHONE_NUMBER", 0, Long.toString(phoneNumber).length(), 1.0))
            );

            assertThat(nameToken).matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
            assertThat(phoneToken.toString())
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PHONE_NUMBER"));
            assertThat(service.detokenizeValueTree(session.handle(), phoneToken)).isEqualTo(phoneNumber);
            assertThat(service.redact(
                    session.handle(),
                    "Alice",
                    List.of(new PiiSpan("PERSON", 0, 5, 1.0))
            )).isEqualTo("[REDACTED_PERSON]");
            assertThat(service.containsPii(
                    session.handle(),
                    "Alice",
                    List.of(new PiiSpan("PERSON", 0, 5, 1.0))
            )).isTrue();
            assertThat(service.containsPii(
                    session.handle(),
                    nameToken,
                    List.of(new PiiSpan("PII", 0, nameToken.length(), 1.0))
            )).isFalse();
        }

        assertThat(analysisCalls).hasValue(0);
    }

    @Test
    void numericScalarWithMultipleTypesUsesConfiguredConflictingTypeForDisclosure() {
        PrivacyService service = new PrivacyService(
                List.of(),
                PiiAnalysisOptions.defaults(),
                EntityTypeRegistry.defaults(),
                PiiResolutionPolicy.builder().typeConflictFallback("SENSITIVE").build()
        );

        try (PrivacySession session = service.openSession()) {
            Object token = service.tokenizeScalar(
                    session.handle(),
                    123456,
                    List.of(
                            new PiiSpan("PHONE_NUMBER", 0, 3, 1.0),
                            new PiiSpan("NATIONAL_ID", 3, 6, 1.0)
                    )
            );

            assertThat(token.toString()).matches(OpaquePiiTokenFormat.patternForEntityType("SENSITIVE"));
            assertThat(service.detokenizeValueTree(session.handle(), token, Set.of("PII")))
                    .isEqualTo(token);
            assertThat(service.detokenizeValueTree(session.handle(), token, Set.of("SENSITIVE")))
                    .isEqualTo(123456);
        }
    }

    @Test
    void recursiveTransformsProtectAndRestoreStringMapKeys() {
        PrivacyService service = new PrivacyService(
                List.of(personAnalyzer()),
                PiiAnalysisOptions.defaults()
        );

        try (PrivacySession session = service.openSession()) {
            Object protectedInput = service.tokenizeValueTree(session.handle(), Map.of("Alice", "safe"));

            assertThat(protectedInput.toString()).doesNotContain("Alice");
            assertThat(service.detokenizeValueTree(session.handle(), protectedInput))
                    .isEqualTo(Map.of("Alice", "safe"));
        }
    }

    @Test
    void recursiveTransformsFailClosedWhenMapKeysCollide() {
        PrivacyService service = new PrivacyService(
                List.of(personAnalyzer()),
                PiiAnalysisOptions.defaults()
        );

        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), "Alice");
            Map<String, String> input = new LinkedHashMap<>();
            input.put("Alice", "first");
            input.put(token, "second");

            assertThatThrownBy(() -> service.tokenizeValueTree(session.handle(), input))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessageContaining("duplicate map keys")
                    .hasMessageNotContaining("Alice");
            assertThatThrownBy(() -> service.detokenizeValueTree(session.handle(), input))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessageContaining("duplicate map keys")
                    .hasMessageNotContaining("Alice");
        }
    }

    @Test
    void analyzeAppliesMinimumScore() {
        PiiAnalyzer analyzer = (text, options) -> List.of(
                new PiiSpan("PERSON", 0, 5, 0.4),
                new PiiSpan("PERSON", 13, 18, 0.9)
        );
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.builder().minimumScore(0.8).build()
        );

        assertThat(service.analyze("Alice called Alice"))
                .extracting(ResolvedPiiSpan::start)
                .containsExactly(13);
    }

    @Test
    void tokenizePrefersCoveringSpanToAvoidPartialMaskingLeak() {
        PiiAnalyzer analyzer = (text, options) -> List.of(
                new PiiSpan("PERSON", 0, 4, 0.99),
                new PiiSpan("EMAIL_ADDRESS", 0, 16, 0.90)
        );
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.defaults()
        );

        try (PrivacySession session = service.openSession()) {
            String tokenized = service.tokenize(session.handle(), "john@example.com");

            assertThat(tokenized).matches(OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS"));
            assertThat(service.detokenize(session.handle(), tokenized)).isEqualTo("john@example.com");
        }
    }

    @Test
    void analyzeAppliesEntityAllowListAcrossAnalyzers() {
        PiiAnalyzer analyzer = (text, options) -> List.of(
                new PiiSpan("PERSON", 0, 5, 0.95),
                new PiiSpan("EMAIL_ADDRESS", 14, 31, 0.95)
        );
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.builder().includedEntityTypes(List.of("EMAIL_ADDRESS")).build()
        );

        assertThat(service.analyze("Alice emailed alice@example.com"))
                .extracting(ResolvedPiiSpan::entityType)
                .containsExactly("EMAIL_ADDRESS");
    }

    @Test
    void sessionsUseDifferentOpaqueTokenNamespaces() {
        PrivacyService service = new PrivacyService(
                List.of(personAnalyzer()),
                PiiAnalysisOptions.defaults()
        );

        String first;
        String second;
        try (PrivacySession session = service.openSession()) {
            first = service.tokenize(session.handle(), "Alice");
        }
        try (PrivacySession session = service.openSession()) {
            second = service.tokenize(session.handle(), "Alice");
        }

        assertThat(first).matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
        assertThat(second).matches(OpaquePiiTokenFormat.patternForEntityType("PERSON")).isNotEqualTo(first);
    }

    @Test
    void tokenizeDoesNotRetokenizeKnownOpaqueTokens() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer broadAnalyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            return List.of(new PiiSpan("PII", 0, text.length(), 0.95));
        };
        PrivacyService service = new PrivacyService(
                List.of(broadAnalyzer),
                PiiAnalysisOptions.defaults()
        );

        try (PrivacySession session = service.openSession()) {
            String first = service.tokenize(session.handle(), "Alice");
            String unchanged = service.tokenize(session.handle(), first);
            String second = service.tokenize(session.handle(), first + " Bob");

            assertThat(unchanged).isEqualTo(first);
            assertThat(second).startsWith(first).isNotEqualTo(first + " Bob");
            assertThat(service.detokenize(session.handle(), second)).isEqualTo("Alice Bob");
            assertThat(analysisCalls.get()).isEqualTo(3);
        }
    }

    @Test
    void tokenizeReanalyzesPartiallyProtectedTextForNewAutomaticEvidence() {
        AtomicInteger calls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options) -> {
            calls.incrementAndGet();
            String detectedValue = calls.get() == 1 ? "Alice" : "Bob";
            int start = text.indexOf(detectedValue);
            return start < 0
                    ? List.of()
                    : List.of(new PiiSpan("PERSON", start, start + detectedValue.length(), 0.95));
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            String partlyProtected = service.tokenize(session.handle(), "Alice Bob");
            String fullyProtected = service.tokenize(session.handle(), partlyProtected);

            assertThat(partlyProtected).contains("Bob").doesNotContain("Alice");
            assertThat(fullyProtected).doesNotContain("Alice", "Bob");
            assertThat(service.detokenize(session.handle(), fullyProtected)).isEqualTo("Alice Bob");
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void tokenizeAppliesNewExplicitSpanToPartiallyProtectedText() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            String partlyProtected = service.tokenize(
                    session.handle(),
                    "Alice Bob",
                    List.of(new PiiSpan("PERSON", 0, 5, 1.0))
            );
            int bobStart = partlyProtected.indexOf("Bob");
            String fullyProtected = service.tokenize(
                    session.handle(),
                    partlyProtected,
                    List.of(new PiiSpan("PERSON", bobStart, bobStart + 3, 1.0))
            );

            assertThat(fullyProtected).doesNotContain("Alice", "Bob");
            assertThat(service.detokenize(session.handle(), fullyProtected)).isEqualTo("Alice Bob");
        }
    }

    @Test
    void sessionAwareInspectionIgnoresKnownTokensButFindsAdjacentPii() {
        PiiAnalyzer broadAnalyzer = (text, options) -> List.of(new PiiSpan("PII", 0, text.length(), 0.95));
        PrivacyService service = new PrivacyService(List.of(broadAnalyzer), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), "Alice");

            assertThat(service.containsPii(session.handle(), token)).isFalse();
            assertThat(service.redact(session.handle(), token)).isEqualTo(token);
            assertThat(service.containsPii(session.handle(), token + " Bob")).isTrue();
            assertThat(service.redact(session.handle(), token + " Bob"))
                    .isEqualTo(token + "[REDACTED_PII]")
                    .doesNotContain("Bob");
        }
    }

    @Test
    void tokenizeReanalyzesTextAfterAnEarlierEmptyResult() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer recoveringAnalyzer = (text, options) -> analysisCalls.incrementAndGet() == 1
                ? List.of()
                : List.of(new PiiSpan("PERSON", 0, 5, 0.95));
        PrivacyService service = new PrivacyService(
                List.of(recoveringAnalyzer),
                PiiAnalysisOptions.defaults()
        );

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenize(session.handle(), "Alice")).isEqualTo("Alice");
            assertThat(service.tokenize(session.handle(), "Alice"))
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
            assertThat(analysisCalls.get()).isEqualTo(2);
        }
    }

    @Test
    void scoreContractsRejectNonFiniteValues() {
        assertThatThrownBy(() -> new PiiSpan("PERSON", 0, 1, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PiiAnalysisOptions.builder().minimumScore(Double.POSITIVE_INFINITY).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PiiResolutionPolicy.builder()
                .providerMinimumScores(Map.of("test", Double.NEGATIVE_INFINITY))
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typeAndConfigurationKeysHaveStrictContracts() {
        assertThatThrownBy(() -> new PiiSpan(
                "X".repeat(EntityTypeRegistry.MAX_ENTITY_TYPE_LENGTH + 1),
                0,
                1,
                1.0
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("entityType is too long");
        assertThatThrownBy(() -> new PiiSpan("customer-id", 0, 1, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
        assertThatThrownBy(() -> new EntityTypeRegistry(Map.of("customer-id", "CUSTOMER_ID")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
        assertThatThrownBy(() -> PiiResolutionPolicy.builder()
                .providerMinimumScores(Map.of("presidio", 0.7, "PRESIDIO", 0.8))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical duplicates");
        assertThatThrownBy(() -> PiiResolutionPolicy.builder()
                .supplementalProviders(Set.of("presidio", "PRESIDIO"))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical duplicates");
        assertThatThrownBy(() -> PiiResolutionPolicy.builder()
                .supplementalProviders(List.of("PRESIDIO", "PRESIDIO"))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical duplicates");
    }

    @Test
    void analyzerAssertionFailureIsSanitizedAtTheCoreBoundary() {
        PiiAnalyzer analyzer = (text, options) -> {
            throw new AssertionError("Analyzer exposed Alice");
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        assertThatThrownBy(() -> service.analyze("Alice"))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_EXECUTION_FAILED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                    assertThat(failure.getMessage()).doesNotContain("Alice", "AssertionError");
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void arbitraryAnalyzerErrorIsSanitizedButFatalJvmErrorIsRethrown() {
        PiiAnalyzer nonFatal = (text, options) -> {
            throw new Error("Analyzer exposed " + text);
        };
        PrivacyService safeService = new PrivacyService(List.of(nonFatal), PiiAnalysisOptions.defaults());

        assertThatThrownBy(() -> safeService.analyze("Alice"))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageNotContaining("Alice")
                .hasNoCause();

        StackOverflowError fatal = new StackOverflowError("Alice");
        PiiAnalyzer fatalAnalyzer = (text, options) -> {
            throw fatal;
        };
        PrivacyService fatalService = new PrivacyService(List.of(fatalAnalyzer), PiiAnalysisOptions.defaults());
        assertThatThrownBy(() -> fatalService.analyze("Alice")).isSameAs(fatal);
    }

    @Test
    void analyzerFailureCannotInjectSensitiveStackTraceMetadata() {
        IllegalStateException analyzerFailure = new IllegalStateException("Alice");
        analyzerFailure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("Alice", "Alice", "Alice.java", 1)
        });
        PiiAnalyzer analyzer = (text, options) -> {
            throw analyzerFailure;
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        assertThatThrownBy(() -> service.analyze("Alice"))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.getMessage()).doesNotContain("Alice");
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getStackTrace())
                            .noneMatch(element -> element.toString().contains("Alice"));
                });
    }

    @Test
    void automaticAnalysisFailsClosedWithoutAnAnalyzer() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());

        assertThatThrownBy(() -> service.analyze("Alice"))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("No PII analyzer")
                .hasMessageNotContaining("Alice");
        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> service.tokenize(session.handle(), "Alice"))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessageContaining("No PII analyzer")
                    .hasMessageNotContaining("Alice");
        }
    }

    @Test
    void malformedAnalyzerTypeFailsSafelyWithoutExposingSourceText() {
        PiiAnalyzer analyzer = (text, options) -> List.of(
                new PiiSpan("Alice Smith", 0, 5, 0.95)
        );
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        assertThatThrownBy(() -> service.analyze("Alice"))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure ->
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_EXECUTION_FAILED)
                )
                .hasMessageNotContaining("Alice")
                .hasMessageNotContaining("Smith");
    }

    @Test
    void analyzerCapabilityTrustsItsCustomTypeWithoutAcceptingOtherUnknownTypes() {
        PiiAnalyzer analyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                return List.of(
                        new PiiSpan("CUSTOMER_ID", 0, 8, 0.95),
                        new PiiSpan("UNREGISTERED", 9, 15, 0.95)
                );
            }

            @Override
            public Set<String> trustedEntityTypes() {
                return Set.of("CUSTOMER_ID");
            }
        };
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.builder().includedEntityTypes(List.of("CUSTOMER_ID")).build()
        );

        assertThat(service.analyze("CUST-123 secret"))
                .extracting(ResolvedPiiSpan::entityType)
                .containsExactly("CUSTOMER_ID");
    }

    @Test
    void analyzerCapabilityTrustAppliesOnlyToEvidenceFromThatProvider() {
        PiiAnalyzer localAnalyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                return List.of(new PiiSpan("EMPLOYEE_ID", 0, text.length(), 0.95));
            }

            @Override
            public String providerId() {
                return "LOCAL";
            }

            @Override
            public Set<String> trustedEntityTypes() {
                return Set.of("EMPLOYEE_ID");
            }
        };
        PiiAnalyzer remoteAnalyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                return List.of(new PiiSpan("EMPLOYEE_ID", 0, text.length(), 0.95));
            }

            @Override
            public String providerId() {
                return "REMOTE";
            }
        };

        PrivacyService localOnly = new PrivacyService(
                List.of(localAnalyzer),
                PiiAnalysisOptions.defaults()
        );
        PrivacyService remoteOnly = new PrivacyService(
                List.of(remoteAnalyzer),
                PiiAnalysisOptions.defaults()
        );
        PrivacyService combined = new PrivacyService(
                List.of(localAnalyzer, remoteAnalyzer),
                PiiAnalysisOptions.defaults()
        );

        assertThat(localOnly.analyze("EMP-1000"))
                .extracting(ResolvedPiiSpan::entityType)
                .containsExactly("EMPLOYEE_ID");
        assertThat(remoteOnly.analyze("EMP-1000"))
                .extracting(ResolvedPiiSpan::entityType)
                .containsExactly("PII");
        assertThat(combined.analyze("EMP-1000"))
                .extracting(ResolvedPiiSpan::entityType)
                .containsExactly("PII");
    }

    @Test
    void callerProvidedSpansRejectNullContracts() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> service.tokenize(session.handle(), "Alice", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("spans must not be null");
            assertThatThrownBy(() -> service.tokenize(
                    session.handle(),
                    "Alice",
                    Collections.singletonList(null)
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("spans must not contain null");
        }
    }

    @Test
    void callerProvidedSpansUseTheSameOverlapResolutionAsAnalyzerEvidence() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());
        String text = "abcdef123456";
        List<PiiSpan> spans = List.of(
                new PiiSpan("PERSON", 0, 7, 0.8),
                new PiiSpan("ACCOUNT", 5, 12, 0.9)
        );

        try (PrivacySession session = service.openSession()) {
            String tokenized = service.tokenize(session.handle(), text, spans);

            assertThat(tokenized).matches(OpaquePiiTokenFormat.patternForEntityType("PII"));
            assertThat(service.detokenize(session.handle(), tokenized)).isEqualTo(text);
        }
        assertThat(service.redact(text, spans)).isEqualTo("[REDACTED_PII]");
    }

    @Test
    void callerProvidedSpansValidateRangesBeforeScoreFiltering() {
        PrivacyService service = new PrivacyService(
                List.of(),
                PiiAnalysisOptions.builder().minimumScore(0.9).build()
        );
        PiiSpan invalidLowScoreSpan = new PiiSpan(
                "PERSON",
                0,
                99,
                0.1
        );

        assertThatThrownBy(() -> service.redact("Alice", List.of(invalidLowScoreSpan)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the source text")
                .hasMessageNotContaining("Alice");
    }

    @Test
    void callerProvidedSpansCannotBypassValidationForNullOrBlankSource() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());
        PiiSpan span = new PiiSpan("PERSON", 0, 1, 1.0);

        assertThatThrownBy(() -> service.redact(null, List.of(span)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source text must not be null");
        assertThatThrownBy(() -> service.redact("", List.of(span)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the source text");
        assertThat(service.redact(" ", List.of(span))).isEqualTo("[REDACTED_PERSON]");
    }

    @Test
    void callerProvidedSpansRemainAuthoritativeForBlankSourceText() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            assertThat(service.containsPii(
                    session.handle(),
                    " ",
                    List.of(new PiiSpan("PERSON", 0, 1, 1.0))
            )).isTrue();
            assertThat(service.containsPii(session.handle(), " ", List.of())).isFalse();
        }
    }

    @Test
    void constructorRejectsDuplicateProviderNames() {
        PiiAnalyzer first = (text, options) -> List.of();
        PiiAnalyzer second = (text, options) -> List.of();

        assertThat(first.providerId()).isEqualTo("CUSTOM");
        assertThatThrownBy(() -> new PrivacyService(List.of(first, second), PiiAnalysisOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate PII analyzer provider CUSTOM");
    }

    private static PiiAnalyzer segmentedAnalyzer(
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
        };
    }

    private PiiAnalyzer personAnalyzer() {
        return (text, options) -> {
            if ("Alice called Alice".equals(text)) {
                return List.of(
                        new PiiSpan("PERSON", 0, 5, 0.95),
                        new PiiSpan("PERSON", 13, 18, 0.95)
                );
            }
            int index = text.indexOf("Alice");
            if (index < 0) {
                return List.of();
            }
            return List.of(new PiiSpan("PERSON", index, index + 5, 0.95));
        };
    }
}

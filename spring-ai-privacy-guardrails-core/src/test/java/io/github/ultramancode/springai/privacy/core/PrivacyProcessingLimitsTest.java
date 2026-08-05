package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyProcessingLimitsTest {

    @Test
    void detokenizeScansThousandsOfMappingsWithoutSearchingForEachMapping() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());
        StringBuilder source = new StringBuilder();
        List<PiiSpan> spans = new ArrayList<>();
        for (int index = 0; index < 5_000; index++) {
            if (index > 0) {
                source.append(' ');
            }
            int start = source.length();
            source.append("id").append(String.format("%05d", index));
            spans.add(new PiiSpan("CUSTOMER_ID", start, source.length(), 1.0));
        }

        try (PrivacySession session = service.openSession()) {
            String tokenized = service.tokenize(session.handle(), source.toString(), spans);

            assertThat(service.detokenize(session.handle(), tokenized))
                    .isEqualTo(source.toString());
        }
    }

    @Test
    void tokenizationRejectsPathologicalOutputAmplificationBeforeCreatingMappings() {
        String text = "a".repeat(50_000);
        String longType = "X".repeat(128);
        PrivacyService service = new PrivacyService(
                List.of(),
                PiiAnalysisOptions.defaults(),
                new EntityTypeRegistry(Map.of(), Set.of(longType)),
                PiiResolutionPolicy.defaults()
        );
        List<PiiSpan> spans = new ArrayList<>();
        for (int index = 0; index < text.length(); index++) {
            spans.add(new PiiSpan(longType, index, index + 1, 1.0));
        }

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> service.tokenize(session.handle(), text, spans))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOKENIZATION);
                    });
        }
    }

    @Test
    void redactionReportsRedactionPhaseWhenAmplificationExceedsTheLimit() {
        String text = "a".repeat(60_000);
        String longType = "X".repeat(128);
        PrivacyService service = new PrivacyService(
                List.of(),
                PiiAnalysisOptions.defaults(),
                new EntityTypeRegistry(Map.of(), Set.of(longType)),
                PiiResolutionPolicy.defaults()
        );
        List<PiiSpan> spans = new ArrayList<>();
        for (int index = 0; index < text.length(); index++) {
            spans.add(new PiiSpan(longType, index, index + 1, 1.0));
        }

        assertThatThrownBy(() -> service.redact(text, spans))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.REDACTION);
                });
    }

    @Test
    void detokenizationRejectsRepeatedExpansionPastTheOutputLimit() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());
        String original = "x".repeat(250_000);

        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(
                    session.handle(),
                    original,
                    List.of(new PiiSpan("SECRET", 0, original.length(), 1.0))
            );
            String repeated = token.repeat(33);

            assertThatThrownBy(() -> service.detokenize(session.handle(), repeated))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.DETOKENIZATION);
                    });
        }
    }

    @Test
    void coreRejectsOversizedAnalyzerResultsBeforeIteratingThem() {
        PiiAnalyzer analyzer = (text, options) -> oversizedSpanList();
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        assertThatThrownBy(() -> service.analyze("A"))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                });
    }

    @Test
    void coreSharesTheAnalyzerResultBoundAcrossProviders() {
        PiiSpan span = new PiiSpan("PERSON", 0, 1, 1.0);
        PiiAnalyzer first = namedAnalyzer(
                "FIRST",
                (text, options) -> Collections.nCopies(60_000, span)
        );
        PiiAnalyzer second = namedAnalyzer("SECOND", (text, options) -> new AbstractList<>() {
            @Override
            public PiiSpan get(int index) {
                throw new AssertionError("the provider overflow must be rejected before iteration");
            }

            @Override
            public int size() {
                return 40_001;
            }
        });
        PrivacyService service = new PrivacyService(
                List.of(first, second),
                PiiAnalysisOptions.defaults()
        );

        assertThatThrownBy(() -> service.analyze("A"))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                });
    }

    @Test
    void callerSuppliedSpansUseTheSameHardCardinalityBound() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());

        assertThatThrownBy(() -> service.redact("A", oversizedSpanList()))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                });
    }

    @Test
    void regexStopsCollectingAtTheAnalyzerResultBound() {
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule("CHARACTER", ".", 1.0, 0)
        ));

        assertThatThrownBy(() -> analyzer.analyze(
                "a".repeat(PiiAnalyzer.MAX_RESULT_SPANS + 1),
                PiiAnalysisOptions.defaults()
        )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
            assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION);
            assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
        });
    }

    private static List<PiiSpan> oversizedSpanList() {
        return new AbstractList<>() {
            @Override
            public PiiSpan get(int index) {
                throw new AssertionError("oversized results must be rejected before iteration");
            }

            @Override
            public int size() {
                return PiiAnalyzer.MAX_RESULT_SPANS + 1;
            }
        };
    }

    private static PiiAnalyzer namedAnalyzer(String providerId, PiiAnalyzer delegate) {
        return new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                return delegate.analyze(text, options);
            }

            @Override
            public String providerId() {
                return providerId;
            }
        };
    }
}

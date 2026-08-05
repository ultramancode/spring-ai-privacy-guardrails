package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveRecordToStringTest {

    @Test
    void piiRecordsDoNotRenderCapturedTextOrUntrustedRecognizerDetails() {
        PiiSpan span = new PiiSpan("ALICE_SMITH", 0, 5, 0.95);
        PiiEvidence evidence = new PiiEvidence(
                span.entityType(), span.start(), span.end(), "REMOTE", span.score()
        );
        ResolvedPiiSpan resolved = new ResolvedPiiSpan(
                "PII", 0, 5, List.of(evidence), PiiResolutionReason.SINGLE_EVIDENCE
        );
        PiiAnalysisResult result = new PiiAnalysisResult(List.of(resolved), Set.of("REMOTE"), List.of());
        PiiTokenizationResult tokenization = new PiiTokenizationResult(
                "public-prefix [[PII_PERSON_opaque_1]] public-suffix",
                result
        );

        assertThat(span.toString()).doesNotContain("Alice", "ALICE_SMITH", "recognizer");
        assertThat(evidence.toString()).doesNotContain("Alice", "ALICE_SMITH", "recognizer");
        assertThat(resolved.toString()).doesNotContain("Alice", "ALICE_SMITH", "recognizer");
        assertThat(result.toString()).doesNotContain("Alice", "ALICE_SMITH", "recognizer");
        assertThat(tokenization.toString())
                .doesNotContain(
                        "Alice",
                        "ALICE_SMITH",
                        "public-prefix",
                        "public-suffix",
                        "[[PII_PERSON"
                );
    }
}

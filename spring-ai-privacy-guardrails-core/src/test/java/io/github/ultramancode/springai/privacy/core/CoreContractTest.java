package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreContractTest {

    @Test
    void publicAnalysisRecordsDoNotRetainSourceText() {
        assertThat(Stream.of(PiiSpan.class, PiiEvidence.class, ResolvedPiiSpan.class)
                .flatMap(type -> Stream.of(type.getRecordComponents()))
                .map(RecordComponent::getName))
                .doesNotContain("text", "source", "recognizer");
        assertThat(Stream.of(ResolvedPiiSpan.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("score", "provider", "recognizer");
        assertThat(PrivacyContextHandle.class.getConstructors()).isEmpty();
    }

    @Test
    void configurationObjectsRejectNullInsteadOfSelectingImplicitDefaults() {
        assertThatThrownBy(() -> new PrivacyService(List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("options must not be null");
        assertThatThrownBy(() -> new PrivacyService(
                List.of(),
                PiiAnalysisOptions.defaults(),
                null,
                PiiResolutionPolicy.defaults()
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("entityTypeRegistry must not be null");
        assertThatThrownBy(() -> new PrivacyService(
                List.of(),
                PiiAnalysisOptions.defaults(),
                EntityTypeRegistry.defaults(),
                null
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("resolutionPolicy must not be null");
        assertThatThrownBy(() -> new EntityTypeRegistry(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("aliases must not be null");
    }

    @Test
    void analysisOptionsCanonicalizeCaseButRejectMalformedValues() {
        assertThat(PiiAnalysisOptions.builder().language("KO").build().language()).isEqualTo("ko");
        assertThat(PiiAnalysisOptions.builder().language("pt-BR").build().language()).isEqualTo("pt-br");
        assertThat(PiiAnalysisOptions.canonicalizeLanguageCode("a".repeat(64)))
                .isEqualTo("a".repeat(64));
        assertThatThrownBy(() -> PiiAnalysisOptions.canonicalizeLanguageCode("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("language is too long");
        assertThatThrownBy(() -> PiiAnalysisOptions.builder().language(" KO ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language must use ASCII");
        assertThatThrownBy(() -> new PiiAnalysisOptions(null, List.of(), 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("language must not be blank");
        assertThatThrownBy(() -> new PiiAnalysisOptions("en", null, 0.0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("includedEntityTypes must not be null");
        assertThatThrownBy(() -> new PiiAnalysisOptions(
                "en",
                Collections.singletonList(null),
                0.0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank values");
        assertThatThrownBy(() -> PiiAnalysisOptions.builder()
                .includedEntityTypes(List.of("email-address"))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
        assertThatThrownBy(() -> PiiAnalysisOptions.builder()
                .includedEntityTypes(List.of("EMAIL_ADDRESS", "EMAIL_ADDRESS"))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
        assertThatThrownBy(() -> new PrivacyService(
                List.of(),
                PiiAnalysisOptions.builder().includedEntityTypes(List.of("PER", "PERSON")).build(),
                new EntityTypeRegistry(Map.of("PER", "PERSON")),
                PiiResolutionPolicy.defaults()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical duplicates");
    }

    @Test
    void resolutionPolicyRejectsConfigurationThatWouldNeverBeUsed() {
        assertThatThrownBy(() -> PiiResolutionPolicy.builder()
                .supplementalProviders(Set.of("PRESIDIO"))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not used in UNION");
        assertThatThrownBy(() -> PiiResolutionPolicy.builder()
                .primaryProvider("PRESIDIO")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not used by this UNION policy");
        assertThatThrownBy(() -> PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY)
                .primaryProvider("PRESIDIO")
                .supplementalProviders(Set.of("presidio"))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not also be supplemental");
        assertThatThrownBy(() -> PiiResolutionPolicy.builder()
                .providerMinimumScores(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("providerMinimumScores");
        assertThatThrownBy(() -> PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY)
                .primaryProvider("PRESIDIO")
                .supplementalProviders(Collections.singletonList(null))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("providerId must not be blank");
        assertThatThrownBy(() -> PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY_WITH_FALLBACK)
                .primaryProvider("PRESIDIO")
                .failurePolicy(PiiAnalyzerFailurePolicy.REQUIRE_PRIMARY)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires ALLOW_PARTIAL");
    }

    @Test
    void sensitiveValueObjectsRejectIncompleteOrInconsistentState() {
        assertThatThrownBy(() -> new PiiSpan("PERSON", 0, 5, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("score");
        assertThatThrownBy(() -> new PiiAnalysisResult(null, Set.of(), List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("spans must not be null");
        assertThatThrownBy(() -> new PiiTokenizationResult("safe", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("analysis must not be null");
        assertThatThrownBy(() -> new PiiAnalyzerFailure(
                null,
                PrivacyFailureCode.ANALYZER_EXECUTION_FAILED,
                PrivacyPhase.ANALYSIS,
                1
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("providerId must not be blank");
    }

    @Test
    void analyzerProviderIdsCanonicalizeCaseButRejectMalformedValues() {
        assertThat(PiiProviderId.canonicalize("my-provider")).isEqualTo("MY-PROVIDER");
        assertThat(PiiProviderId.canonicalize("My_Provider")).isEqualTo("MY_PROVIDER");
        assertThat(PiiProviderId.canonicalize("a".repeat(128))).isEqualTo("A".repeat(128));
        assertThatThrownBy(() -> PiiProviderId.canonicalize("a".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("providerId is too long");
        assertThatThrownBy(() -> PiiProviderId.canonicalize(" my-provider "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerId must use ASCII");
        assertThatThrownBy(() -> PiiProviderId.canonicalize("my@provider"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerId must use ASCII");
        assertThatThrownBy(() -> PiiProviderId.canonicalize("my__provider"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeated separators");

        PiiAnalyzer analyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                return List.of();
            }

            @Override
            public String providerId() {
                return "---";
            }
        };

        assertThatThrownBy(() -> new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerId must use ASCII");
    }

    @Test
    void entityAllowlistDoesNotImplicitlyTrustAnalyzerOutputTypes() {
        PiiAnalyzer analyzer = (text, options) -> List.of(new PiiSpan("CUSTOMER_ID", 0, 1, 1.0));

        assertThatThrownBy(() -> new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.builder().includedEntityTypes(List.of("CUSTOMER_ID")).build()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("untrusted type CUSTOMER_ID");
    }
}

package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("presidio")
@TestPropertySource(properties = {
        "spring.ai.privacy.analysis.included-entity-types[0]=PERSON",
        "spring.ai.privacy.analysis.included-entity-types[1]=EMAIL_ADDRESS",
        "spring.ai.privacy.analysis.included-entity-types[2]=NATIONAL_ID"
})
@EnabledIfEnvironmentVariable(named = "PRESIDIO_LIVE_TEST", matches = "true")
class PresidioProfileLiveIntegrationTest {

    @Autowired
    private PrivacyService privacyService;

    @Test
    void profileProtectsStockEnglishPersonAndEmailDetection() {
        String input = "John Smith can be reached at john.smith@example.com";
        var spans = this.privacyService.analyzeDetailed(input).spans();

        assertThat(spans)
                .filteredOn(span -> "PERSON".equals(span.entityType()))
                .anySatisfy(span -> {
                    assertThat(input.substring(span.start(), span.end())).isEqualTo("John Smith");
                    assertThat(span.evidence())
                            .extracting(evidence -> evidence.provider())
                            .contains("PRESIDIO");
                });
        assertThat(spans)
                .filteredOn(span -> "EMAIL_ADDRESS".equals(span.entityType()))
                .anySatisfy(span -> {
                    assertThat(input.substring(span.start(), span.end()))
                            .isEqualTo("john.smith@example.com");
                    assertThat(span.evidence())
                            .extracting(evidence -> evidence.provider())
                            .contains("PRESIDIO");
                });
    }

    @Test
    void canonicalNationalIdFilterDoesNotBecomeAnInvalidPresidioNativeRequestFilter() {
        String input = "SSN: 212-45-6789";

        var result = this.privacyService.analyzeDetailed(input);

        assertThat(result.successfulProviders()).contains("PRESIDIO");
        assertThat(result.failures()).isEmpty();
        assertThat(result.spans()).singleElement().satisfies(span -> {
            assertThat(span.entityType()).isEqualTo("NATIONAL_ID");
            assertThat(input.substring(span.start(), span.end())).isEqualTo("212-45-6789");
            assertThat(span.evidence())
                    .extracting(evidence -> evidence.provider())
                    .contains("PRESIDIO");
        });
    }
}

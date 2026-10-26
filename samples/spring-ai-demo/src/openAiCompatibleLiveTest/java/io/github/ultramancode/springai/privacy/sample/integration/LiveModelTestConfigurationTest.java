package io.github.ultramancode.springai.privacy.sample.integration;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisResult;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.ResolvedPiiSpan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = LiveModelTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.banner-mode=off",
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "spring.ai.model.embedding.text=none",
                "spring.ai.model.embedding.multimodal=none",
                "spring.ai.model.image=none",
                "spring.ai.model.audio.transcription=none",
                "spring.ai.model.audio.speech=none",
                "spring.ai.model.moderation=none"
        }
)
class LiveModelTestConfigurationTest {

    @Autowired
    private PrivacyService privacyService;

    @Test
    void loadsConfiguredEmployeeAndCustomerRules() {
        PiiAnalysisResult result = this.privacyService.analyzeDetailed(
                "Employee EMP-1234 handles customer CUST-123456."
        );

        assertThat(result.failures()).isEmpty();
        assertThat(result.spans())
                .extracting(ResolvedPiiSpan::entityType)
                .containsExactly("EMPLOYEE_ID", "CUSTOMER_ID");
    }
}

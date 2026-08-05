package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("opennlp")
@EnabledIfEnvironmentVariable(named = "OPENNLP_LIVE_TEST", matches = "true")
class OpenNlpProfileLiveIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PrivacyService privacyService;

    @Test
    void profileProtectsPersonDetectedByApplicationSuppliedModels() throws Exception {
        this.mockMvc.perform(post("/demo/protect")
                        .contentType("application/json")
                        .content("{\"text\":\"John Smith joined the board.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.not(
                        Matchers.containsString("John Smith")
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.startsWith(
                        "[[PII_PERSON_"
                )))
                .andExpect(jsonPath("$.detectedSpans[0].type").value("PERSON"))
                .andExpect(jsonPath("$.detectedSpans[0].start").value(0))
                .andExpect(jsonPath("$.detectedSpans[0].end").value(10))
                .andExpect(jsonPath("$.detectedSpans[0].providers").value(Matchers.contains(
                        "OPENNLP"
                )))
                .andExpect(jsonPath("$.successfulProviders").value(Matchers.contains(
                        "OPENNLP",
                        "REGEX"
                )));

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }
}

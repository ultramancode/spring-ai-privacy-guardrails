package io.github.ultramancode.springai.privacy.sample.mcp.controller;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class McpToolLoopEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PrivacyService privacyService;

    @Test
    @Timeout(20)
    void repeatedMcpToolLoopCallsPreservePrivacyEvidenceAcrossLanguages() throws Exception {
        assertMcpToolLoopEvidence(
                "en",
                "Local model received protected MCP result: ",
                "CRM result: Employee ID is [PII_EMPLOYEE_ID_"
        );
        assertThat(this.privacyService.activeSessionCount()).isZero();

        assertMcpToolLoopEvidence(
                "ko",
                "로컬 모델이 보호된 MCP 결과를 수신했습니다: ",
                "CRM 결과: 직원번호는 [PII_EMPLOYEE_ID_"
        );
        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    private void assertMcpToolLoopEvidence(
            String language,
            String finalResponsePrefix,
            String protectedCrmResult
    ) throws Exception {
        this.mockMvc.perform(get("/demo/mcp-tool-loop")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, language))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelCalls").value(2))
                .andExpect(jsonPath("$.protectedModelInput").value(Matchers.containsString(
                        "[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.tokenizedToolArguments").value(Matchers.containsString(
                        "[PII_CUSTOMER_ID_"
                )))
                .andExpect(jsonPath("$.allowedOriginalEntityTypes[0]").value("CUSTOMER_ID"))
                .andExpect(jsonPath("$.toolReceivedOnlyAllowedOriginals").value(true))
                .andExpect(jsonPath("$.toolLookupSucceeded").value(true))
                .andExpect(jsonPath("$.toolResultRetokenizedBeforeModel").value(true))
                .andExpect(jsonPath("$.boundaryEvidence.modelRawValues.observed").value(0))
                .andExpect(jsonPath("$.boundaryEvidence.modelRawValues.total").value(4))
                .andExpect(jsonPath("$.boundaryEvidence.modelRawValues.passed").value(true))
                .andExpect(jsonPath("$.boundaryEvidence.deniedToolRawValues.observed").value(0))
                .andExpect(jsonPath("$.boundaryEvidence.deniedToolRawValues.total").value(3))
                .andExpect(jsonPath("$.boundaryEvidence.deniedToolRawValues.passed").value(true))
                .andExpect(jsonPath("$.boundaryEvidence.allowedToolRawValues.observed").value(1))
                .andExpect(jsonPath("$.boundaryEvidence.allowedToolRawValues.total").value(1))
                .andExpect(jsonPath("$.boundaryEvidence.allowedToolRawValues.passed").value(true))
                .andExpect(jsonPath("$.boundaryEvidence.rawToolResultValuesAtModel.observed").value(0))
                .andExpect(jsonPath("$.boundaryEvidence.rawToolResultValuesAtModel.total").value(4))
                .andExpect(jsonPath("$.boundaryEvidence.rawToolResultValuesAtModel.passed").value(true))
                .andExpect(jsonPath("$.finalResponse").value(Matchers.containsString(
                        "[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.finalResponse").value(Matchers.containsString(
                        protectedCrmResult
                )))
                .andExpect(jsonPath("$.finalResponse").value(Matchers.startsWith(
                        finalResponsePrefix
                )))
                .andExpect(jsonPath("$.activeSessionsAfterCall").value(0))
                .andExpect(content().string(Matchers.not(Matchers.containsString("EMP-1234"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString(
                        "test@example.com"
                ))))
                .andExpect(content().string(Matchers.not(Matchers.containsString(
                        "010-1234-5678"
                ))))
                .andExpect(content().string(Matchers.not(Matchers.containsString(
                        "CUST-123456"
                ))))
                .andExpect(content().string(Matchers.not(Matchers.containsString(
                        "tokenMappings"
                ))));
    }
}

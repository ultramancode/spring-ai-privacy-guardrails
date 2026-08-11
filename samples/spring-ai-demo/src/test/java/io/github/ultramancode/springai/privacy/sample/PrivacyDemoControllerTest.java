package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.modelcontextprotocol.client.McpSyncClient;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PrivacyDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private PrivacyDemoMcpToolLoop mcpToolLoop;

    @Test
    void inspectorIsServedAsASampleOnlyBoundaryVisualization() throws Exception {
        this.mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(Matchers.containsString(
                        "Privacy Boundary Inspector"
                )))
                .andExpect(content().string(Matchers.containsString(
                        "Model raw values"
                )))
                .andExpect(content().string(Matchers.containsString(
                        "Retokenized result"
                )))
                .andExpect(content().string(Matchers.containsString(
                        "json(\"/demo/scenario\")"
                )))
                .andExpect(content().string(Matchers.not(Matchers.containsString(
                        "직원번호는 EMP-1234이고"
                ))));
    }

    @Test
    void scenarioReturnsOnlyTheFixedSyntheticInputUsedByTheInspector() throws Exception {
        this.mockMvc.perform(get("/demo/scenario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.input").value(
                        "직원번호는 EMP-1234이고 이메일은 test@example.com, "
                                + "전화번호는 010-1234-5678, 고객번호는 CUST-123456입니다."
                ))
                .andExpect(jsonPath("$.protectedPrompt").doesNotExist())
                .andExpect(jsonPath("$.tokenMappings").doesNotExist());
    }

    @Test
    void protectReturnsProtectedPromptWithoutRawMappingsOrSourceSubstrings() throws Exception {
        this.mockMvc.perform(get("/demo/protect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawUserInput").doesNotExist())
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.not(
                        Matchers.containsString("EMP-1234"))))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "[[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString("]]이고 이메일")))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "[[PII_EMAIL_ADDRESS_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "[[PII_PHONE_NUMBER_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "[[PII_CUSTOMER_ID_"
                )))
                .andExpect(jsonPath("$.detectedSpans[0].text").doesNotExist())
                .andExpect(jsonPath("$.detectedSpans[0].recognizer").doesNotExist())
                .andExpect(jsonPath("$.detectedSpans[0].providers[0]").value("REGEX"))
                .andExpect(jsonPath("$.successfulProviders[0]").value("REGEX"))
                .andExpect(jsonPath("$.tokenMappings").doesNotExist());

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    void chatClientRunsAutoConfiguredAdvisorsAgainstLocalModel() throws Exception {
        this.mockMvc.perform(get("/demo/chat-client"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawUserInput").doesNotExist())
                .andExpect(jsonPath("$.modelResponse").value(Matchers.containsString(
                        "Local model received:"
                )))
                .andExpect(jsonPath("$.modelResponse").value(Matchers.containsString(
                        "[[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.modelResponse").value(Matchers.not(
                        Matchers.containsString("EMP-1234")
                )))
                .andExpect(jsonPath("$.activeSessionsAfterCall").value(0));

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    void ragDemoProtectsRetrievedPiiBeforeTheModelBoundary() throws Exception {
        this.mockMvc.perform(get("/demo/rag"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrievedDocument").value(
                        "Customer account owner email: alice@example.com"
                ))
                .andExpect(jsonPath("$.modelVisibleContext").value(Matchers.containsString(
                        "Customer account owner email: [[PII_EMAIL_ADDRESS_"
                )))
                .andExpect(jsonPath("$.modelVisibleContext").value(Matchers.not(
                        Matchers.containsString("alice@example.com")
                )))
                .andExpect(jsonPath("$.retrievedDocumentContainsRawPii").value(true))
                .andExpect(jsonPath("$.modelVisibleContextContainsRawPii").value(false))
                .andExpect(jsonPath("$.modelVisibleContextContainsTokenizedPii").value(true))
                .andExpect(jsonPath("$.activeSessionsAfterCall").value(0))
                .andExpect(jsonPath("$.tokenMappings").doesNotExist());

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    void protectAcceptsDocumentedEnglishTextWithStructuredIdentifiers() throws Exception {
        this.mockMvc.perform(post("/demo/protect")
                        .contentType("application/json")
                        .content("""
                                {"text":"Email alice@example.com, phone 010-2345-6789, customer ID CUST-654321, employee ID EMP-1234."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "[[PII_EMAIL_ADDRESS_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "[[PII_PHONE_NUMBER_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "[[PII_CUSTOMER_ID_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "[[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.not(
                        Matchers.containsString("alice@example.com")
                )))
                .andExpect(jsonPath("$.successfulProviders[0]").value("REGEX"));

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    void postEndpointsRejectBlankTextInsteadOfSilentlyUsingTheDefaultExample() throws Exception {
        this.mockMvc.perform(post("/demo/protect")
                        .contentType("application/json")
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
        this.mockMvc.perform(post("/demo/chat-client")
                        .contentType("application/json")
                        .content("{\"text\":null}"))
                .andExpect(status().isBadRequest());

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    void toolLoopExposesOriginalOnlyInsideActualChatClientToolExecution() throws Exception {
        this.mockMvc.perform(get("/demo/tool-loop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("actual-chat-client-tool-loop"))
                .andExpect(jsonPath("$.modelCalls").value(2))
                .andExpect(jsonPath("$.modelSawOnlyTokens").value(true))
                .andExpect(jsonPath("$.protectedModelInput").value(Matchers.containsString(
                        "[[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.protectedModelInput").value(Matchers.not(
                        Matchers.containsString("EMP-1234")
                )))
                .andExpect(jsonPath("$.tokenizedToolArguments").value(Matchers.containsString(
                        "[[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.tokenizedToolArguments").value(Matchers.containsString(
                        "[[PII_EMAIL_ADDRESS_"
                )))
                .andExpect(jsonPath("$.tokenizedToolArguments").value(Matchers.containsString(
                        "[[PII_PHONE_NUMBER_"
                )))
                .andExpect(jsonPath("$.tokenizedToolArguments").value(Matchers.containsString(
                        "[[PII_CUSTOMER_ID_"
                )))
                .andExpect(jsonPath("$.tokenizedToolArguments").value(Matchers.not(
                        Matchers.containsString("EMP-1234"))))
                .andExpect(jsonPath("$.allowedOriginalEntityTypes[0]").value("CUSTOMER_ID"))
                .andExpect(jsonPath("$.toolReceivedOnlyAllowedOriginals").value(true))
                .andExpect(jsonPath("$.toolLookupSucceededWithRestoredCustomerId").value(true))
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
                        "[[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.finalResponse").value(Matchers.not(
                        Matchers.containsString("EMP-1234"))))
                .andExpect(jsonPath("$.activeSessionsAfterCall").value(0))
                .andExpect(content().string(Matchers.not(
                        Matchers.containsString("test@example.com"))))
                .andExpect(content().string(Matchers.not(
                        Matchers.containsString("010-1234-5678"))))
                .andExpect(content().string(Matchers.not(
                        Matchers.containsString("CUST-123456"))))
                .andExpect(content().string(Matchers.not(
                        Matchers.containsString("tokenMappings"))));

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    @Timeout(20)
    void repeatedMcpToolLoopCallsReuseRuntimeAndPreservePrivacyEvidence() throws Exception {
        assertMcpToolLoopEvidence();
        assertThat(this.privacyService.activeSessionCount()).isZero();

        LocalMcpCrmServer firstServer = (LocalMcpCrmServer) ReflectionTestUtils.getField(
                this.mcpToolLoop,
                "localMcpServer"
        );
        McpSyncClient firstClient = (McpSyncClient) ReflectionTestUtils.getField(
                this.mcpToolLoop,
                "mcpClient"
        );
        assertThat(firstServer).isNotNull();
        assertThat(firstClient).isNotNull();
        assertThat(firstClient.getCurrentInitializationResult()).isNotNull();
        int callsAfterFirstRequest = firstServer.calls();

        assertMcpToolLoopEvidence();
        assertThat(this.privacyService.activeSessionCount()).isZero();

        assertThat(ReflectionTestUtils.getField(this.mcpToolLoop, "localMcpServer"))
                .isSameAs(firstServer);
        assertThat(ReflectionTestUtils.getField(this.mcpToolLoop, "mcpClient"))
                .isSameAs(firstClient);
        assertThat(firstServer.calls()).isEqualTo(callsAfterFirstRequest + 1);
    }

    private void assertMcpToolLoopEvidence() throws Exception {
        this.mockMvc.perform(get("/demo/mcp-tool-loop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("actual-streamable-http-mcp-tool-loop"))
                .andExpect(jsonPath("$.modelCalls").value(2))
                .andExpect(jsonPath("$.modelSawOnlyTokens").value(true))
                .andExpect(jsonPath("$.protectedModelInput").value(Matchers.containsString(
                        "[[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.tokenizedToolArguments").value(Matchers.containsString(
                        "[[PII_CUSTOMER_ID_"
                )))
                .andExpect(jsonPath("$.allowedOriginalEntityTypes[0]").value("CUSTOMER_ID"))
                .andExpect(jsonPath("$.toolReceivedOnlyAllowedOriginals").value(true))
                .andExpect(jsonPath("$.toolLookupSucceededWithRestoredCustomerId").value(true))
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
                        "[[PII_EMPLOYEE_ID_"
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

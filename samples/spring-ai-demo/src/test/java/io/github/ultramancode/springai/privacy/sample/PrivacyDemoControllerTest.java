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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    void inspectorServesAllRuntimeScenariosWithOneLocalizedStaticPage() throws Exception {
        byte[] pageBytes = this.mockMvc.perform(get("/index.html"))
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
                        "id=\"scenarioSelector\""
                )))
                .andExpect(content().string(Matchers.containsString(
                        "<option value=\"local-tool\">Local Tool</option>"
                )))
                .andExpect(content().string(Matchers.containsString(
                        "<option value=\"rag\">RAG</option>"
                )))
                .andExpect(content().string(Matchers.containsString(
                        "<option value=\"mcp\">MCP</option>"
                )))
                .andExpect(content().string(Matchers.containsString(
                        "id=\"languageToggle\""
                )))
                .andExpect(content().string(Matchers.containsString(
                        "data-language=\"ko\""
                )))
                .andExpect(content().string(Matchers.containsString(
                        "const translations ="
                )))
                .andExpect(content().string(Matchers.containsString(
                        "id=\"retrievedDocument\""
                )))
                .andExpect(content().string(Matchers.containsString(
                        "id=\"modelVisibleContext\""
                )))
                .andExpect(content().string(Matchers.containsString(
                        "id=\"mcpMode\""
                )))
                .andExpect(content().string(Matchers.containsString(
                        "id=\"mcpAllowedOriginalEntityTypes\""
                )))
                .andExpect(content().string(Matchers.containsString(
                        "id=\"mcpFinalResponse\""
                )))
                .andExpect(content().string(Matchers.containsString(
                        "json(\"/demo/scenario\")"
                )))
                .andExpect(content().string(Matchers.not(Matchers.containsString(
                        "User, memory, and RAG text enter the same final model boundary."
                ))))
                .andExpect(content().string(Matchers.not(Matchers.containsString(
                        "activeSessionsAfterCall === 0"
                ))))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        String page = new String(pageBytes, StandardCharsets.UTF_8);

        assertThat(page)
                .doesNotContain("직원번호는 EMP-1234이고")
                .contains(
                        "RAG 흐름 실행",
                        "\"status.wait\": \"대기\"",
                        "\"status.value\": \"값\"",
                        "\"flow.input\": \"입력\"",
                        "\"error.returnedHttp\": \"반환 HTTP 상태\""
                );
        assertScenarioWiring(page, "local-tool", "/demo/tool-loop", "runLocalTool");
        assertScenarioWiring(page, "rag", "/demo/rag", "runRag");
        assertScenarioWiring(page, "mcp", "/demo/mcp-tool-loop", "runMcp");
        assertTranslationCoverage(page);
    }

    @Test
    void inspectorDoesNotMaintainASeparateKoreanHtmlResource() throws Exception {
        this.mockMvc.perform(get("/index-ko.html"))
                .andExpect(status().isNotFound());
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

    private static void assertScenarioWiring(
            String page,
            String scenarioKey,
            String endpoint,
            String runner
    ) {
        String property = scenarioKey.contains("-") ? "\"" + scenarioKey + "\"" : scenarioKey;
        assertThat(page).containsPattern(Pattern.compile(
                Pattern.quote(property)
                        + "\\s*:\\s*\\{[^}]*endpoint:\\s*\""
                        + Pattern.quote(endpoint)
                        + "\"[^}]*}"
        ));
        assertThat(page).containsPattern(Pattern.compile(
                "async\\s+function\\s+"
                        + Pattern.quote(runner)
                        + "\\(scenario\\)\\s*\\{[^}]*json\\(scenario\\.endpoint\\)"
        ));
        if ("mcp".equals(scenarioKey)) {
            assertThat(page).containsPattern(Pattern.compile(
                    "else\\s*\\{\\s*await\\s+" + Pattern.quote(runner) + "\\(scenario\\);"
            ));
        } else {
            assertThat(page).containsPattern(Pattern.compile(
                    "scenarioKey\\s*===\\s*\""
                            + Pattern.quote(scenarioKey)
                            + "\"\\)\\s*\\{\\s*await\\s+"
                            + Pattern.quote(runner)
                            + "\\(scenario\\);"
            ));
        }
    }

    private static void assertTranslationCoverage(String page) {
        int translationsStart = page.indexOf("const translations =");
        int englishStart = page.indexOf("en: {", translationsStart);
        int koreanStart = page.indexOf("ko: {", englishStart);
        int translationsEnd = page.indexOf("const scenarios =", koreanStart);
        assertThat(translationsStart).isGreaterThanOrEqualTo(0);
        assertThat(englishStart).isGreaterThan(translationsStart);
        assertThat(koreanStart).isGreaterThan(englishStart);
        assertThat(translationsEnd).isGreaterThan(koreanStart);

        Set<String> requiredKeys = attributeKeys(page);
        requiredKeys.addAll(Set.of(
                "lead.rag",
                "lead.mcp",
                "run.rag",
                "run.mcp",
                "running",
                "errorPrefix",
                "error.returnedHttp"
        ));
        Set<String> englishKeys = translationKeys(page.substring(englishStart, koreanStart));
        Set<String> koreanKeys = translationKeys(page.substring(koreanStart, translationsEnd));

        assertThat(englishKeys).containsAll(requiredKeys);
        assertThat(koreanKeys).containsAll(requiredKeys);
        assertThat(koreanKeys).containsExactlyInAnyOrderElementsOf(englishKeys);
    }

    private static Set<String> attributeKeys(String page) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("data-i18n(?:-aria-label)?=\"([^\"]+)\"").matcher(page);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private static Set<String> translationKeys(String translationBlock) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(
                "(?m)^\\s+(?:\"([^\"]+)\"|([A-Za-z][A-Za-z0-9]*))\\s*:"
        ).matcher(translationBlock);
        while (matcher.find()) {
            keys.add(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        }
        return keys;
    }
}

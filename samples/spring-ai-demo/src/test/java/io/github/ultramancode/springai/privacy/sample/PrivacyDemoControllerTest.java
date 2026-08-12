package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.modelcontextprotocol.client.McpSyncClient;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
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
                        "Model raw exposure"
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
                        "json(\"/demo/scenario\", locale)"
                )))
                .andExpect(content().string(Matchers.containsString(
                        "\"Accept-Language\": locale"
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
                .doesNotContain(
                        "Employee ID is EMP-1234",
                        "직원번호는 EMP-1234이고"
                )
                .contains(
                        "RAG 흐름 실행",
                        "\"status.wait\": \"대기\"",
                        "\"status.done\": \"DONE\"",
                        "\"status.done\": \"완료\"",
                        "\"error.returnedHttp\": \"반환 HTTP 상태\"",
                        "\"flow.sessionsReported\": \"Session cleanup\"",
                        "\"flow.sessionsReported\": \"세션 정리\"",
                        "\"analyzer.resolution\": \"Resolution reason\"",
                        "\"analyzer.resolution\": \"판정 이유\"",
                        "Start/end positions are character offsets in the original input.",
                        "시작/끝 위치는 입력 원문 내 문자 위치(offset)입니다.",
                        "SINGLE_EVIDENCE means the range was finalized from one detection signal.",
                        "SINGLE_EVIDENCE는 하나의 탐지 근거만으로 해당 범위가 최종 확정되었음을 의미합니다.",
                        "\"analyzer.start\": \"Start offset\"",
                        "\"analyzer.start\": \"시작 위치\"",
                        "\"analyzer.end\": \"End offset\"",
                        "\"analyzer.end\": \"끝 위치\"",
                        "The CRM tool result is tokenized and protected again before model re-entry.",
                        "CRM 도구 결과는 모델에 다시 전달되기 전에 재토큰화되어 보호됩니다.",
                        "This is the actual document text returned by vector search.",
                        "벡터 검색으로 조회된 실제 문서 원문입니다.",
                        "Raw PII has been replaced with opaque tokens.",
                        "원문 개인정보는 불투명 토큰으로 대체되어 있습니다.",
                        "\"rag.evidenceTitle\": \"Backend verification evidence\"",
                        "\"rag.evidenceTitle\": \"백엔드 검증 근거\"",
                        "Displays the /demo/rag backend response values that support the results above.",
                        "위 결과의 근거가 되는 /demo/rag 백엔드 응답 값을 표시합니다.",
                        "data-i18n=\"rag.evidenceRetrievedRaw\">Retrieved document contains raw PII</span>",
                        "\"rag.evidenceRetrievedRaw\": \"검색 문서에 원문 PII 포함\"",
                        "data-i18n=\"rag.evidenceModelRaw\">Model context contains raw PII</span>",
                        "\"rag.evidenceModelRaw\": \"모델 컨텍스트에 원문 PII 포함\"",
                        "data-i18n=\"rag.evidenceModelToken\">Model context contains protected tokens</span>",
                        "\"rag.evidenceModelToken\": \"모델 컨텍스트에 보호 토큰 포함\"",
                        "data-i18n=\"rag.evidenceActiveSessions\">Service-wide active sessions after call</span>",
                        "\"rag.evidenceActiveSessions\": \"호출 후 서비스 전체 활성 세션\"",
                        "<code>retrievedDocumentContainsRawPii</code>",
                        "<code>modelVisibleContextContainsRawPii</code>",
                        "<code>modelVisibleContextContainsTokenizedPii</code>",
                        "<code>activeSessionsAfterCall</code>",
                        "data-i18n=\"mcp.runtimeModeId\">Runtime mode ID</span>",
                        "\"mcp.runtimeModeId\": \"실행 모드 ID\"",
                        "data-i18n=\"mcp.modelCalls\">Model call count</span>",
                        "\"mcp.modelCalls\": \"모델 호출 횟수\"",
                        "Execution information from a round-trip call to the actual local Streamable HTTP MCP server.",
                        "실제 로컬 Streamable HTTP MCP 서버를 왕복 호출한 실행 정보입니다.",
                        "This is the input delivered to the actual model boundary.",
                        "실제 모델 경계에 전달된 입력입니다.",
                        "data-i18n-aria-label=\"local.boundaryDisclosure\"",
                        "data-i18n-aria-label=\"mcp.boundaryDisclosure\"",
                        "Within this request, only CUSTOMER_ID is restored to its original value and sent to the CRM tool.",
                        "Within this request, only CUSTOMER_ID is restored to its original value and sent to the MCP tool.",
                        "현재 요청 안에서 CUSTOMER_ID만 원문으로 복원되어 CRM 도구에 전달됩니다.",
                        "현재 요청 안에서 CUSTOMER_ID만 원문으로 복원되어 MCP 도구에 전달됩니다.",
                        "\"disclosure.before\": \"Before tool boundary\"",
                        "\"disclosure.tokenized\": \"TOKENIZED\"",
                        "class=\"disclosure-boundary local-disclosure-boundary\"",
                        "class=\"tool-boundary-focus\"",
                        "class=\"secondary-evidence\"",
                        "\"disclosure.atBoundary\": \"Tool boundary\"",
                        "\"disclosure.customerOnlyRestored\": \"ORIGINAL RESTORED\"",
                        "\"disclosure.customerOnlyRestored\": \"원문 복원\"",
                        "data-i18n=\"disclosure.localCustomerOnlyRestored\">ONLY · ORIGINAL RESTORED</strong>",
                        "\"disclosure.localCustomerOnlyRestored\": \"만 원문 복원\"",
                        "\"disclosure.currentRequestOnly\": \"THIS REQUEST ONLY\"",
                        "\"disclosure.currentRequestOnly\": \"현재 요청에서만\"",
                        "\"disclosure.crmLookupSucceeded\": \"CRM lookup succeeded\"",
                        "\"disclosure.crmLookupSucceeded\": \"CRM 조회 성공\"",
                        "\"disclosure.mcpLookupSucceeded\": \"MCP tool lookup succeeded\"",
                        "\"disclosure.mcpLookupSucceeded\": \"MCP 도구 조회 성공\"",
                        "\"disclosure.othersTokenized\": \"Other PII remains tokenized\"",
                        "\"disclosure.othersTokenized\": \"다른 PII는 계속 토큰 상태\"",
                        "\"disclosure.rawOmitted\": \"The restored value is sent only to the tool and is not displayed in this Inspector.\"",
                        "\"disclosure.rawOmitted\": \"실제 복원 값은 도구에만 전달되며, 이 화면에는 표시되지 않습니다.\"",
                        "id=\"allowedDisclosureCheck\"",
                        "id=\"mcpAllowedDisclosureCheck\"",
                        "setCheck(prefix, \"allowedDisclosureCheck\", tool.toolReceivedOnlyAllowedOriginals)",
                        "setCheck(prefix, \"lookupCheck\", tool.toolLookupSucceededWithRestoredCustomerId)",
                        ".opaque-token .token-entity",
                        ".opaque-token .token-detail",
                        "function prettyJsonPayload(value)",
                        "JSON.stringify(parsed, null, 2)",
                        "function prettyFinalResponse(value)",
                        "const objectStart = value.indexOf(\"{\")",
                        "const payload = value.slice(objectStart)",
                        "const parsed = JSON.parse(payload)",
                        "return prefix + \"\\n\\n\" + JSON.stringify(parsed, null, 2)",
                        "function renderTokenEvidence(id, value, prettyJson = false)",
                        "entity.textContent = match[2]",
                        "detail.textContent = match[3]",
                        "renderTokenEvidence(prefixed(prefix, \"protected\"), tool.protectedModelInput)",
                        "renderTokenEvidence(prefixed(prefix, \"toolArguments\"), tool.tokenizedToolArguments, true)",
                        "renderTokenEvidence(prefixed(prefix, \"finalResponse\"), prettyFinalResponse(tool.finalResponse))",
                        "renderTokenEvidence(\"modelVisibleContext\", rag.modelVisibleContext)",
                        "class=\"evidence-grid\"",
                        "class=\"card step-card step-1\"",
                        ".evidence-grid .step-1 { grid-column: 1 / 26; grid-row: 1; }",
                        ".evidence-grid .step-2 { grid-column: 1 / 14; grid-row: 2; }",
                        ".evidence-grid .step-3 { grid-column: 14 / 26; grid-row: 2; }",
                        ".evidence-grid .step-4 { grid-column: 1 / 11; grid-row: 3; }",
                        ".evidence-grid .step-5 { grid-column: 11 / 26; grid-row: 3; }",
                        ".evidence-grid .step-6 { grid-column: 1 / 26; grid-row: 4; }",
                        "class=\"card step-card step-5\"",
                        "class=\"card step-card step-6\"",
                        "class=\"rag-boundary\"",
                        "class=\"rag-boundary-flow\"",
                        "id=\"ragBoundaryInput\"",
                        "id=\"ragBoundaryOutput\"",
                        "id=\"ragBoundaryState\"",
                        "\"rag.boundaryProtected\": \"보호됨\"",
                        "function renderRagBoundary(rag)",
                        "renderRagBoundary(rag)",
                        "id=\"modelRawMeaning\"",
                        "id=\"mcpAllowedToolMeaning\"",
                        "\"summary.none\": \"NONE\"",
                        "\"summary.none\": \"없음\"",
                        "\"summary.customerOnly\": \"CUSTOMER_ID ONLY\"",
                        "\"summary.customerOnly\": \"CUSTOMER_ID만\"",
                        "\"rag.present\": \"PRESENT\"",
                        "\"rag.present\": \"있음\"",
                        "\"rag.protected\": \"PROTECTED\"",
                        "\"rag.protected\": \"보호됨\"",
                        "The MCP tool result is retokenized before it is sent back to the model.",
                        "MCP 도구 결과는 모델로 다시 전달되기 전에 재토큰화됩니다.",
                        "setStageCompleted(prefix, \"stageCleanup\")"
                )
                .doesNotContain(
                        "setStageValue(prefix, \"stageCleanup\", tool.activeSessionsAfterCall)",
                        "setStageBoolean(prefix, \"stageCleanup\"",
                        "\"summary.cleanupComplete\"",
                        "\"summary.sessionsRemain\"",
                        "data-i18n=\"rag.boundaryProtected\">PROTECTED</em>",
                        "data-i18n=\"flow.input\"",
                        "\"flow.input\":",
                        "id=\"runtimeBadges\"",
                        "renderBadges(",
                        "runtimeEvidence: \"Runtime evidence\"",
                        "runtimeEvidence: \"런타임 근거\"",
                        "MCP transaction",
                        "MCP 트랜잭션",
                        "data-i18n-aria-label=\"local.boundaryPath\"",
                        "data-i18n-aria-label=\"mcp.boundaryPath\"",
                        "\"boundary.modelArguments\"",
                        "\"boundary.localExecution\"",
                        "\"boundary.mcpExecution\"",
                        "\"boundary.resultRetokenized\"",
                        "id=\"modelCheck\"",
                        "id=\"toolCheck\"",
                        "id=\"resultCheck\"",
                        "id=\"mcpModelCheck\"",
                        "id=\"mcpToolCheck\"",
                        "id=\"mcpResultCheck\"",
                        "class=\"disclosure-stage",
                        "class=\"disclosure-verdict",
                        "class=\"entity-state-list\"",
                        "class=\"entity-state\"",
                        "\"disclosure.allTokenized\"",
                        "\"disclosure.policy\"",
                        "\"disclosure.allowedRestored\"",
                        "\"disclosure.stillTokenized\"",
                        "\"disclosure.lookupSucceeded\""
                );
        assertScenarioWiring(page, "local-tool", "/demo/tool-loop", "runLocalTool");
        assertScenarioWiring(page, "rag", "/demo/rag", "runRag");
        assertScenarioWiring(page, "mcp", "/demo/mcp-tool-loop", "runMcp");
        assertTranslationCoverage(page);
    }

    @Test
    void inspectorOmitsGlobalSessionSummaryCardsAndBindsRagBoundaryToBackendEvidence() throws Exception {
        String page = this.mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(page)
                .contains(
                        "data-i18n=\"rag.evidenceActiveSessions\">Service-wide active sessions after call</span>",
                        "<code>activeSessionsAfterCall</code>",
                        "element(\"ragActiveSessionsDetail\").textContent = activeSessions",
                        "function renderRagBoundary(rag)",
                        "rag.retrievedDocumentContainsRawPii",
                        "!rag.modelVisibleContextContainsRawPii",
                        "rag.modelVisibleContextContainsTokenizedPii",
                        "protectedBoundary ? \"rag.boundaryProtected\" : \"rag.boundaryNotConfirmed\"",
                        "renderRagBoundary(rag)"
                )
                .doesNotContain(
                        "id=\"activeSessionsMetric\"",
                        "id=\"ragActiveSessionsMetric\"",
                        "id=\"mcpActiveSessionsMetric\"",
                        "\"metric.sessions\"",
                        "\"summary.serviceSnapshot\"",
                        "setValueMetric(",
                        "summary.cleanupComplete",
                        "summary.sessionsRemain",
                        "data-i18n=\"rag.boundaryProtected\">PROTECTED</em>"
                );
    }

    @Test
    void inspectorDoesNotMaintainASeparateKoreanHtmlResource() throws Exception {
        this.mockMvc.perform(get("/index-ko.html"))
                .andExpect(status().isNotFound());
    }

    @Test
    void scenarioReturnsTheFixedSyntheticInputForEachRequestedLocale() throws Exception {
        this.mockMvc.perform(get("/demo/scenario")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.input").value(
                        "Employee ID is EMP-1234, email is test@example.com, "
                                + "phone is 010-1234-5678, and customer ID is CUST-123456."
                ))
                .andExpect(jsonPath("$.protectedPrompt").doesNotExist())
                .andExpect(jsonPath("$.tokenMappings").doesNotExist());

        this.mockMvc.perform(get("/demo/scenario")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.input").value(
                        "직원번호는 EMP-1234이고, 이메일은 test@example.com, "
                                + "전화번호는 010-1234-5678, 고객번호는 CUST-123456입니다."
                ))
                .andExpect(jsonPath("$.protectedPrompt").doesNotExist())
                .andExpect(jsonPath("$.tokenMappings").doesNotExist());
    }

    @Test
    void protectReturnsProtectedPromptWithoutRawMappingsOrSourceSubstrings() throws Exception {
        this.mockMvc.perform(get("/demo/protect")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawUserInput").doesNotExist())
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.not(
                        Matchers.containsString("EMP-1234"))))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "[[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "Employee ID is [[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString("]], email is")))
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
                .andExpect(jsonPath("$.detectedSpans[0].start").value(15))
                .andExpect(jsonPath("$.detectedSpans[0].end").value(23))
                .andExpect(jsonPath("$.detectedSpans[1].start").value(34))
                .andExpect(jsonPath("$.detectedSpans[1].end").value(50))
                .andExpect(jsonPath("$.detectedSpans[2].start").value(61))
                .andExpect(jsonPath("$.detectedSpans[2].end").value(74))
                .andExpect(jsonPath("$.detectedSpans[3].start").value(95))
                .andExpect(jsonPath("$.detectedSpans[3].end").value(106))
                .andExpect(jsonPath("$.successfulProviders[0]").value("REGEX"))
                .andExpect(jsonPath("$.tokenMappings").doesNotExist());

        this.mockMvc.perform(get("/demo/protect")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "직원번호는 [[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString("]]이고, 이메일은")))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.not(
                        Matchers.containsString("EMP-1234"))))
                .andExpect(jsonPath("$.detectedSpans[0].start").value(6))
                .andExpect(jsonPath("$.detectedSpans[0].end").value(14))
                .andExpect(jsonPath("$.detectedSpans[1].start").value(23))
                .andExpect(jsonPath("$.detectedSpans[1].end").value(39))
                .andExpect(jsonPath("$.detectedSpans[2].start").value(47))
                .andExpect(jsonPath("$.detectedSpans[2].end").value(60))
                .andExpect(jsonPath("$.detectedSpans[3].start").value(68))
                .andExpect(jsonPath("$.detectedSpans[3].end").value(79));

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    void chatClientUsesTheFixedEnglishExampleAndRunsAutoConfiguredAdvisors() throws Exception {
        this.mockMvc.perform(get("/demo/chat-client")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawUserInput").doesNotExist())
                .andExpect(jsonPath("$.modelResponse").value(Matchers.containsString(
                        "Local model received:"
                )))
                .andExpect(jsonPath("$.modelResponse").value(Matchers.containsString(
                        "Employee ID is [[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.modelResponse").value(Matchers.not(
                        Matchers.containsString("직원번호는")
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
    void ragDemoUsesLocalizedRuntimeFixturesAndProtectsBothModelBoundaries() throws Exception {
        this.mockMvc.perform(get("/demo/rag")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrievedDocument").value(
                        "Customer account owner email: alice@example.com"
                ))
                .andExpect(jsonPath("$.modelVisibleContext").value(Matchers.containsString(
                        "Customer account owner email: [[PII_EMAIL_ADDRESS_"
                )))
                .andExpect(jsonPath("$.modelVisibleContext").value(Matchers.containsString(
                        "Which email owns the customer account?"
                )))
                .andExpect(jsonPath("$.modelVisibleContext").value(Matchers.not(
                        Matchers.containsString("alice@example.com")
                )))
                .andExpect(jsonPath("$.retrievedDocumentContainsRawPii").value(true))
                .andExpect(jsonPath("$.modelVisibleContextContainsRawPii").value(false))
                .andExpect(jsonPath("$.modelVisibleContextContainsTokenizedPii").value(true))
                .andExpect(jsonPath("$.activeSessionsAfterCall").value(0))
                .andExpect(jsonPath("$.tokenMappings").doesNotExist());

        this.mockMvc.perform(get("/demo/rag")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrievedDocument").value(
                        "고객 계정 소유자 이메일: alice@example.com"
                ))
                .andExpect(jsonPath("$.modelVisibleContext").value(Matchers.containsString(
                        "고객 계정 소유자의 이메일은 무엇인가요?"
                )))
                .andExpect(jsonPath("$.modelVisibleContext").value(Matchers.containsString(
                        "고객 계정 소유자 이메일: [[PII_EMAIL_ADDRESS_"
                )))
                .andExpect(jsonPath("$.modelVisibleContext").value(Matchers.containsString(
                        "사전 지식이 아니라 주어진 컨텍스트와 대화 이력을 사용해 답하세요."
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
        this.mockMvc.perform(get("/demo/tool-loop")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("actual-chat-client-tool-loop"))
                .andExpect(jsonPath("$.modelCalls").value(2))
                .andExpect(jsonPath("$.modelSawOnlyTokens").value(true))
                .andExpect(jsonPath("$.protectedModelInput").value(Matchers.containsString(
                        "[[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.protectedModelInput").value(Matchers.containsString(
                        "Employee ID is [[PII_EMPLOYEE_ID_"
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
                .andExpect(jsonPath("$.finalResponse").value(Matchers.startsWith(
                        "Local model received protected CRM result: "
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
    void toolLoopUsesTheKoreanFixtureAndBackendGeneratedKoreanResultWrapper() throws Exception {
        this.mockMvc.perform(get("/demo/tool-loop")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protectedModelInput").value(Matchers.containsString(
                        "직원번호는 [[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.protectedModelInput").value(Matchers.containsString(
                        "]]이고, 이메일은 [[PII_EMAIL_ADDRESS_"
                )))
                .andExpect(jsonPath("$.modelSawOnlyTokens").value(true))
                .andExpect(jsonPath("$.toolReceivedOnlyAllowedOriginals").value(true))
                .andExpect(jsonPath("$.toolResultRetokenizedBeforeModel").value(true))
                .andExpect(jsonPath("$.boundaryEvidence.modelRawValues.passed").value(true))
                .andExpect(jsonPath("$.boundaryEvidence.deniedToolRawValues.passed").value(true))
                .andExpect(jsonPath("$.boundaryEvidence.allowedToolRawValues.passed").value(true))
                .andExpect(jsonPath("$.boundaryEvidence.rawToolResultValuesAtModel.passed").value(true))
                .andExpect(jsonPath("$.finalResponse").value(Matchers.startsWith(
                        "로컬 모델이 보호된 CRM 결과를 수신했습니다: "
                )))
                .andExpect(jsonPath("$.activeSessionsAfterCall").value(0))
                .andExpect(content().string(Matchers.not(Matchers.containsString("EMP-1234"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString(
                        "test@example.com"
                ))));

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    @Timeout(20)
    void repeatedMcpToolLoopCallsReuseRuntimeAndPreservePrivacyEvidence() throws Exception {
        assertMcpToolLoopEvidence(
                "en",
                "Local model received protected MCP result: ",
                "CRM result: Employee ID is [[PII_EMPLOYEE_ID_"
        );
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

        assertMcpToolLoopEvidence(
                "ko",
                "로컬 모델이 보호된 MCP 결과를 수신했습니다: ",
                "CRM 결과: 직원번호는 [[PII_EMPLOYEE_ID_"
        );
        assertThat(this.privacyService.activeSessionCount()).isZero();

        assertThat(ReflectionTestUtils.getField(this.mcpToolLoop, "localMcpServer"))
                .isSameAs(firstServer);
        assertThat(ReflectionTestUtils.getField(this.mcpToolLoop, "mcpClient"))
                .isSameAs(firstClient);
        assertThat(firstServer.calls()).isEqualTo(callsAfterFirstRequest + 1);
    }

    private void assertMcpToolLoopEvidence(
            String language,
            String finalResponsePrefix,
            String protectedCrmResult
    ) throws Exception {
        this.mockMvc.perform(get("/demo/mcp-tool-loop")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, language))
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
                        + "\\(scenario,\\s*locale\\)\\s*\\{[^}]*json\\(scenario\\.endpoint,\\s*locale\\)"
        ));
        if ("mcp".equals(scenarioKey)) {
            assertThat(page).containsPattern(Pattern.compile(
                    "else\\s*\\{\\s*await\\s+" + Pattern.quote(runner)
                            + "\\(scenario,\\s*locale\\);"
            ));
        } else {
            assertThat(page).containsPattern(Pattern.compile(
                    "scenarioKey\\s*===\\s*\""
                             + Pattern.quote(scenarioKey)
                             + "\"\\)\\s*\\{\\s*await\\s+"
                             + Pattern.quote(runner)
                            + "\\(scenario,\\s*locale\\);"
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

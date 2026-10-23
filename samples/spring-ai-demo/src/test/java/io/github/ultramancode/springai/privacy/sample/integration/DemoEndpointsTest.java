package io.github.ultramancode.springai.privacy.sample.integration;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.sample.privacy.dto.response.ProtectedPromptResponse.DetectedSpan;
import io.github.ultramancode.springai.privacy.sample.privacy.dto.response.ProtectedPromptResponse;
import io.github.ultramancode.springai.privacy.sample.shared.model.CustomerDemoData;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DemoEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void inspectorServesThePage() throws Exception {
        this.mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(Matchers.containsString("Privacy Boundary Inspector")));
    }

    @Test
    void demoInputReturnsTheFixedSyntheticInputForEachRequestedLocale() throws Exception {
        this.mockMvc.perform(get("/demo/example-input")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.input").value(
                        "Employee ID is EMP-1234, email is test@example.com, "
                                + "phone is 010-1234-5678, and customer ID is CUST-123456."
                ))
                .andExpect(jsonPath("$.protectedPrompt").doesNotExist())
                .andExpect(jsonPath("$.tokenMappings").doesNotExist());

        this.mockMvc.perform(get("/demo/example-input")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.input").value(
                        "직원번호는 EMP-1234이고, 이메일은 test@example.com, "
                                + "전화번호는 010-1234-5678, 고객번호는 CUST-123456입니다."
                ))
                .andExpect(jsonPath("$.protectedPrompt").doesNotExist())
                .andExpect(jsonPath("$.tokenMappings").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ko"})
    void protectReturnsAccurateSpansWithoutExposingRawValues(String language) throws Exception {
        CustomerDemoData demoData = CustomerDemoData.getByLocale(Locale.forLanguageTag(language));
        String body = this.mockMvc.perform(get("/demo/protect")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, language))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawUserInput").doesNotExist())
                .andExpect(jsonPath("$.tokenMappings").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        List<String> originalValues = demoData.originalValues();
        assertThat(body).doesNotContain(originalValues.toArray(String[]::new));

        ProtectedPromptResponse response = this.objectMapper.readValue(body, ProtectedPromptResponse.class);
        assertThat(response.successfulProviders()).containsExactly("REGEX");
        assertThat(response.detectedSpans()).extracting(DetectedSpan::type)
                .containsExactly("EMPLOYEE_ID", "EMAIL_ADDRESS", "PHONE_NUMBER", "CUSTOMER_ID");
        for (int index = 0; index < response.detectedSpans().size(); index++) {
            DetectedSpan span = response.detectedSpans().get(index);
            assertThat(demoData.input().substring(span.start(), span.end()))
                    .isEqualTo(originalValues.get(index));
            assertThat(span.providers()).containsExactly("REGEX");
            assertThat(OpaquePiiTokenFormat.patternForEntityType(span.type())
                    .matcher(response.protectedPrompt()).find()).isTrue();
        }

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    void chatClientProtectsTheFixedEnglishExample() throws Exception {
        this.mockMvc.perform(get("/demo/chat-client")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawUserInput").doesNotExist())
                .andExpect(jsonPath("$.finalResponse").value(Matchers.containsString(
                        "Local model received:"
                )))
                .andExpect(jsonPath("$.finalResponse").value(Matchers.containsString(
                        "Employee ID is [PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.finalResponse").value(Matchers.not(
                        Matchers.containsString("직원번호는")
                )))
                .andExpect(jsonPath("$.finalResponse").value(Matchers.not(
                        Matchers.containsString("EMP-1234")
                )))
                .andExpect(jsonPath("$.activeSessionsAfterCall").value(0));

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    void ragDemoProtectsRetrievedDocumentsBeforeTheyReachTheModelInBothLanguages() throws Exception {
        this.mockMvc.perform(get("/demo/rag")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrievedDocument").value(
                        "Customer account owner email: alice@example.com"
                ))
                .andExpect(jsonPath("$.modelInput").value(Matchers.containsString(
                        "Customer account owner email: [PII_EMAIL_ADDRESS_"
                )))
                .andExpect(jsonPath("$.modelInput").value(Matchers.containsString(
                        "Which email owns the customer account?"
                )))
                .andExpect(jsonPath("$.modelInput").value(Matchers.not(
                        Matchers.containsString("alice@example.com")
                )))
                .andExpect(jsonPath("$.retrievedDocumentContainsRawPii").value(true))
                .andExpect(jsonPath("$.modelInputContainsRawPii").value(false))
                .andExpect(jsonPath("$.modelInputContainsTokenizedPii").value(true))
                .andExpect(jsonPath("$.activeSessionsAfterCall").value(0))
                .andExpect(jsonPath("$.tokenMappings").doesNotExist());

        this.mockMvc.perform(get("/demo/rag")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrievedDocument").value(
                        "고객 계정 소유자 이메일: alice@example.com"
                ))
                .andExpect(jsonPath("$.modelInput").value(Matchers.containsString(
                        "고객 계정 소유자의 이메일은 무엇인가요?"
                )))
                .andExpect(jsonPath("$.modelInput").value(Matchers.containsString(
                        "고객 계정 소유자 이메일: [PII_EMAIL_ADDRESS_"
                )))
                .andExpect(jsonPath("$.modelInput").value(Matchers.containsString(
                        "사전 지식이 아니라 주어진 컨텍스트와 대화 이력을 사용해 답하세요."
                )))
                .andExpect(jsonPath("$.modelInput").value(Matchers.not(
                        Matchers.containsString("alice@example.com")
                )))
                .andExpect(jsonPath("$.retrievedDocumentContainsRawPii").value(true))
                .andExpect(jsonPath("$.modelInputContainsRawPii").value(false))
                .andExpect(jsonPath("$.modelInputContainsTokenizedPii").value(true))
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
                        "[PII_EMAIL_ADDRESS_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "[PII_PHONE_NUMBER_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "[PII_CUSTOMER_ID_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.containsString(
                        "[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.protectedPrompt").value(Matchers.not(
                        Matchers.containsString("alice@example.com")
                )))
                .andExpect(jsonPath("$.successfulProviders[0]").value("REGEX"));

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    void postEndpointsRequireNonBlankText() throws Exception {
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
    void toolLoopRestoresOnlyAllowedValuesForTheTool() throws Exception {
        this.mockMvc.perform(get("/demo/tool-loop")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelCalls").value(2))
                .andExpect(jsonPath("$.protectedModelInput").value(Matchers.containsString(
                        "Employee ID is [PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.protectedModelInput").value(Matchers.not(
                        Matchers.containsString("EMP-1234")
                )))
                .andExpect(jsonPath("$.tokenizedToolArguments").value(Matchers.containsString(
                        "[PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.tokenizedToolArguments").value(Matchers.containsString(
                        "[PII_EMAIL_ADDRESS_"
                )))
                .andExpect(jsonPath("$.tokenizedToolArguments").value(Matchers.containsString(
                        "[PII_PHONE_NUMBER_"
                )))
                .andExpect(jsonPath("$.tokenizedToolArguments").value(Matchers.containsString(
                        "[PII_CUSTOMER_ID_"
                )))
                .andExpect(jsonPath("$.tokenizedToolArguments").value(Matchers.not(
                        Matchers.containsString("EMP-1234"))))
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
    void toolLoopReturnsKoreanInputAndResult() throws Exception {
        this.mockMvc.perform(get("/demo/tool-loop")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protectedModelInput").value(Matchers.containsString(
                        "직원번호는 [PII_EMPLOYEE_ID_"
                )))
                .andExpect(jsonPath("$.protectedModelInput").value(Matchers.containsString(
                        "]이고, 이메일은 [PII_EMAIL_ADDRESS_"
                )))
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
    void securityBoundaryHidesAndBlocksTheToolForGeneralEmployeesButAllowsCustomerSupport()
            throws Exception {
        this.mockMvc.perform(get("/demo/security-tool-boundary")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generalEmployee.role").value("ROLE_EMPLOYEE"))
                .andExpect(jsonPath("$.generalEmployee.exposedToolNames").isEmpty())
                .andExpect(jsonPath("$.generalEmployee.authorizationChecks[*].phase")
                        .value(Matchers.hasItem("DEFINITION")))
                .andExpect(jsonPath("$.generalEmployee.authorizationChecks[*].granted")
                        .value(Matchers.everyItem(Matchers.is(false))))
                .andExpect(jsonPath("$.generalEmployee.modelRequestedTool").value(true))
                .andExpect(jsonPath("$.generalEmployee.toolCallDenied").value(true))
                .andExpect(jsonPath("$.generalEmployee.denialExceptionType").isNotEmpty())
                .andExpect(jsonPath("$.generalEmployee.callbackInvocations").value(0))
                .andExpect(jsonPath("$.generalEmployee.deniedCallStoppedBeforeCallback")
                        .value(true))
                .andExpect(jsonPath("$.customerSupport.role").value("ROLE_CUSTOMER_SUPPORT"))
                .andExpect(jsonPath("$.customerSupport.exposedToolNames[0]")
                        .value("customerLookup"))
                .andExpect(jsonPath("$.customerSupport.authorizationChecks[*].phase")
                        .value(Matchers.hasItems("DEFINITION", "EXECUTION")))
                .andExpect(jsonPath("$.customerSupport.authorizationChecks[*].granted")
                        .value(Matchers.everyItem(Matchers.is(true))))
                .andExpect(jsonPath("$.customerSupport.modelRequestedTool").value(true))
                .andExpect(jsonPath("$.customerSupport.toolCallDenied").value(false))
                .andExpect(jsonPath("$.customerSupport.callbackInvocations").value(1))
                .andExpect(jsonPath("$.customerSupport.toolReceivedOnlyAllowedOriginals")
                        .value(true))
                .andExpect(jsonPath("$.customerSupport.toolResultRetokenizedBeforeModel")
                        .value(true))
                .andExpect(jsonPath("$.customerSupport.finalResponse").value(Matchers.containsString(
                        "[PII_CUSTOMER_ID_"
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
                ))));

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }
}

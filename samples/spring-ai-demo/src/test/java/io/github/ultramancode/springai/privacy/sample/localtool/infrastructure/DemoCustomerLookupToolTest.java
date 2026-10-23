package io.github.ultramancode.springai.privacy.sample.localtool.infrastructure;

import io.github.ultramancode.springai.privacy.sample.shared.tool.model.CustomerLookupArguments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DemoCustomerLookupToolTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String EMPLOYEE_TOKEN = "[PII_EMPLOYEE_ID_1:AbCdEfGhIjKlMnOp]";
    private static final String EMAIL_TOKEN = "[PII_EMAIL_ADDRESS_1:AbCdEfGhIjKlMnOp]";
    private static final String PHONE_TOKEN = "[PII_PHONE_NUMBER_1:AbCdEfGhIjKlMnOp]";
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    @Test
    void returnsStructuredJsonToolResult() {
        DemoCustomerLookupTool tool = new DemoCustomerLookupTool(OBJECT_MAPPER);

        String result = tool.call("""
                {
                  "employeeId": "protected-employee-id",
                  "email": "protected-email",
                  "phone": "protected-phone",
                  "customerId": "CUST-123456"
                }
                """);

        assertThat(OBJECT_MAPPER.readValue(result, STRING_MAP_TYPE))
                .containsEntry("employeeId", "EMP-1234")
                .containsEntry("email", "test@example.com")
                .containsEntry("phone", "010-1234-5678")
                .containsEntry("customerId", "CUST-123456");
    }

    @Test
    void recordsAllowedDisclosureForMatchingTokenFields() {
        DemoCustomerLookupTool tool = new DemoCustomerLookupTool(OBJECT_MAPPER);
        tool.call(OBJECT_MAPPER.writeValueAsString(new CustomerLookupArguments(
                EMPLOYEE_TOKEN, EMAIL_TOKEN, PHONE_TOKEN, "CUST-123456")));

        assertThat(tool.receivedOnlyAllowedOriginals()).isTrue();
        assertThat(tool.deniedRawValueCount()).isZero();
        assertThat(tool.allowedRawValueCount()).isEqualTo(1);
    }

    @Test
    void doesNotApproveTokensPlacedInTheWrongFields() {
        DemoCustomerLookupTool tool = new DemoCustomerLookupTool(OBJECT_MAPPER);
        tool.call(OBJECT_MAPPER.writeValueAsString(new CustomerLookupArguments(
                EMPLOYEE_TOKEN, PHONE_TOKEN, EMAIL_TOKEN, "CUST-123456")));

        assertThat(tool.lookupSucceeded()).isTrue();
        assertThat(tool.receivedOnlyAllowedOriginals()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"test@example.com", "test\\u0040example.com"})
    void recordsRawDisclosureFromDecodedArgumentValues(String rawEmail) {
        DemoCustomerLookupTool tool = new DemoCustomerLookupTool(OBJECT_MAPPER);
        String toolInput = OBJECT_MAPPER.writeValueAsString(new CustomerLookupArguments(
                EMPLOYEE_TOKEN, EMAIL_TOKEN + " EMAIL_PLACEHOLDER", PHONE_TOKEN, "CUST-123456"));
        tool.call(toolInput.replace("EMAIL_PLACEHOLDER", rawEmail));

        assertThat(tool.receivedOnlyAllowedOriginals()).isFalse();
        assertThat(tool.deniedRawValueCount()).isEqualTo(1);
    }
}

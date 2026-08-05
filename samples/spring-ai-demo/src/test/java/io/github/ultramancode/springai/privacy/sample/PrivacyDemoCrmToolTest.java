package io.github.ultramancode.springai.privacy.sample;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyDemoCrmToolTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    @Test
    void returnsStructuredJsonToolResult() {
        PrivacyDemoCrmTool tool = new PrivacyDemoCrmTool(OBJECT_MAPPER);

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
}

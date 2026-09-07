package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

final class PrivacyDemoCrmTool implements ToolCallback {

    private static final PrivacyDemoScenario SCENARIO = PrivacyDemoScenario.DEFAULT;
    private static final List<String> DENIED_VALUES = SCENARIO.deniedToolValues();
    private static final List<String> ALLOWED_VALUES = SCENARIO.allowedToolValues();
    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name("customerLookup")
            .description("Looks up a customer by the only required original value, CUSTOMER_ID")
            .inputSchema(JsonSchemaGenerator.generateForType(CrmLookupArguments.class))
            .build();
    private static final Map<String, CrmRecord> CRM_RECORDS = Map.of(
            SCENARIO.customerId(),
            new CrmRecord(
                    SCENARIO.employeeId(),
                    SCENARIO.email(),
                    SCENARIO.phone(),
                    SCENARIO.customerId()
            )
    );

    private final ObjectMapper objectMapper;
    private boolean receivedOnlyAllowedOriginals;
    private boolean lookupSucceededWithRestoredCustomerId;
    private int deniedRawValueCount;
    private int allowedRawValueCount;
    private int calls;

    PrivacyDemoCrmTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return TOOL_DEFINITION;
    }

    @Override
    public String call(String toolInput) {
        this.calls++;
        CrmLookupArguments arguments = this.objectMapper.readValue(toolInput, CrmLookupArguments.class);
        this.deniedRawValueCount = countContainedValues(toolInput, DENIED_VALUES);
        this.allowedRawValueCount = countContainedValues(toolInput, ALLOWED_VALUES);
        this.receivedOnlyAllowedOriginals = this.deniedRawValueCount == 0
                && this.allowedRawValueCount == ALLOWED_VALUES.size()
                && OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID").matcher(toolInput).find()
                && OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS").matcher(toolInput).find()
                && OpaquePiiTokenFormat.patternForEntityType("PHONE_NUMBER").matcher(toolInput).find()
                && !OpaquePiiTokenFormat.patternForEntityType("CUSTOMER_ID").matcher(toolInput).find();

        CrmRecord record = CRM_RECORDS.get(arguments.customerId());
        this.lookupSucceededWithRestoredCustomerId = record != null
                && SCENARIO.customerId().equals(arguments.customerId());
        if (record == null) {
            throw new IllegalArgumentException("No demo CRM record exists for the supplied customerId");
        }
        return this.objectMapper.writeValueAsString(record);
    }

    boolean receivedOnlyAllowedOriginals() {
        return this.receivedOnlyAllowedOriginals;
    }

    boolean lookupSucceededWithRestoredCustomerId() {
        return this.lookupSucceededWithRestoredCustomerId;
    }

    int deniedRawValueCount() {
        return this.deniedRawValueCount;
    }

    int allowedRawValueCount() {
        return this.allowedRawValueCount;
    }

    int calls() {
        return this.calls;
    }

    private static int countContainedValues(String text, List<String> values) {
        return Math.toIntExact(values.stream().filter(text::contains).count());
    }

    private record CrmRecord(
            String employeeId,
            String email,
            String phone,
            String customerId
    ) {
    }
}

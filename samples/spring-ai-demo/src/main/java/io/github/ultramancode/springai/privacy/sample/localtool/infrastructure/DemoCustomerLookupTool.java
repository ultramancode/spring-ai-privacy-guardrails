package io.github.ultramancode.springai.privacy.sample.localtool.infrastructure;

import io.github.ultramancode.springai.privacy.sample.shared.model.CustomerDemoData;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.CustomerLookupArguments;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolArgumentEvidence;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static io.github.ultramancode.springai.privacy.sample.shared.tool.support.DemoPiiChecks.checkToolArguments;

public final class DemoCustomerLookupTool implements ToolCallback {

    private static final CustomerDemoData CUSTOMER_DATA = CustomerDemoData.DEFAULT;
    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name("customerLookup")
            .description("Looks up a customer by the only required original value, CUSTOMER_ID")
            .inputSchema(JsonSchemaGenerator.generateForType(CustomerLookupArguments.class))
            .build();
    private static final Map<String, CrmRecord> CRM_RECORDS = Map.of(
            CUSTOMER_DATA.customerId(),
            new CrmRecord(
                    CUSTOMER_DATA.employeeId(),
                    CUSTOMER_DATA.email(),
                    CUSTOMER_DATA.phone(),
                    CUSTOMER_DATA.customerId()
            )
    );

    private final ObjectMapper objectMapper;
    private boolean receivedOnlyAllowedOriginals;
    private boolean lookupSucceeded;
    private int deniedRawValueCount;
    private int allowedRawValueCount;
    private int calls;

    public DemoCustomerLookupTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return TOOL_DEFINITION;
    }

    @Override
    public String call(String toolInput) {
        this.calls++;
        CustomerLookupArguments arguments = this.objectMapper.readValue(toolInput, CustomerLookupArguments.class);
        ToolArgumentEvidence argumentEvidence = checkToolArguments(arguments);
        this.deniedRawValueCount = argumentEvidence.deniedRawValueCount();
        this.allowedRawValueCount = argumentEvidence.allowedRawValueCount();
        this.receivedOnlyAllowedOriginals = argumentEvidence.receivedOnlyAllowedOriginals();

        CrmRecord record = CRM_RECORDS.get(arguments.customerId());

        if (record == null) {
            this.lookupSucceeded = false;
            throw new IllegalArgumentException("No demo CRM record exists for the supplied customerId");
        }

        this.lookupSucceeded = true;
        return this.objectMapper.writeValueAsString(record);
    }

    public boolean receivedOnlyAllowedOriginals() {
        return this.receivedOnlyAllowedOriginals;
    }

    public boolean lookupSucceeded() {
        return this.lookupSucceeded;
    }

    public int deniedRawValueCount() {
        return this.deniedRawValueCount;
    }

    public int allowedRawValueCount() {
        return this.allowedRawValueCount;
    }

    public int calls() {
        return this.calls;
    }

    private record CrmRecord(
            String employeeId,
            String email,
            String phone,
            String customerId
    ) {
    }
}

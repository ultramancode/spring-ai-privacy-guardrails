package io.github.ultramancode.springai.privacy.sample.mcp.infrastructure;

import io.github.ultramancode.springai.privacy.sample.shared.tool.model.CustomerLookupArguments;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public final class McpCustomerLookupTool {

    private Map<String, String> recordsByCustomerId = Map.of();
    private int totalCallCount;
    private boolean lastLookupSucceeded;
    private CustomerLookupArguments lastReceivedArguments;

    public synchronized void replaceCustomerRecords(Map<String, String> recordsByCustomerId) {
        this.recordsByCustomerId = Map.copyOf(recordsByCustomerId);
    }

    public synchronized McpSchema.CallToolResult lookup(McpSchema.CallToolRequest request) {
        CustomerLookupArguments arguments = readArguments(request.arguments());
        String toolResult = this.recordsByCustomerId.get(arguments.customerId());
        this.totalCallCount++;
        this.lastReceivedArguments = arguments;
        if (toolResult == null) {
            this.lastLookupSucceeded = false;
            return McpSchema.CallToolResult.builder()
                    .addTextContent("No CRM record exists for the supplied customerId")
                    .isError(true)
                    .build();
        }
        this.lastLookupSucceeded = true;
        return McpSchema.CallToolResult.builder()
                .addTextContent(toolResult)
                .isError(false)
                .build();
    }

    public synchronized ToolCallState getCallState() {
        return new ToolCallState(this.totalCallCount, this.lastLookupSucceeded, this.lastReceivedArguments);
    }

    private static CustomerLookupArguments readArguments(Map<String, Object> arguments) {
        String employeeId = requireStringArgument(arguments, "employeeId");
        String email = requireStringArgument(arguments, "email");
        String phone = requireStringArgument(arguments, "phone");
        String customerId = requireStringArgument(arguments, "customerId");
        return new CustomerLookupArguments(employeeId, email, phone, customerId);
    }

    private static String requireStringArgument(Map<String, Object> arguments, String argumentName) {
        Object argument = arguments.get(argumentName);
        if (!(argument instanceof String text)) {
            throw new IllegalStateException("MCP argument '%s' must be a string".formatted(argumentName));
        }
        return text;
    }

    public record ToolCallState(
            int totalCallCount,
            boolean lastLookupSucceeded,
            CustomerLookupArguments lastReceivedArguments
    ) {

        public CustomerLookupArguments requireLastReceivedArguments() {
            if (this.lastReceivedArguments == null) {
                throw new IllegalStateException("MCP customer lookup tool has not received a tool call");
            }
            return this.lastReceivedArguments;
        }
    }
}

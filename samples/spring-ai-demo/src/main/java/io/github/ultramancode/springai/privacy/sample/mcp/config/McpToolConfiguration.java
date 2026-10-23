package io.github.ultramancode.springai.privacy.sample.mcp.config;

import io.github.ultramancode.springai.privacy.sample.mcp.infrastructure.McpCustomerLookupTool;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
class McpToolConfiguration {

    @Bean
    List<McpServerFeatures.SyncToolSpecification> mcpCustomerLookupTools(McpCustomerLookupTool customerLookupTool) {
        McpSchema.Tool tool = McpSchema.Tool.builder("customerLookup", createToolInputSchema())
                .description("Looks up a customer by customerId")
                .build();
        McpServerFeatures.SyncToolSpecification specification = McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> customerLookupTool.lookup(request))
                .build();
        return List.of(specification);
    }

    private static Map<String, Object> createToolInputSchema() {
        Map<String, Object> stringProperty = Map.of("type", "string");
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "employeeId", stringProperty,
                        "email", stringProperty,
                        "phone", stringProperty,
                        "customerId", stringProperty
                ),
                "required", List.of("employeeId", "email", "phone", "customerId"),
                "additionalProperties", false
        );
    }
}

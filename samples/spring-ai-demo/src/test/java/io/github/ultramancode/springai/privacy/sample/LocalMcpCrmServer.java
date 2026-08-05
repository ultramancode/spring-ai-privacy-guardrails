package io.github.ultramancode.springai.privacy.sample;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class LocalMcpCrmServer implements AutoCloseable {

    private static final String MCP_ENDPOINT = "/mcp";

    private final Tomcat tomcat;
    private final McpSyncServer mcpServer;
    private final CrmLookupHandler lookupHandler;
    private final int port;

    private LocalMcpCrmServer(
            Tomcat tomcat,
            McpSyncServer mcpServer,
            CrmLookupHandler lookupHandler,
            int port
    ) {
        this.tomcat = tomcat;
        this.mcpServer = mcpServer;
        this.lookupHandler = lookupHandler;
        this.port = port;
    }

    static LocalMcpCrmServer start(Path baseDirectory, Map<String, String> recordsByCustomerId) {
        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .mcpEndpoint(MCP_ENDPOINT)
                        .build();
        CrmLookupHandler lookupHandler = new CrmLookupHandler(recordsByCustomerId);
        McpSchema.Tool tool = McpSchema.Tool.builder("customerLookup", inputSchema())
                .description("Looks up a customer by customerId")
                .build();
        McpSyncServer mcpServer = McpServer.sync(transport)
                .serverInfo("privacy-demo-crm", "1.0.0")
                .tools(McpServerFeatures.SyncToolSpecification.builder()
                        .tool(tool)
                        .callHandler((exchange, request) -> lookupHandler.lookup(request))
                        .build())
                .build();

        Tomcat tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.setBaseDir(baseDirectory.toString());
        Context context = tomcat.addContext("", baseDirectory.toString());
        Wrapper wrapper = context.createWrapper();
        wrapper.setName("mcpServlet");
        wrapper.setServlet(transport);
        wrapper.setLoadOnStartup(1);
        wrapper.setAsyncSupported(true);
        context.addChild(wrapper);
        context.addServletMappingDecoded("/*", "mcpServlet");
        tomcat.getConnector().setAsyncTimeout(3_000);
        try {
            tomcat.start();
        }
        catch (LifecycleException failure) {
            mcpServer.close();
            throw new IllegalStateException("Failed to start the local MCP test server", failure);
        }

        return new LocalMcpCrmServer(
                tomcat,
                mcpServer,
                lookupHandler,
                tomcat.getConnector().getLocalPort()
        );
    }

    McpSyncClient connect() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + this.port)
                .endpoint(MCP_ENDPOINT)
                .build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }

    String receivedArgument(String name) {
        return this.lookupHandler.receivedArgument(name);
    }

    int calls() {
        return this.lookupHandler.calls();
    }

    boolean lookupSucceeded() {
        return this.lookupHandler.lookupSucceeded();
    }

    @Override
    public void close() {
        try {
            this.mcpServer.closeGracefully();
        }
        finally {
            try {
                this.tomcat.stop();
                this.tomcat.destroy();
            }
            catch (LifecycleException failure) {
                throw new IllegalStateException("Failed to stop the local MCP test server", failure);
            }
        }
    }

    private static Map<String, Object> inputSchema() {
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

    private static final class CrmLookupHandler {

        private final Map<String, String> recordsByCustomerId;
        private int calls;
        private boolean lookupSucceeded;
        private Map<String, Object> receivedArguments = Map.of();

        private CrmLookupHandler(Map<String, String> recordsByCustomerId) {
            this.recordsByCustomerId = Map.copyOf(recordsByCustomerId);
        }

        private McpSchema.CallToolResult lookup(McpSchema.CallToolRequest request) {
            this.calls++;
            this.receivedArguments = Map.copyOf(request.arguments());
            String customerId = receivedArgument("customerId");
            String toolResult = this.recordsByCustomerId.get(customerId);
            this.lookupSucceeded = toolResult != null;
            if (toolResult == null) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("No CRM record exists for the supplied customerId")
                        .isError(true)
                        .build();
            }
            return McpSchema.CallToolResult.builder()
                    .addTextContent(toolResult)
                    .isError(false)
                    .build();
        }

        private String receivedArgument(String name) {
            Object value = this.receivedArguments.get(name);
            if (!(value instanceof String text)) {
                throw new IllegalStateException("MCP argument '%s' must be a string".formatted(name));
            }
            return text;
        }

        private int calls() {
            return this.calls;
        }

        private boolean lookupSucceeded() {
            return this.lookupSucceeded;
        }
    }
}

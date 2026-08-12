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
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class LocalMcpCrmServer implements AutoCloseable {

    private static final String LOOPBACK_ADDRESS = "127.0.0.1";
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
        Connector connector = tomcat.getConnector();
        connector.setProperty("address", LOOPBACK_ADDRESS);
        connector.setAsyncTimeout(3_000);
        Context context = tomcat.addContext("", baseDirectory.toString());
        Wrapper wrapper = context.createWrapper();
        wrapper.setName("mcpServlet");
        wrapper.setServlet(transport);
        wrapper.setLoadOnStartup(1);
        wrapper.setAsyncSupported(true);
        context.addChild(wrapper);
        context.addServletMappingDecoded("/*", "mcpServlet");
        try {
            tomcat.start();
        }
        catch (LifecycleException | RuntimeException | Error failure) {
            IllegalStateException startupFailure = new IllegalStateException(
                    "Failed to start the local MCP demo server",
                    failure
            );
            try {
                mcpServer.closeGracefully();
            }
            catch (RuntimeException | Error cleanupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
            cleanupTomcat(tomcat, startupFailure);
            throw startupFailure;
        }

        return new LocalMcpCrmServer(
                tomcat,
                mcpServer,
                lookupHandler,
                connector.getLocalPort()
        );
    }

    McpSyncClient connect() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://" + LOOPBACK_ADDRESS + ":" + this.port)
                .endpoint(MCP_ENDPOINT)
                .build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }

    void useRecords(Map<String, String> recordsByCustomerId) {
        this.lookupHandler.useRecords(recordsByCustomerId);
    }

    RequestEvidence requestEvidence() {
        return this.lookupHandler.snapshot();
    }

    String receivedArgument(String name) {
        return requestEvidence().receivedArgument(name);
    }

    int calls() {
        return requestEvidence().calls();
    }

    boolean lookupSucceeded() {
        return requestEvidence().lookupSucceeded();
    }

    @Override
    public void close() {
        Throwable cleanupFailure = null;
        try {
            this.mcpServer.closeGracefully();
        }
        catch (RuntimeException | Error failure) {
            cleanupFailure = failure;
        }
        cleanupFailure = cleanupTomcat(this.tomcat, cleanupFailure);
        rethrowFailure(cleanupFailure);
    }

    private static Throwable cleanupTomcat(Tomcat tomcat, Throwable currentFailure) {
        try {
            tomcat.stop();
        }
        catch (LifecycleException | RuntimeException | Error failure) {
            currentFailure = appendFailure(
                    currentFailure,
                    new IllegalStateException("Failed to stop the local MCP demo server", failure)
            );
        }

        try {
            tomcat.destroy();
        }
        catch (LifecycleException | RuntimeException | Error failure) {
            currentFailure = appendFailure(
                    currentFailure,
                    new IllegalStateException("Failed to destroy the local MCP demo server", failure)
            );
        }
        return currentFailure;
    }

    private static Throwable appendFailure(Throwable current, Throwable additional) {
        if (current == null) {
            return additional;
        }
        current.addSuppressed(additional);
        return current;
    }

    private static void rethrowFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
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

    record RequestEvidence(
            int calls,
            boolean lookupSucceeded,
            Map<String, Object> receivedArguments
    ) {

        RequestEvidence {
            receivedArguments = Map.copyOf(receivedArguments);
        }

        String receivedArgument(String name) {
            Object value = this.receivedArguments.get(name);
            if (!(value instanceof String text)) {
                throw new IllegalStateException("MCP argument '%s' must be a string".formatted(name));
            }
            return text;
        }
    }

    private static final class CrmLookupHandler {

        private volatile Map<String, String> recordsByCustomerId;
        private final Object evidenceLock = new Object();
        private int calls;
        private boolean lookupSucceeded;
        private Map<String, Object> receivedArguments = Map.of();

        private CrmLookupHandler(Map<String, String> recordsByCustomerId) {
            this.recordsByCustomerId = Map.copyOf(recordsByCustomerId);
        }

        private void useRecords(Map<String, String> recordsByCustomerId) {
            this.recordsByCustomerId = Map.copyOf(recordsByCustomerId);
        }

        private McpSchema.CallToolResult lookup(McpSchema.CallToolRequest request) {
            Map<String, Object> arguments = Map.copyOf(request.arguments());
            String customerId = requiredArgument(arguments, "customerId");
            String toolResult = this.recordsByCustomerId.get(customerId);
            synchronized (this.evidenceLock) {
                this.calls++;
                this.receivedArguments = arguments;
                this.lookupSucceeded = toolResult != null;
            }
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

        private RequestEvidence snapshot() {
            synchronized (this.evidenceLock) {
                return new RequestEvidence(this.calls, this.lookupSucceeded, this.receivedArguments);
            }
        }

        private static String requiredArgument(Map<String, Object> arguments, String name) {
            Object value = arguments.get(name);
            if (!(value instanceof String text)) {
                throw new IllegalStateException("MCP argument '%s' must be a string".formatted(name));
            }
            return text;
        }
    }
}

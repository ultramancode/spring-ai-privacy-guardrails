package io.github.ultramancode.springai.privacy.sample.mcp.infrastructure;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public final class McpDemoClient implements AutoCloseable {

    private final Environment environment;
    private final McpServerStreamableHttpProperties serverProperties;
    private McpSyncClient client;
    private ToolCallbackProvider toolCallbackProvider;
    private boolean closed;

    public McpDemoClient(Environment environment, McpServerStreamableHttpProperties serverProperties) {
        this.environment = environment;
        this.serverProperties = serverProperties;
    }

    public synchronized McpSyncClient connect() {
        if (this.closed) {
            throw new IllegalStateException("MCP demo client is closed");
        }
        if (this.client != null) {
            return this.client;
        }

        int port = this.environment.getRequiredProperty("local.server.port", Integer.class);
        String baseUrl = "http://127.0.0.1:" + port;
        String contextPath = this.environment.getProperty("server.servlet.context-path", "");
        String endpoint = contextPath + this.serverProperties.getMcpEndpoint();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(endpoint)
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(5))
                .build();
        try {
            client.initialize();
            ToolCallbackProvider toolCallbackProvider = SyncMcpToolCallbackProvider.builder()
                    .mcpClients(client)
                    .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                    .build();
            this.client = client;
            this.toolCallbackProvider = toolCallbackProvider;
            return client;
        }
        catch (RuntimeException | Error failure) {
            try {
                closeClient(client);
            }
            catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public synchronized ToolCallbackProvider getToolCallbackProvider() {
        connect();
        return this.toolCallbackProvider;
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        this.closed = true;
        McpSyncClient client = this.client;
        this.client = null;
        this.toolCallbackProvider = null;
        if (client != null) {
            closeClient(client);
        }
    }

    private static void closeClient(McpSyncClient client) {
        if (!client.closeGracefully()) {
            throw new IllegalStateException("Failed to close the MCP demo client");
        }
    }
}

package io.github.ultramancode.springai.privacy.sample.mcp.infrastructure;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class McpDemoClientTest {

    @Test
    void clientConnectsOnFirstUseAndClosesWithTheApplication() {
        McpSyncClient connection = mock(McpSyncClient.class);
        when(connection.closeGracefully()).thenReturn(true);
        try (MockedConstruction<HttpClientStreamableHttpTransport> transports =
                mockConstruction(HttpClientStreamableHttpTransport.class);
             MockedConstruction<McpClient.SyncSpec> builders = mockConstruction(
                     McpClient.SyncSpec.class,
                     withSettings().defaultAnswer(RETURNS_SELF),
                     (builder, context) -> when(builder.build()).thenReturn(connection)
             )) {
            McpDemoClient client;
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.setEnvironment(new MockEnvironment().withProperty("local.server.port", "12345"));
                context.register(McpDemoClient.class, McpServerStreamableHttpProperties.class);
                context.refresh();
                client = context.getBean(McpDemoClient.class);
                assertThat(transports.constructed()).isEmpty();

                assertThat(client.connect()).isSameAs(connection);
                assertThat(client.connect()).isSameAs(connection);
                assertThat(builders.constructed()).hasSize(1);
            }
            verify(connection).closeGracefully();
            assertThatThrownBy(client::connect).hasMessage("MCP demo client is closed");
        }
    }

    @Test
    void failedInitializationClosesTheClientAndAllowsRetry() {
        MockEnvironment environment = new MockEnvironment().withProperty("local.server.port", "12345");
        McpSyncClient failedClient = mock(McpSyncClient.class);
        McpSyncClient connectedClient = mock(McpSyncClient.class);
        IllegalStateException initializationFailure = new IllegalStateException("initialization failed");
        when(failedClient.initialize()).thenThrow(initializationFailure);
        when(failedClient.closeGracefully()).thenReturn(true);
        when(connectedClient.closeGracefully()).thenReturn(true);

        try (MockedConstruction<HttpClientStreamableHttpTransport> transports =
                mockConstruction(HttpClientStreamableHttpTransport.class);
             MockedConstruction<McpClient.SyncSpec> builders = mockConstruction(
                     McpClient.SyncSpec.class,
                     withSettings().defaultAnswer(RETURNS_SELF),
                     (builder, context) -> {
                         if (context.getCount() == 1) {
                             when(builder.build()).thenReturn(failedClient);
                         }
                         else {
                             when(builder.build()).thenReturn(connectedClient);
                         }
                     }
             ); McpDemoClient client = new McpDemoClient(environment, new McpServerStreamableHttpProperties())) {
            assertThatThrownBy(client::connect).isSameAs(initializationFailure);
            verify(failedClient).closeGracefully();
            assertThat(client.connect()).isSameAs(connectedClient);
            assertThat(client.connect()).isSameAs(connectedClient);
            assertThat(builders.constructed()).hasSize(2);
            assertThat(transports.constructed()).hasSize(2);
        }

        verify(connectedClient).closeGracefully();
    }
}

package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.security.autoconfigure.PrivacySecurityChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosureScope;
import io.modelcontextprotocol.client.McpSyncClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class PrivacyDemoMcpToolLoop implements AutoCloseable {

    private static final PrivacyDemoScenario SCENARIO = PrivacyDemoScenario.DEFAULT;
    private static final List<String> RAW_VALUES = SCENARIO.originalValues();
    private static final List<String> DENIED_TOOL_VALUES = SCENARIO.deniedToolValues();
    private static final List<String> ALLOWED_TOOL_VALUES = SCENARIO.allowedToolValues();
    private static final Pattern EMPLOYEE_ID_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID");
    private static final Pattern EMAIL_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS");
    private static final Pattern PHONE_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("PHONE_NUMBER");
    private static final Pattern CUSTOMER_ID_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("CUSTOMER_ID");
    private final PrivacySecurityChatClientConfigurer securityConfigurer;
    private final PrivacyToolCallbackFactory toolCallbackFactory;
    private final ToolDisclosurePolicy toolDisclosurePolicy;
    private final ToolCallingManager toolCallingManager;
    private final PrivacyDemoSecurityPolicy securityPolicy;
    private final ObjectMapper objectMapper;
    private LocalMcpCrmServer localMcpServer;
    private McpSyncClient mcpClient;
    private ToolCallbackProvider protectedMcpTools;
    private ToolDisclosureScope disclosureScope;
    private Path serverBaseDirectory;

    PrivacyDemoMcpToolLoop(
            PrivacySecurityChatClientConfigurer securityConfigurer,
            PrivacyToolCallbackFactory toolCallbackFactory,
            ToolDisclosurePolicy toolDisclosurePolicy,
            ToolCallingManager toolCallingManager,
            PrivacyDemoSecurityPolicy securityPolicy,
            ObjectMapper objectMapper
    ) {
        this.securityConfigurer = securityConfigurer;
        this.toolCallbackFactory = toolCallbackFactory;
        this.toolDisclosurePolicy = toolDisclosurePolicy;
        this.toolCallingManager = toolCallingManager;
        this.securityPolicy = securityPolicy;
        this.objectMapper = objectMapper;
    }

    synchronized PrivacyDemoToolLoop.Result run(String input, PrivacyDemoLocale locale) {
        LocalMcpCrmServer server = localMcpServer(locale);
        server.useRecords(crmRecords(locale));
        initializeMcpClient(server);
        return this.securityPolicy.runAs(
                PrivacyDemoSecurityPolicy.Role.CUSTOMER_SUPPORT,
                () -> runAgainstLocalServer(input, locale, server)
        ).value();
    }

    @Override
    public synchronized void close() {
        LocalMcpCrmServer server = this.localMcpServer;
        McpSyncClient client = this.mcpClient;
        Path baseDirectory = this.serverBaseDirectory;
        this.localMcpServer = null;
        this.mcpClient = null;
        this.protectedMcpTools = null;
        this.disclosureScope = null;
        this.serverBaseDirectory = null;

        Throwable cleanupFailure = null;
        if (client != null) {
            try {
                if (!client.closeGracefully()) {
                    cleanupFailure = new IllegalStateException("Failed to close the local MCP demo client");
                }
            }
            catch (RuntimeException | Error failure) {
                cleanupFailure = failure;
            }
        }

        if (server != null) {
            try {
                server.close();
            }
            catch (RuntimeException | Error failure) {
                cleanupFailure = appendFailure(cleanupFailure, failure);
            }
        }

        if (baseDirectory != null) {
            try {
                deleteBaseDirectory(baseDirectory);
            }
            catch (RuntimeException | Error failure) {
                cleanupFailure = appendFailure(cleanupFailure, failure);
            }
        }

        rethrowFailure(cleanupFailure);
    }

    private PrivacyDemoToolLoop.Result runAgainstLocalServer(
            String input,
            PrivacyDemoLocale locale,
            LocalMcpCrmServer server
    ) {
        PrivacyDemoToolLoop.DemoToolLoopModel model =
                new PrivacyDemoToolLoop.DemoToolLoopModel(
                        this.objectMapper,
                        "MCP",
                        locale,
                        this.toolCallingManager
                );

        ChatClient.Builder builder = ChatClient.builder(
                model,
                ObservationRegistry.NOOP,
                null,
                null,
                ToolCallingAdvisor.builder().toolCallingManager(this.toolCallingManager)
        ).defaultTools(this.protectedMcpTools);
        this.securityConfigurer.configure(builder);

        int callsBeforeRequest = server.requestEvidence().calls();
        String finalResponse = builder.build().prompt().user(input).call().content();
        LocalMcpCrmServer.RequestEvidence requestEvidence = server.requestEvidence();
        if (requestEvidence.calls() - callsBeforeRequest != 1) {
            throw new IllegalStateException("MCP demo expected exactly one tool call");
        }

        String employeeId = requestEvidence.receivedArgument("employeeId");
        String email = requestEvidence.receivedArgument("email");
        String phone = requestEvidence.receivedArgument("phone");
        String customerId = requestEvidence.receivedArgument("customerId");
        String receivedArguments = String.join("\n", employeeId, email, phone, customerId);
        int deniedRawValueCount = countContainedValues(receivedArguments, DENIED_TOOL_VALUES);
        int allowedRawValueCount = countContainedValues(receivedArguments, ALLOWED_TOOL_VALUES);
        boolean receivedOnlyAllowedOriginals = deniedRawValueCount == 0
                && allowedRawValueCount == ALLOWED_TOOL_VALUES.size()
                && EMPLOYEE_ID_TOKEN_PATTERN.matcher(employeeId).matches()
                && EMAIL_TOKEN_PATTERN.matcher(email).matches()
                && PHONE_TOKEN_PATTERN.matcher(phone).matches()
                && SCENARIO.customerId().equals(customerId)
                && !CUSTOMER_ID_TOKEN_PATTERN.matcher(customerId).find();

        return new PrivacyDemoToolLoop.Result(
                model.calls(),
                !model.rawPiiSeenByModel(),
                model.protectedModelInput(),
                model.issuedToolArguments(),
                this.disclosureScope.entityTypes().stream().sorted().toList(),
                receivedOnlyAllowedOriginals,
                requestEvidence.lookupSucceeded(),
                model.protectedToolResultSeenByModel(),
                new PrivacyDemoToolLoop.BoundaryEvidence(
                        PrivacyDemoToolLoop.EvidenceCount.expectedNone(
                                model.rawValueCount(),
                                RAW_VALUES.size()
                        ),
                        PrivacyDemoToolLoop.EvidenceCount.expectedNone(
                                deniedRawValueCount,
                                DENIED_TOOL_VALUES.size()
                        ),
                        PrivacyDemoToolLoop.EvidenceCount.expectedAll(
                                allowedRawValueCount,
                                ALLOWED_TOOL_VALUES.size()
                        ),
                        PrivacyDemoToolLoop.EvidenceCount.expectedNone(
                                model.rawToolResultValueCountAtModel(),
                                RAW_VALUES.size()
                        )
                ),
                finalResponse
        );
    }

    private void initializeMcpClient(LocalMcpCrmServer server) {
        if (this.mcpClient != null) {
            return;
        }

        McpSyncClient client = server.connect();
        try {
            client.initialize();
            ToolCallbackProvider mcpTools = SyncMcpToolCallbackProvider.builder()
                    .mcpClients(client)
                    .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                    .build();
            ToolDefinition toolDefinition = requireSingleToolDefinition(mcpTools);
            ToolDisclosureScope scope = Objects.requireNonNull(
                    this.toolDisclosurePolicy.scopeFor(toolDefinition),
                    "tool disclosure policy must return a scope"
            );
            ToolCallbackProvider protectedTools = this.toolCallbackFactory.wrapProvider(mcpTools);

            this.mcpClient = client;
            this.protectedMcpTools = protectedTools;
            this.disclosureScope = scope;
        }
        catch (RuntimeException | Error failure) {
            try {
                if (!client.closeGracefully()) {
                    failure.addSuppressed(new IllegalStateException(
                            "Failed to close the local MCP demo client after initialization failure"
                    ));
                }
            }
            catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private LocalMcpCrmServer localMcpServer(PrivacyDemoLocale locale) {
        if (this.localMcpServer != null) {
            return this.localMcpServer;
        }

        Path baseDirectory = createBaseDirectory();
        try {
            this.localMcpServer = LocalMcpCrmServer.start(baseDirectory, crmRecords(locale));
            this.serverBaseDirectory = baseDirectory;
            return this.localMcpServer;
        }
        catch (RuntimeException | Error failure) {
            try {
                deleteBaseDirectory(baseDirectory);
            }
            catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static Map<String, String> crmRecords(PrivacyDemoLocale locale) {
        String record = locale == PrivacyDemoLocale.KO
                ? "CRM 결과: 직원번호는 %s / 이메일은 %s / 전화번호는 %s / 고객번호는 %s"
                : "CRM result: Employee ID is %s / email is %s / phone is %s / customer ID is %s";
        return Map.of(
                SCENARIO.customerId(),
                record.formatted(
                        SCENARIO.employeeId(),
                        SCENARIO.email(),
                        SCENARIO.phone(),
                        SCENARIO.customerId()
                )
        );
    }

    private static ToolDefinition requireSingleToolDefinition(ToolCallbackProvider toolCallbackProvider) {
        ToolCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
        if (toolCallbacks == null || toolCallbacks.length != 1 || toolCallbacks[0] == null) {
            throw new IllegalStateException("MCP demo expected exactly one tool definition");
        }
        return toolCallbacks[0].getToolDefinition();
    }

    private static int countContainedValues(String text, List<String> values) {
        return Math.toIntExact(values.stream().filter(text::contains).count());
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

    private static Path createBaseDirectory() {
        try {
            return Files.createTempDirectory("privacy-mcp-demo-");
        }
        catch (IOException failure) {
            throw new IllegalStateException("Failed to create the local MCP demo directory", failure);
        }
    }

    private static void deleteBaseDirectory(Path baseDirectory) {
        try (Stream<Path> paths = Files.walk(baseDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        catch (IOException failure) {
            throw new IllegalStateException("Failed to remove the local MCP demo directory", failure);
        }
    }
}

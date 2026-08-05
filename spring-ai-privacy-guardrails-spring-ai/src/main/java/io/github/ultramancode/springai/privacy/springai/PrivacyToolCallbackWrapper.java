package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;

/**
 * Protects tool arguments and results according to a capability-scoped disclosure policy.
 * Only callbacks explicitly created by {@link PrivacyToolCallbackFactory} are protected;
 * raw callbacks registered directly with Spring AI are outside this boundary. Definition and
 * metadata accessors are read once when the wrapper is created. Application-owned accessor
 * failures propagate unchanged, while invalid null contracts become safe typed failures.
 */
final class PrivacyToolCallbackWrapper implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolDefinition toolDefinition;
    private final ToolMetadata toolMetadata;
    private final PrivacyService privacyService;
    private final ToolDisclosurePolicy disclosurePolicy;
    private final PrivacyToolCallbackFactory.Provenance factoryProvenance;

    PrivacyToolCallbackWrapper(
            ToolCallback delegate,
            PrivacyService privacyService,
            ToolDisclosurePolicy disclosurePolicy,
            PrivacyToolCallbackFactory.Provenance factoryProvenance
    ) {
        this.delegate = delegate;
        this.privacyService = privacyService;
        this.disclosurePolicy = disclosurePolicy;
        this.factoryProvenance = Objects.requireNonNull(
                factoryProvenance,
                "factoryProvenance must not be null"
        );
        this.toolDefinition = readToolDefinition(delegate);
        this.toolMetadata = readToolMetadata(delegate);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return this.toolDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return this.toolMetadata;
    }

    boolean usesPrivacyService(PrivacyService expected) {
        return this.privacyService == expected;
    }

    boolean hasFactoryProvenance(PrivacyToolCallbackFactory.Provenance expected) {
        return this.factoryProvenance == expected;
    }

    @Override
    public String call(String toolInput) {
        throw new PrivacyGuardrailException(
                PrivacyFailureCode.TOOL_CONTEXT_MISSING,
                PrivacyPhase.TOOL_INPUT,
                "PrivacyToolCallbackWrapper requires an active privacy session ToolContext"
        );
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return callDelegate(toolInput, toolContext);
    }

    private String callDelegate(String toolInput, ToolContext toolContext) {
        Objects.requireNonNull(toolInput, "toolInput must not be null");
        PrivacyContextHandle handle = requireActiveHandle(toolContext);
        ToolDisclosureScope disclosureScope = disclosureScope();
        String cleanInput = transformInput(handle, toolInput, disclosureScope);
        ToolContext delegateContext = withoutInternalPrivacyEntries(toolContext);
        String delegateResult = this.delegate.call(cleanInput, delegateContext);
        if (delegateResult == null) {
            throw new ToolExecutionException(
                    this.toolDefinition,
                    new PrivacyGuardrailException(
                            PrivacyFailureCode.TOOL_EXECUTION_FAILED,
                            PrivacyPhase.TOOL_EXECUTION,
                            "Tool execution failed"
                    )
            );
        }
        return tokenizeResult(handle, delegateResult);
    }

    private ToolDisclosureScope disclosureScope() {
        ToolDisclosureScope scope = this.disclosurePolicy.scopeFor(this.toolDefinition);
        if (scope == null) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.TOOL_POLICY_FAILED,
                    PrivacyPhase.TOOL_INPUT,
                    "Tool disclosure policy returned no disclosure"
            );
        }
        return scope;
    }

    private String transformInput(
            PrivacyContextHandle handle,
            String toolInput,
            ToolDisclosureScope disclosureScope
    ) {
        return PrivacyJsonPayloadTransformer.disclose(
                this.privacyService,
                handle,
                toolInput,
                disclosureScope.entityTypes(),
                PrivacyPhase.TOOL_INPUT,
                true
        );
    }

    private String tokenizeResult(PrivacyContextHandle handle, String toolResult) {
        return PrivacyJsonPayloadTransformer.tokenize(
                this.privacyService,
                handle,
                toolResult,
                PrivacyPhase.TOOL_OUTPUT,
                false
        );
    }

    private PrivacyContextHandle requireActiveHandle(ToolContext toolContext) {
        if (toolContext == null) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.TOOL_CONTEXT_MISSING,
                    PrivacyPhase.TOOL_INPUT,
                    "PrivacyToolCallbackWrapper requires an active privacy session ToolContext"
            );
        }
        PrivacyContextHandle handle = PrivacyRequestContextSupport.findHandle(toolContext.getContext())
                .orElseThrow(() -> new PrivacyGuardrailException(
                        PrivacyFailureCode.TOOL_CONTEXT_MISSING,
                        PrivacyPhase.TOOL_INPUT,
                        "PrivacyToolCallbackWrapper requires an active privacy session ToolContext"
                ));
        if (!this.privacyService.isSessionActive(handle)) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.CONTEXT_NOT_ACTIVE,
                    PrivacyPhase.SESSION,
                    "Privacy context is unknown or already closed"
            );
        }
        return handle;
    }

    private ToolContext withoutInternalPrivacyEntries(ToolContext toolContext) {
        return new ToolContext(PrivacyRequestContextSupport.stripInternalPrivacyEntries(toolContext.getContext()));
    }

    private static ToolDefinition readToolDefinition(ToolCallback delegate) {
        ToolDefinition definition = delegate.getToolDefinition();
        if (definition == null) {
            throw toolContractViolation(
                    PrivacyFailureCode.TOOL_DEFINITION_UNAVAILABLE,
                    "Tool callback returned no definition"
            );
        }
        String name = definition.name();
        String description = definition.description();
        String inputSchema = definition.inputSchema();
        if (name == null || description == null || inputSchema == null) {
            throw toolContractViolation(
                    PrivacyFailureCode.TOOL_DEFINITION_UNAVAILABLE,
                    "Tool callback returned an incomplete definition"
            );
        }
        return new DefaultToolDefinition(name, description, inputSchema);
    }

    private static ToolMetadata readToolMetadata(ToolCallback delegate) {
        ToolMetadata metadata = delegate.getToolMetadata();
        if (metadata == null) {
            throw toolContractViolation(
                    PrivacyFailureCode.TOOL_METADATA_UNAVAILABLE,
                    "Tool callback returned no metadata"
            );
        }
        return ToolMetadata.builder()
                .returnDirect(metadata.returnDirect())
                .build();
    }

    private static PrivacyGuardrailException toolContractViolation(
            PrivacyFailureCode code,
            String safeMessage
    ) {
        return new PrivacyGuardrailException(code, PrivacyPhase.TOOL_INPUT, safeMessage);
    }
}

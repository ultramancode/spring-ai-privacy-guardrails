package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyToolCallbackContractTest {
    @Test
    void factoryRejectsAlreadyWrappedCallbacksInsteadOfSilentlyKeepingAnotherPolicy() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(service, ToolDisclosurePolicy.denyAll());
        ToolCallback wrapped = factory.wrap(delegate(input -> "ok"));

        assertThatThrownBy(() -> factory.wrap(wrapped))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolCallback must not already be privacy wrapped");
    }

    @Test
    void delegateToolReceivesApplicationContextWithoutInternalPrivacyId() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<ToolContext> receivedContext = new AtomicReference<>();
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("lookup")
                        .description("lookup")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                receivedContext.set(toolContext);
                return "ok";
            }
        };

        try (PrivacySession session = service.openSession()) {
            ToolContext context = new ToolContext(Map.of(
                    PrivacyRequestContextSupport.CONTEXT_HANDLE, session.handle(),
                    "tenant", "acme"
            ));

            wrap(delegate, service).call("{}", context);

            assertThat(receivedContext.get().getContext())
                    .containsOnly(Map.entry("tenant", "acme"));
        }
    }

    @Test
    void closedContextIsRejectedBeforeToolSideEffectsRun() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyContextHandle handle;
        try (PrivacySession session = service.openSession()) {
            handle = session.handle();
        }
        AtomicReference<String> received = new AtomicReference<>();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    received.set(input);
                    return "ok";
                }),
                service,
                ToolDisclosurePolicy.byToolName(Map.of("lookup", Set.of("PERSON")))
        );

        ToolContext context = toolContext(handle);
        assertThatThrownBy(() -> wrapper.call("lookup token", context))
                .hasMessageContaining("closed");
        assertThat(received.get()).isNull();
    }

    @Test
    void scopedToolPropagatesDelegateFailureUnchanged() {
        PrivacyService service = TestPrivacyServices.privacyService();
        IllegalStateException delegateFailure =
                new IllegalStateException("Host-owned retryable tool failure");
        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), "Alice");
            PrivacyToolCallbackWrapper wrapper = wrap(
                    delegate(input -> {
                        assertThat(input).contains("Alice");
                        throw delegateFailure;
                    }),
                    service,
                    ToolDisclosurePolicy.byToolName(Map.of("lookup", Set.of("PERSON")))
            );

            assertThatThrownBy(() -> wrapper.call(
                    "{\"query\":\"lookup " + token + "\"}",
                    toolContext(session.handle())
            ))
                    .isSameAs(delegateFailure);
        }
    }

    @Test
    void scopedToolRethrowsFatalJvmErrorUnchanged() {
        PrivacyService service = TestPrivacyServices.privacyService();
        OutOfMemoryError fatal = new OutOfMemoryError("fatal Alice");
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    throw fatal;
                }),
                service,
                ToolDisclosurePolicy.byToolName(Map.of("lookup", Set.of("PERSON")))
        );

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> wrapper.call("{}", toolContext(session.handle())))
                    .isSameAs(fatal);
        }
    }

    @Test
    void returnDirectResultIsAlwaysTokenizedByTheToolBoundary() {
        PrivacyService service = TestPrivacyServices.privacyService();
        ToolCallback direct = returnDirectDelegate("Alice direct result");

        try (PrivacySession session = service.openSession()) {
            String result = wrap(direct, service).call("{}", toolContext(session.handle()));

            assertThat(result)
                    .containsPattern(io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat
                            .patternForEntityType("PERSON"))
                    .doesNotContain("Alice");
        }
    }

    @Test
    void scopedPolicyRejectsAmbiguousOrEmptyConfiguration() {
        assertThatThrownBy(() -> ToolDisclosurePolicy.byToolName(Map.of("", List.of("PERSON"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> ToolDisclosurePolicy.byToolName(Map.of("look up", List.of("PERSON"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");
        assertThatThrownBy(() -> ToolDisclosurePolicy.byToolName(Map.of(" lookup", List.of("PERSON"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");
        assertThatThrownBy(() -> ToolDisclosurePolicy.byToolName(Map.of("lookup", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> ToolDisclosurePolicy.byToolName(Map.of(
                "lookup",
                List.of("person")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
        assertThatThrownBy(() -> ToolDisclosurePolicy.byToolName(Map.of(
                "lookup",
                List.of("PERSON", "PERSON")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    void scopedPolicyMatchesToolNamesExactlyAndCaseSensitively() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> received = new AtomicReference<>();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    received.set(input);
                    return "ok";
                }),
                service,
                ToolDisclosurePolicy.byToolName(Map.of("LOOKUP", List.of("PERSON")))
        );

        try (PrivacySession session = service.openSession()) {
            wrapper.call("{\"name\":\"Alice\"}", toolContext(session.handle()));

            assertThat(received.get()).doesNotContain("Alice");
            assertThat(service.detokenize(session.handle(), received.get()))
                    .isEqualTo("{\"name\":\"Alice\"}");
        }
    }

    @Test
    void factoryWrapPropagatesDefinitionLookupFailureUnchanged() {
        PrivacyService service = TestPrivacyServices.privacyService();
        IllegalStateException lookupFailure = new IllegalStateException("Alice is in the tool definition");
        lookupFailure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("Alice.Tool", "lookupBob", "Alice.java", 42)
        });
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                throw lookupFailure;
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };

        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(service, ToolDisclosurePolicy.denyAll());

        assertThatThrownBy(() -> factory.wrap(delegate))
                .isSameAs(lookupFailure);
    }

    @Test
    void factoryWrapSnapshotsDefinitionAndMetadataAccessorsOnce() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicInteger definitionReads = new AtomicInteger();
        AtomicInteger metadataReads = new AtomicInteger();
        AtomicInteger nameReads = new AtomicInteger();
        AtomicInteger descriptionReads = new AtomicInteger();
        AtomicInteger schemaReads = new AtomicInteger();
        AtomicInteger returnDirectReads = new AtomicInteger();
        ToolDefinition statefulDefinition = new ToolDefinition() {
            @Override
            public String name() {
                return readOnce(nameReads, "lookup");
            }

            @Override
            public String description() {
                return readOnce(descriptionReads, "lookup");
            }

            @Override
            public String inputSchema() {
                return readOnce(schemaReads, "{}");
            }
        };
        ToolMetadata statefulMetadata = new ToolMetadata() {
            @Override
            public boolean returnDirect() {
                if (returnDirectReads.incrementAndGet() > 1) {
                    throw new IllegalStateException("Alice leaked from a repeated metadata lookup");
                }
                return true;
            }
        };
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                if (definitionReads.incrementAndGet() > 1) {
                    throw new IllegalStateException("Alice leaked from a repeated definition lookup");
                }
                return statefulDefinition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                if (metadataReads.incrementAndGet() > 1) {
                    throw new IllegalStateException("Alice leaked from a repeated metadata lookup");
                }
                return statefulMetadata;
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };

        ToolCallback wrapper = new PrivacyToolCallbackFactory(service, ToolDisclosurePolicy.denyAll())
                .wrap(delegate);

        assertThat(wrapper.getToolDefinition().name()).isEqualTo("lookup");
        assertThat(wrapper.getToolDefinition().description()).isEqualTo("lookup");
        assertThat(wrapper.getToolDefinition().inputSchema()).isEqualTo("{}");
        assertThat(wrapper.getToolMetadata().returnDirect()).isTrue();
        try (PrivacySession session = service.openSession()) {
            assertThat(wrapper.call("{}", toolContext(session.handle()))).isEqualTo("ok");
        }
        assertThat(definitionReads).hasValue(1);
        assertThat(metadataReads).hasValue(1);
        assertThat(nameReads).hasValue(1);
        assertThat(descriptionReads).hasValue(1);
        assertThat(schemaReads).hasValue(1);
        assertThat(returnDirectReads).hasValue(1);
    }

    @Test
    void factoryWrapPropagatesDefinitionAndMetadataAccessorFailuresBeforeRegistration() {
        PrivacyService service = TestPrivacyServices.privacyService();
        IllegalStateException definitionAccessorFailure =
                new IllegalStateException("Alice leaked from the tool name");
        IllegalStateException metadataAccessorFailure =
                new IllegalStateException("Bob leaked from returnDirect metadata");
        ToolCallback definitionFailure = callbackWithDefinition(new ToolDefinition() {
            @Override
            public String name() {
                throw definitionAccessorFailure;
            }

            @Override
            public String description() {
                return "lookup";
            }

            @Override
            public String inputSchema() {
                return "{}";
            }
        }, ToolMetadata.builder().build());
        ToolCallback metadataFailure = callbackWithDefinition(
                ToolDefinition.builder().name("lookup").description("lookup").inputSchema("{}").build(),
                new ToolMetadata() {
                    @Override
                    public boolean returnDirect() {
                        throw metadataAccessorFailure;
                    }
                }
        );
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );

        assertThatThrownBy(() -> factory.wrap(definitionFailure))
                .isSameAs(definitionAccessorFailure);
        assertThatThrownBy(() -> factory.wrap(metadataFailure))
                .isSameAs(metadataAccessorFailure);
    }

    @Test
    void factoryWrapRejectsMissingDefinitionAndMetadataWithSafeTypedFailures() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        ToolCallback missingDefinition = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return null;
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
        ToolCallback missingMetadata = callbackWithDefinition(
                ToolDefinition.builder().name("lookup").description("lookup").inputSchema("{}").build(),
                null
        );

        assertThatThrownBy(() -> factory.wrap(missingDefinition))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TOOL_DEFINITION_UNAVAILABLE);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOOL_INPUT);
                    assertThat(failure).hasMessage("Tool callback returned no definition");
                });
        assertThatThrownBy(() -> factory.wrap(missingMetadata))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TOOL_METADATA_UNAVAILABLE);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOOL_INPUT);
                    assertThat(failure).hasMessage("Tool callback returned no metadata");
                });
    }

    @Test
    void factoryRejectsMissingConfiguration() {
        PrivacyService service = TestPrivacyServices.privacyService();

        assertThatThrownBy(() -> new PrivacyToolCallbackFactory(null, ToolDisclosurePolicy.denyAll()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("privacyService must not be null");
        assertThatThrownBy(() -> new PrivacyToolCallbackFactory(service, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("disclosurePolicy must not be null");
    }

    @Test
    void factoryRejectsNullCallback() {
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                TestPrivacyServices.privacyService(),
                ToolDisclosurePolicy.denyAll()
        );

        assertThatThrownBy(() -> factory.wrap(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("toolCallback must not be null");
    }

    @Test
    void wrapperPropagatesPolicyFailureBeforeAnyDetokenization() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> received = new AtomicReference<>();
        IllegalStateException policyFailure =
                new IllegalStateException("Alice leaked from policy evaluation");
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    received.set(input);
                    return "ok";
                }),
                service,
                definition -> {
                    throw policyFailure;
                }
        );

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> wrapper.call("Alice", toolContext(session.handle())))
                    .isSameAs(policyFailure);
        }
        assertThat(received.get()).isNull();
    }

    @Test
    void wrapperRejectsMissingPolicyResultBeforeAnyDetokenization() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> received = new AtomicReference<>();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    received.set(input);
                    return "ok";
                }),
                service,
                definition -> null
        );

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> wrapper.call("Alice", toolContext(session.handle())))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TOOL_POLICY_FAILED);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOOL_INPUT);
                        assertThat(failure).hasMessage("Tool disclosure policy returned no disclosure");
                    });
        }
        assertThat(received.get()).isNull();
    }

    private ToolContext toolContext(PrivacyContextHandle handle) {
        return new ToolContext(Map.of(PrivacyRequestContextSupport.CONTEXT_HANDLE, handle));
    }

    private PrivacyToolCallbackWrapper wrap(ToolCallback delegate, PrivacyService service) {
        return wrap(delegate, service, ToolDisclosurePolicy.denyAll());
    }

    private PrivacyToolCallbackWrapper wrap(
            ToolCallback delegate,
            PrivacyService service,
            ToolDisclosurePolicy policy
    ) {
        return (PrivacyToolCallbackWrapper) new PrivacyToolCallbackFactory(service, policy).wrap(delegate);
    }

    private ToolCallback delegate(Callback callback) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("lookup")
                        .description("lookup")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return callback.call(toolInput);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return callback.call(toolInput);
            }
        };
    }

    private ToolCallback returnDirectDelegate(String result) {
        return returnDirectDelegate(() -> result);
    }

    private ToolCallback returnDirectDelegate(Supplier<String> result) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("lookup")
                        .description("lookup")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(true).build();
            }

            @Override
            public String call(String toolInput) {
                return result.get();
            }
        };
    }

    private ToolCallback callbackWithDefinition(ToolDefinition definition, ToolMetadata metadata) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return metadata;
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }

    private String readOnce(AtomicInteger reads, String value) {
        if (reads.incrementAndGet() > 1) {
            throw new IllegalStateException("Alice leaked from a repeated definition accessor");
        }
        return value;
    }

    @FunctionalInterface
    private interface Callback {
        String call(String input);
    }
}

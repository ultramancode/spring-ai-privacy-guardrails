package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyToolCallbackFactoryTest {

    @Test
    void isWrappedRecognizesOnlyCallbacksCreatedByTheExactFactory() {
        PrivacyToolCallbackFactory factory = factory();
        PrivacyToolCallbackFactory anotherFactory = factory();
        ToolCallback raw = tool("raw");
        ToolCallback wrapped = factory.wrap(raw);

        assertThat(factory.isWrapped(wrapped)).isTrue();
        assertThat(factory.isWrapped(raw)).isFalse();
        assertThat(factory.isWrapped(null)).isFalse();
        assertThat(anotherFactory.isWrapped(wrapped)).isFalse();
    }

    @Test
    void wrapAllCollectionPreservesIterationOrderAndReturnsImmutableList() {
        PrivacyToolCallbackFactory factory = factory();
        ToolCallback first = tool("first");
        ToolCallback second = tool("second");

        List<ToolCallback> wrapped = factory.wrapAll(List.of(first, second));

        assertThat(wrapped)
                .allMatch(PrivacyToolCallbackWrapper.class::isInstance)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("first", "second");
        assertThatThrownBy(() -> wrapped.add(tool("third")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void wrapAllVarargsPreservesArgumentOrder() {
        PrivacyToolCallbackFactory factory = factory();

        List<ToolCallback> wrapped = factory.wrapAll(tool("first"), tool("second"));

        assertThat(wrapped)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("first", "second");
    }

    @Test
    void wrapAllRejectsDuplicateToolNames() {
        PrivacyToolCallbackFactory factory = factory();

        assertThatThrownBy(() -> factory.wrapAll(tool("duplicate"), tool("duplicate")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolCallbacks must have distinct tool names");
    }

    @Test
    void wrapAllRejectsAlreadyWrappedBatchBeforeCreatingAnyWrapper() {
        PrivacyToolCallbackFactory factory = factory();
        AtomicInteger firstDefinitionReads = new AtomicInteger();
        ToolCallback first = tool("first", firstDefinitionReads);
        ToolCallback alreadyWrapped = factory.wrap(tool("wrapped"));

        assertThatThrownBy(() -> factory.wrapAll(List.of(first, alreadyWrapped)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolCallback must not already be privacy wrapped");
        assertThat(firstDefinitionReads).hasValue(0);
    }

    @Test
    void wrapAllRejectsNullElementBeforeCreatingAnyWrapper() {
        PrivacyToolCallbackFactory factory = factory();
        AtomicInteger firstDefinitionReads = new AtomicInteger();
        ToolCallback first = tool("first", firstDefinitionReads);

        assertThatThrownBy(() -> factory.wrapAll(Arrays.asList(first, null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("toolCallback must not be null");
        assertThat(firstDefinitionReads).hasValue(0);
    }

    @Test
    void wrapAllRejectsNullContainers() {
        PrivacyToolCallbackFactory factory = factory();

        assertThatThrownBy(() -> factory.wrapAll((Collection<ToolCallback>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("toolCallbacks must not be null");
        assertThatThrownBy(() -> factory.wrapAll((ToolCallback[]) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("toolCallbacks must not be null");
    }

    @Test
    void wrapProviderResolvesAndWrapsTheCurrentCallbackSnapshotOnEveryAccess() {
        PrivacyToolCallbackFactory factory = factory();
        AtomicReference<ToolCallback[]> current = new AtomicReference<>(new ToolCallback[]{
                tool("customerLookup"),
                tool("knowledgeSearch")
        });
        ToolCallbackProvider protectedProvider = factory.wrapProvider(current::get);

        ToolCallback[] first = protectedProvider.getToolCallbacks();
        current.set(new ToolCallback[]{tool("ticketLookup")});
        ToolCallback[] second = protectedProvider.getToolCallbacks();

        assertThat(first)
                .allMatch(PrivacyToolCallbackWrapper.class::isInstance)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("customerLookup", "knowledgeSearch");
        assertThat(second)
                .allMatch(PrivacyToolCallbackWrapper.class::isInstance)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("ticketLookup");
    }

    @Test
    void wrapProvidersCombinesCurrentSourcesInOrderAndRejectsCrossProviderDuplicates() {
        PrivacyToolCallbackFactory factory = factory();
        AtomicInteger firstResolutions = new AtomicInteger();
        AtomicInteger secondResolutions = new AtomicInteger();
        ToolCallbackProvider first = () -> {
            firstResolutions.incrementAndGet();
            return new ToolCallback[]{tool("customerLookup"), tool("knowledgeSearch")};
        };
        ToolCallbackProvider second = () -> {
            secondResolutions.incrementAndGet();
            return new ToolCallback[]{tool("ticketLookup")};
        };
        ToolCallbackProvider combined = factory.wrapProviders(first, second);

        assertThat(combined.getToolCallbacks())
                .allMatch(PrivacyToolCallbackWrapper.class::isInstance)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("customerLookup", "knowledgeSearch", "ticketLookup");
        assertThat(firstResolutions).hasValue(1);
        assertThat(secondResolutions).hasValue(1);

        ToolCallbackProvider duplicate = factory.wrapProviders(
                () -> new ToolCallback[]{tool("AliceSensitiveTool")},
                () -> new ToolCallback[]{tool("AliceSensitiveTool")}
        );
        assertThatThrownBy(duplicate::getToolCallbacks)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolCallbacks must have distinct tool names")
                .hasMessageNotContaining("AliceSensitiveTool");
    }

    @Test
    void wrapProvidersRejectsMissingOrAlreadyWrappedSourcesBeforeResolution() {
        PrivacyToolCallbackFactory factory = factory();
        AtomicInteger resolutions = new AtomicInteger();
        ToolCallbackProvider source = () -> {
            resolutions.incrementAndGet();
            return new ToolCallback[]{tool("lookup")};
        };
        ToolCallbackProvider protectedProvider = factory.wrapProvider(source);

        assertThatThrownBy(() -> factory.wrapProviders())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolCallbackProviders must not be empty");
        assertThatThrownBy(() -> factory.wrapProviders((ToolCallbackProvider[]) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("toolCallbackProviders must not be null");
        assertThatThrownBy(() -> factory.wrapProviders(source, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("toolCallbackProvider must not be null");
        assertThatThrownBy(() -> factory.wrapProviders(source, protectedProvider))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolCallbackProvider must not already be privacy wrapped");
        assertThat(resolutions).hasValue(0);
    }

    @Test
    void wrapProviderRejectsNullAlreadyWrappedAndDuplicateProviderResults() {
        PrivacyToolCallbackFactory factory = factory();
        ToolCallbackProvider protectedProvider = factory.wrapProvider(
                () -> new ToolCallback[]{tool("duplicate"), tool("duplicate")}
        );

        assertThatThrownBy(() -> factory.wrapProvider(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("toolCallbackProvider must not be null");
        assertThatThrownBy(() -> factory.wrapProvider(protectedProvider))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolCallbackProvider must not already be privacy wrapped");
        assertThatThrownBy(protectedProvider::getToolCallbacks)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolCallbacks must have distinct tool names");
    }

    @Test
    void wrapProviderPropagatesProviderFailureAndRejectsInvalidSnapshots() {
        PrivacyToolCallbackFactory factory = factory();
        IllegalStateException providerFailure = new IllegalStateException("provider exposed Alice");
        providerFailure.addSuppressed(new IllegalArgumentException("Alice suppressed"));
        ToolCallbackProvider failing = factory.wrapProvider(() -> {
            throw providerFailure;
        });
        ToolCallbackProvider nullReturning = factory.wrapProvider(() -> null);
        ToolCallbackProvider nullElementReturning = factory.wrapProvider(
                () -> new ToolCallback[]{null}
        );

        assertThatThrownBy(failing::getToolCallbacks)
                .isSameAs(providerFailure);
        assertThatThrownBy(nullReturning::getToolCallbacks)
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TOOL_PROVIDER_UNAVAILABLE);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOOL_INPUT);
                    assertThat(failure).hasMessage("Tool callback provider returned an invalid callback snapshot");
                });
        assertThatThrownBy(nullElementReturning::getToolCallbacks)
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TOOL_PROVIDER_UNAVAILABLE);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOOL_INPUT);
                    assertThat(failure).hasMessage("Tool callback provider returned an invalid callback snapshot");
                });
    }

    private PrivacyToolCallbackFactory factory() {
        return new PrivacyToolCallbackFactory(
                TestPrivacyServices.privacyService(),
                ToolDisclosurePolicy.denyAll()
        );
    }

    private ToolCallback tool(String name) {
        return tool(name, new AtomicInteger());
    }

    private ToolCallback tool(String name, AtomicInteger definitionReads) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                definitionReads.incrementAndGet();
                return ToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }
}

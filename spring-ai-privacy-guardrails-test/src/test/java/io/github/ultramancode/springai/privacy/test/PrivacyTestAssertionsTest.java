package io.github.ultramancode.springai.privacy.test;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.RegexPiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.RegexPiiRule;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Arrays;
import java.util.List;

import static io.github.ultramancode.springai.privacy.test.PrivacyTestAssertions.assertThatToolsArePrivacyWrapped;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyTestAssertionsTest {

    @Test
    void privacyWrappedAssertionAcceptsPlannedCollectionAndArray() {
        PrivacyToolCallbackFactory toolCallbackFactory = factory();
        ToolCallback wrapped = toolCallbackFactory.wrap(tool("wrapped"));

        assertThatCode(() -> assertThatToolsArePrivacyWrapped(toolCallbackFactory, List.of(wrapped)))
                .doesNotThrowAnyException();
        assertThatCode(() -> assertThatToolsArePrivacyWrapped(
                toolCallbackFactory,
                new ToolCallback[]{wrapped}
        ))
                .doesNotThrowAnyException();
    }

    @Test
    void privacyWrappedAssertionRejectsRawCallbackWithoutRenderingIt() {
        PrivacyToolCallbackFactory toolCallbackFactory = factory();
        ToolCallback wrapped = toolCallbackFactory.wrap(tool("wrapped"));
        ToolCallback raw = sensitiveToStringTool("raw");

        assertThatThrownBy(() -> assertThatToolsArePrivacyWrapped(
                toolCallbackFactory,
                List.of(wrapped, raw)
        ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("index <1>", raw.getClass().getName())
                .hasMessageNotContaining("synthetic-secret");
    }

    @Test
    void privacyWrappedAssertionReportsNullArrayElementByIndex() {
        PrivacyToolCallbackFactory toolCallbackFactory = factory();
        ToolCallback wrapped = toolCallbackFactory.wrap(tool("wrapped"));

        assertThatThrownBy(() -> assertThatToolsArePrivacyWrapped(
                toolCallbackFactory,
                Arrays.asList(wrapped, null)
        ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("index <1>", "<null>");
    }

    private PrivacyToolCallbackFactory factory() {
        PrivacyService privacyService = new PrivacyService(
                List.of(new RegexPiiAnalyzer(List.of(
                        new RegexPiiRule("PERSON", "\\bAlice\\b", 0.99, 0)
                ))),
                PiiAnalysisOptions.defaults()
        );
        return new PrivacyToolCallbackFactory(privacyService, ToolDisclosurePolicy.denyAll());
    }

    private ToolCallback sensitiveToStringTool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition(name);
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }

            @Override
            public String toString() {
                return "synthetic-secret";
            }
        };
    }

    private ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition(name);
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }

    private ToolDefinition definition(String name) {
        return ToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{}")
                .build();
    }
}

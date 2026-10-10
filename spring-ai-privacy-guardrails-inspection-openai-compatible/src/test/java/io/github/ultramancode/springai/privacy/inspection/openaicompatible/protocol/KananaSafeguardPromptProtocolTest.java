package io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.ObjectMappers;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KananaSafeguardPromptProtocolTest {

    private final KananaSafeguardPromptProtocol protocol = new KananaSafeguardPromptProtocol();

    @Test
    void buildsOneUserMessageWithDefaultGenerationOptions() {
        ChatCompletionCreateParams request = protocol.request("test-model", "customer [PII:1]");
        JsonNode body = ObjectMappers.jsonMapper().valueToTree(request._body());

        assertThat(body.path("model").asText()).isEqualTo("test-model");
        assertThat(body.path("messages").size()).isEqualTo(1);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("customer [PII:1]");
        assertThat(body.path("n").asInt()).isEqualTo(1);
        assertThat(body.path("max_completion_tokens").asLong()).isEqualTo(1L);
        assertThat(body.path("temperature").isNumber()).isTrue();
        assertThat(body.path("temperature").asDouble()).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(doubles = 0.5)
    void appliesConfiguredGenerationOptions(Double temperature) {
        GuardModelGenerationOptions options = new GuardModelGenerationOptions(128L, temperature);
        KananaSafeguardPromptProtocol configuredProtocol = new KananaSafeguardPromptProtocol(options);
        JsonNode body = ObjectMappers.jsonMapper().valueToTree(
                configuredProtocol.request("test-model", "customer [PII:1]")._body());

        assertThat(body.path("max_completion_tokens").asLong()).isEqualTo(128L);
        if (temperature == null) {
            assertThat(body.has("temperature")).isFalse();
        } else {
            assertThat(body.path("temperature").asDouble()).isEqualTo(temperature);
        }
    }

    @Test
    void safeVerdictHasNoFinding() {
        assertThat(protocol.parse("s1", "<SAFE>")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "<UNSAFE-A1>, PROMPT_INJECTION, UNSAFE-A1",
            "<UNSAFE-A2>, PROMPT_LEAKING, UNSAFE-A2"
    })
    void unsafeVerdictsMapToTheirCategories(String output, InspectionFinding.Category category, String code) {
        assertThat(protocol.parse("s1", output)).contains(new InspectionFinding("s1", category, code, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<SA", "I think this is <SAFE>", "<SAFE><UNSAFE-A1>"})
    void rejectsIncompleteAndUnknownVerdicts(String output) {
        assertThatThrownBy(() -> protocol.parse("s1", output))
                .isInstanceOfSatisfying(InspectionException.class, failure ->
                        assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESPONSE));
    }
}

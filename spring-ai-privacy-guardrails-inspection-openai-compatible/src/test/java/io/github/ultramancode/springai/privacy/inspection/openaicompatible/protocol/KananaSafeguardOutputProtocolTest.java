package io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.ObjectMappers;
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

class KananaSafeguardOutputProtocolTest {

    private final KananaSafeguardOutputProtocol protocol = new KananaSafeguardOutputProtocol();

    @Test
    void sendsQuestionAndCompleteJsonAnswerInOneConversation() {
        String question = "배송 상태를 알려주세요.";
        String answer = "{\"status\":\"배송 중\",\"detail\":\"내일 도착\"}";
        JsonNode body = ObjectMappers.jsonMapper().valueToTree(protocol.request("test-model", answer, question)._body());

        assertThat(body.path("messages").size()).isEqualTo(2);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo(question);
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo(answer);
    }

    @Test
    void sendsInspectedTextAsAssistantWithEmptyUserAndDefaultGenerationOptions() {
        String text = "고객 [REDACTED_PERSON]님의 배송이 진행 중입니다.\n";
        JsonNode body = ObjectMappers.jsonMapper().valueToTree(protocol.request("test-model", text)._body());

        assertThat(body.path("model").asText()).isEqualTo("test-model");
        assertThat(body.path("messages").size()).isEqualTo(2);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(0).path("content").asText()).isEmpty();
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo(text);
        assertThat(body.path("n").asInt()).isEqualTo(1);
        assertThat(body.path("max_completion_tokens").asLong()).isEqualTo(1L);
        assertThat(body.path("temperature").isNumber()).isTrue();
        assertThat(body.path("temperature").asDouble()).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(doubles = 0.5)
    void appliesConfiguredGenerationOptions(Double temperature) {
        var configuredProtocol = new KananaSafeguardOutputProtocol(new GuardModelGenerationOptions(128L, temperature));
        JsonNode body = ObjectMappers.jsonMapper().valueToTree(configuredProtocol.request("test-model", "answer")._body());

        assertThat(body.path("max_completion_tokens").asLong()).isEqualTo(128L);
        if (temperature == null) {
            assertThat(body.has("temperature")).isFalse();
        } else {
            assertThat(body.path("temperature").asDouble()).isEqualTo(temperature);
        }
    }

    @Test
    void rejectsNullGenerationOptions() {
        assertThatThrownBy(() -> new KananaSafeguardOutputProtocol(null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<SAFE>", " \n<SAFE>\t"})
    void safeVerdictHasNoFinding(String output) {
        assertThat(protocol.parse("answer", output)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "<UNSAFE-S1>, HATE_SPEECH, UNSAFE-S1",
            "<UNSAFE-S2>, HARASSMENT, UNSAFE-S2",
            "<UNSAFE-S3>, SEXUAL_CONTENT, UNSAFE-S3",
            "<UNSAFE-S4>, CRIMINAL_ACTIVITY, UNSAFE-S4",
            "<UNSAFE-S5>, CHILD_SEXUAL_EXPLOITATION, UNSAFE-S5",
            "<UNSAFE-S6>, SELF_HARM, UNSAFE-S6",
            "<UNSAFE-S7>, MISINFORMATION, UNSAFE-S7"
    })
    void unsafeVerdictsMapToContentCategoriesAndPreserveCode(
            String output, InspectionFinding.Category category, String code) {
        InspectionFinding expected = new InspectionFinding("answer", category, code, null);
        assertThat(protocol.parse("answer", output)).contains(expected);
        assertThat(protocol.parse("answer", " \n" + output + "\t")).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "<SA", "<UNSAFE-S", "<UNSAFE-S1", "<UNSAFE-S0>", "<UNSAFE-S8>", "<UNSAFE-S01>",
            "<UNSAFE-A1>", "<UNSAFE-A2>", "<unsafe-s1>", "SAFE", "I think this is <SAFE>",
            "<SAFE> explanation", "<SAFE><UNSAFE-S1>", "<UNSAFE-S1>\n<UNSAFE-S2>", "```<SAFE>```"
    })
    void rejectsIncompleteUnknownAndMixedVerdicts(String output) {
        assertThatThrownBy(() -> protocol.parse("answer", output))
                .isInstanceOfSatisfying(InspectionException.class, failure ->
                        assertThat(failure.code()).isEqualTo(InspectionFailureCode.INVALID_RESPONSE));
    }
}

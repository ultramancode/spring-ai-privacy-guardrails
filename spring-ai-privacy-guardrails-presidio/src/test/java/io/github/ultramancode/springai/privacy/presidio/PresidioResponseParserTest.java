package io.github.ultramancode.springai.privacy.presidio;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PresidioResponseParserTest {

    @Test
    void oversizedNumericOffsetsAndScoresAreRejectedWithoutExposingTheirValues() {
        String hugeNumber = "9".repeat(10_000);
        for (String field : List.of("start", "end", "score")) {
            String response = "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":1,\"score\":1}]";
            String originalValue = field.equals("start") ? "0" : "1";
            response = response.replace("\"" + field + "\":" + originalValue,
                    "\"" + field + "\":" + hugeNumber);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            PresidioResponseParser parser = new PresidioResponseParser(body.length, 64);

            assertThatThrownBy(() -> parser.parse(body, "A", 1))
                    .hasMessageContaining("expected contract")
                    .hasMessageNotContaining(hugeNumber)
                    .hasNoCause();
        }
    }

    @Test
    void segmentedResponsesShareTheConfiguredSpanLimit() {
        String span = "{\"entity_type\":\"PERSON\",\"start\":0,\"end\":1,\"score\":1}";
        byte[] body = ("[[" + span + "],[" + span + "]]").getBytes(StandardCharsets.UTF_8);
        PresidioResponseParser parser = new PresidioResponseParser(body.length, 64);

        assertThat(parser.parseSegments(body, List.of("A", "B"), 2)).hasSize(2);
        assertThatThrownBy(() -> parser.parseSegments(body, List.of("A", "B"), 1))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure ->
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED));
    }
}

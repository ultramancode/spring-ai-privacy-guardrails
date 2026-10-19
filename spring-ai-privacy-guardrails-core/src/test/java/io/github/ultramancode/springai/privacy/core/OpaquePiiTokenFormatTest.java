package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpaquePiiTokenFormatTest {

    @Test
    void patternForEntityUsesTheRuntimeTokenGrammarAndRejectsNonCanonicalTypes() {
        String token = OpaquePiiTokenFormat.format(
                "EMAIL_ADDRESS",
                "a7Kp2xQ9mR4vT8zB",
                3
        );

        assertThat(token).isEqualTo("[PII_EMAIL_ADDRESS_3:a7Kp2xQ9mR4vT8zB]");
        assertThat(OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS").matcher(token).matches()).isTrue();
        assertThat(OpaquePiiTokenFormat.isCanonicalToken(token)).isTrue();
        assertThat(OpaquePiiTokenFormat.isCanonicalToken(null)).isFalse();
        assertThatThrownBy(() -> OpaquePiiTokenFormat.patternForEntityType("email-address"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[PII_PERSON_0:a7Kp2xQ9mR4vT8zB]",
            "[PII_PERSON_01:a7Kp2xQ9mR4vT8zB]",
            "[PII_PERSON_-1:a7Kp2xQ9mR4vT8zB]",
            "[PII_PERSON_1:a7Kp2xQ9mR4vT8z]",
            "[PII_PERSON_1:a7Kp2xQ9mR4vT8zBX]",
            "[PII_PERSON_1:a7Kp2xQ9mR4vT8z=]",
            "[PII_PERSON_1:a7Kp2xQ9mR4vT8z+]",
            "[PII_PERSON_1:a7Kp2xQ9mR4vT8z/]",
            "[PII_PERSON_1]",
            "[PII_PERSON__NAME_1:a7Kp2xQ9mR4vT8zB]"
    })
    void tokenGrammarRejectsMalformedTokens(String token) {
        assertThat(OpaquePiiTokenFormat.isCanonicalToken(token)).isFalse();
        assertThat(OpaquePiiTokenFormat.patternForEntityType("PERSON").matcher(token).matches()).isFalse();
    }

    @Test
    void grammarPreservesEntityTypeBoundariesAndBase64UrlCharacters() {
        String nonce = "_-0123456789aBcD";
        String first = OpaquePiiTokenFormat.format("EMPLOYEE_ID_2", nonce, 12);
        String longestType = "A".repeat(EntityTypeRegistry.MAX_ENTITY_TYPE_LENGTH);
        String second = OpaquePiiTokenFormat.format(longestType, nonce, 1);

        assertThat(OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID_2").matcher(first).matches()).isTrue();
        assertThat(OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID").matcher(first).matches()).isFalse();
        assertThat(OpaquePiiTokenFormat.canonicalTokenPattern().matcher("before " + first + second + " after")
                .results().map(result -> result.group()).toList()).containsExactly(first, second);
        assertThat(OpaquePiiTokenFormat.isCanonicalToken("[PII_" + longestType + "A_1:" + nonce + "]"))
                .isFalse();
    }

    @Test
    void generatedNonceEncodesTwelveBytesWithoutPadding() {
        String nonce = OpaquePiiTokenFormat.randomNonce();

        assertThat(nonce).matches("[A-Za-z0-9_-]{16}");
        assertThat(Base64.getUrlDecoder().decode(nonce)).hasSize(12);
    }
}

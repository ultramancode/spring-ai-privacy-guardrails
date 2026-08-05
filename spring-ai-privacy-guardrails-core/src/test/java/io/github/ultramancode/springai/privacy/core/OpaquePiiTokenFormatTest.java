package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpaquePiiTokenFormatTest {

    @Test
    void patternForEntityUsesTheRuntimeTokenGrammarAndRejectsNonCanonicalTypes() {
        String token = OpaquePiiTokenFormat.format(
                "EMAIL_ADDRESS",
                "0123456789abcdef0123456789abcdef",
                3
        );

        assertThat(token).isEqualTo("[[PII_EMAIL_ADDRESS_0123456789abcdef0123456789abcdef_3]]");
        assertThat(OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS").matcher(token).matches()).isTrue();
        assertThat(OpaquePiiTokenFormat.isCanonicalToken(token)).isTrue();
        assertThat(OpaquePiiTokenFormat.isCanonicalToken(null)).isFalse();
        assertThatThrownBy(() -> OpaquePiiTokenFormat.patternForEntityType("email-address"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
    }

    @Test
    void tokenGrammarRejectsInvalidNonceAndZeroIndex() {
        assertThatThrownBy(() -> OpaquePiiTokenFormat.format("PERSON", "short", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(OpaquePiiTokenFormat.patternForEntityType("PERSON").matcher(
                "[[PII_PERSON_0123456789abcdef0123456789abcdef_0]]"
        ).matches()).isFalse();
        assertThat(OpaquePiiTokenFormat.canonicalTokenPattern().matcher(
                "[[PII_PERSON__NAME_0123456789abcdef0123456789abcdef_1]]"
        ).matches()).isFalse();
        assertThat(OpaquePiiTokenFormat.isCanonicalToken(
                "[[PII_PERSON__NAME_0123456789abcdef0123456789abcdef_1]]"
        )).isFalse();
    }
}

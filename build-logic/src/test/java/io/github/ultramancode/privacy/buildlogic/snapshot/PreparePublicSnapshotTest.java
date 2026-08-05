package io.github.ultramancode.privacy.buildlogic.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class PreparePublicSnapshotTest {

    @Test
    void detectsModernOpenAiAndAnthropicCredentials() {
        String openAiCredential = "sk-" + "proj-" + "A".repeat(32);
        String openAiAdminCredential = "sk-" + "admin-" + "C".repeat(32);
        String openAiServiceAccountCredential = "sk-" + "svcacct-" + "D".repeat(32);
        String anthropicCredential = "sk-" + "ant-api03-" + "B".repeat(32);
        String anthropicAdminCredential = "sk-" + "ant-admin01-" + "E".repeat(32);

        assertThat(matchingLabels(openAiCredential)).containsExactly("OpenAI credential");
        assertThat(matchingLabels(openAiAdminCredential)).containsExactly("OpenAI credential");
        assertThat(matchingLabels(openAiServiceAccountCredential))
                .containsExactly("OpenAI credential");
        assertThat(matchingLabels(anthropicCredential)).containsExactly("Anthropic credential");
        assertThat(matchingLabels(anthropicAdminCredential))
                .containsExactly("Anthropic credential");
    }

    @Test
    void ignoresEmptyTemplatesAndShortPlaceholders() {
        assertThat(matchingLabels(
                "OPENAI_COMPATIBLE_API_KEY=",
                "sk-proj-example",
                "sk-ant-api03-placeholder"
        )).isEmpty();
    }

    private static Set<String> matchingLabels(String... values) {
        Map<String, Pattern> rules = PreparePublicSnapshot.forbiddenTextPatterns(List.of());
        return rules.entrySet().stream()
                .filter(entry -> List.of(values).stream()
                        .anyMatch(value -> entry.getValue().matcher(value).find()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}

package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import io.github.ultramancode.springai.privacy.core.RegexPiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.RegexPiiRule;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyToolBoundaryPropertyTest {

    private static final long TOOL_SEED = 0x7001B0A5L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void randomizedNestedToolPayloadsRevealOnlyTheAllowedEntityType() throws Exception {
        Random random = new Random(TOOL_SEED);
        PrivacyService service = privacyService();
        AtomicReference<String> delegateInput = new AtomicReference<>();
        ToolCallback wrapped = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.byToolName(Map.of("customerLookup", List.of("CUSTOMER_ID")))
        ).wrap(delegateInput(delegateInput));

        for (int index = 0; index < 250; index++) {
            String customerId = "CUST-%04d".formatted(index);
            String email = "user%04d@example.test".formatted(index);
            String note = randomValidUnicode(random, 48);
            String rawInput = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "customer", Map.of("id", customerId, "email", email),
                    "notes", List.of(note, Map.of("owner", email))
            ));

            try (PrivacySession session = service.openSession()) {
                String result = wrapped.call(rawInput, PrivacyToolContextFactory.create(session.handle()));

                assertThat(delegateInput.get())
                        .as("delegate input for case %s with seed %s", index, TOOL_SEED)
                        .contains(customerId)
                        .doesNotContain(email);
                assertThat(result)
                        .as("protected result for case %s", index)
                        .doesNotContain(customerId, email);
                assertThat(service.detokenize(session.handle(), result))
                        .isEqualTo("processed " + customerId + " for " + email);
            }
            assertThat(service.activeSessionCount())
                    .as("tool session cleanup for case %s", index)
                    .isZero();
        }
    }

    private static PrivacyService privacyService() {
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule("CUSTOMER_ID", "\\bCUST-\\d{4}\\b", 0.99, 0),
                new RegexPiiRule(
                        "EMAIL_ADDRESS",
                        "\\b[A-Za-z0-9._%+-]+@example\\.test\\b",
                        0.99,
                        0
                )
        ));
        return new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
    }

    private static ToolCallback delegateInput(AtomicReference<String> delegateInput) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("customerLookup")
                        .description("Returns a synthetic customer record")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                delegateInput.set(toolInput);
                return "processed CUST-0000 for user0000@example.test";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                delegateInput.set(toolInput);
                Map<?, ?> parsed = readMap(toolInput);
                Map<?, ?> customer = (Map<?, ?>) parsed.get("customer");
                return "processed " + customer.get("id") + " for "
                        + tokenOrValue((List<?>) parsed.get("notes"));
            }
        };
    }

    private static String tokenOrValue(List<?> notes) {
        Map<?, ?> owner = (Map<?, ?>) notes.get(1);
        return owner.get("owner").toString();
    }

    private static Map<?, ?> readMap(String input) {
        try {
            return OBJECT_MAPPER.readValue(input, Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Synthetic test payload must remain valid JSON", exception);
        }
    }

    private static String randomValidUnicode(Random random, int maxCodePoints) {
        int count = random.nextInt(maxCodePoints + 1);
        StringBuilder value = new StringBuilder(count * 2);
        for (int index = 0; index < count; index++) {
            switch (random.nextInt(5)) {
                case 0 -> value.append((char) (0x20 + random.nextInt(0x5F)));
                case 1 -> value.append((char) (0xAC00 + random.nextInt(11_172)));
                case 2 -> value.appendCodePoint(0x1F300 + random.nextInt(0x500));
                case 3 -> value.append((char) (0x0300 + random.nextInt(0x70)));
                default -> value.append((char) (0x2000 + random.nextInt(0x70)));
            }
        }
        return value.toString();
    }
}

package io.github.ultramancode.springai.privacy.core;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyServicePropertyTest {

    private static final long UNICODE_SEED = 0x5A17C0DEL;
    private static final long STRUCTURE_SEED = 0x51A7E123L;

    @Test
    void arbitraryUnicodeRoundTripsOnlyInsideItsOwningSession() {
        Random random = new Random(UNICODE_SEED);
        PrivacyService service = new PrivacyService(
                List.of(fullTextAnalyzer()),
                PiiAnalysisOptions.defaults()
        );

        for (int index = 0; index < 750; index++) {
            String original = "synthetic-" + index + '-' + randomUnicode(random, 96);
            try (PrivacySession firstSession = service.openSession();
                    PrivacySession secondSession = service.openSession()) {
                String firstToken = service.tokenize(firstSession.handle(), original);
                String secondToken = service.tokenize(secondSession.handle(), original);

                assertThat(secondToken).isNotEqualTo(firstToken);
                assertThat(firstToken)
                        .as("tokenized case %s with seed %s", index, UNICODE_SEED)
                        .matches(OpaquePiiTokenFormat.patternForEntityType("SYNTHETIC"));
                assertThat(service.detokenize(firstSession.handle(), firstToken))
                        .as("first session round trip for case %s", index)
                        .isEqualTo(original);
                // The second session must not restore the original value from the first session's token.
                assertThat(service.detokenize(secondSession.handle(), firstToken))
                        .as("cross-session isolation for case %s", index)
                        .isEqualTo(firstToken);
            }
            assertThat(service.activeSessionCount())
                    .as("session cleanup for case %s", index)
                    .isZero();
        }
    }

    @Test
    void randomNestedJsonCompatibleStructuresRoundTripWithoutLeakingSessions() {
        Random random = new Random(STRUCTURE_SEED);
        PrivacyService service = recursivePrivacyService();

        for (int index = 0; index < 300; index++) {
            String identifier = "SYN-%04d".formatted(index);
            long phone = 821_000_000_000L + index;
            Object original = nestedValue(random, identifier, phone, 0);

            try (PrivacySession firstSession = service.openSession();
                    PrivacySession secondSession = service.openSession()) {
                Object tokenizedValueTree = service.tokenizeValueTree(firstSession.handle(), original);

                assertThat(tokenizedValueTree.toString())
                        .as("protected recursive case %s with seed %s", index, STRUCTURE_SEED)
                        .doesNotContain(identifier, Long.toString(phone));
                assertThat(service.detokenizeValueTree(firstSession.handle(), tokenizedValueTree))
                        .as("recursive round trip for case %s", index)
                        .isEqualTo(original);
                // The second session must not restore the original values from the first session's tokens.
                assertThat(service.detokenizeValueTree(secondSession.handle(), tokenizedValueTree))
                        .as("recursive cross-session isolation for case %s", index)
                        .isEqualTo(tokenizedValueTree);
            }
            assertThat(service.activeSessionCount())
                    .as("recursive session cleanup for case %s", index)
                    .isZero();
        }
    }

    private static PiiAnalyzer fullTextAnalyzer() {
        return new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(
                    String text,
                    PiiAnalysisOptions options,
                    PrivacyProcessingLimits limits
            ) {
                return List.of(new PiiSpan("SYNTHETIC", 0, text.length(), 1.0));
            }

            @Override
            public String providerId() {
                return "PROPERTY_UNICODE";
            }

            @Override
            public Set<String> trustedEntityTypes() {
                return Set.of("SYNTHETIC");
            }
        };
    }

    private static PrivacyService recursivePrivacyService() {
        PatternDefinition syntheticIdPattern = PatternDefinition.builder()
            .expression("\\bSYN-\\d{4}\\b")
            .score(1.0)
            .build();
        PatternDefinition phoneNumberPattern = PatternDefinition.builder()
            .expression("\\b821\\d{9}\\b")
            .score(1.0)
            .build();
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
            PatternPiiRule.builder()
                .id("synthetic-id")
                .entityType("SYNTHETIC_ID")
                .addPattern(syntheticIdPattern)
                .build(),
            PatternPiiRule.builder()
                .id("phone-number")
                .entityType(PiiEntityTypes.PHONE_NUMBER)
                .addPattern(phoneNumberPattern)
                .build()
        ));
        return new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
    }

    private static Object nestedValue(Random random, String identifier, long phone, int depth) {
        if (depth >= 4) {
            return random.nextBoolean() ? identifier : phone;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("identifier-" + depth, identifier);
        result.put("phone-" + depth, phone);
        result.put("note-" + depth, randomUnicode(random, 24));
        List<Object> children = new ArrayList<>();
        children.add(random.nextBoolean());
        children.add(random.nextInt(10_000));
        children.add(nestedValue(random, identifier, phone, depth + 1));
        result.put("children-" + depth, children);
        return result;
    }

    private static String randomUnicode(Random random, int maxUnits) {
        int units = random.nextInt(maxUnits + 1);
        StringBuilder value = new StringBuilder(units + 24);
        for (int index = 0; index < units; index++) {
            switch (random.nextInt(7)) {
                case 0 -> value.append((char) random.nextInt(0x80));
                case 1 -> value.append((char) (0xAC00 + random.nextInt(11_172)));
                case 2 -> value.appendCodePoint(0x1F300 + random.nextInt(0x500));
                case 3 -> value.append((char) (0x0300 + random.nextInt(0x70)));
                case 4 -> value.append((char) (0xD800 + random.nextInt(0x800)));
                case 5 -> value.append("[PII_PERSON_1:0000000000000000]");
                default -> value.append((char) (0x2000 + random.nextInt(0x70)));
            }
        }
        return value.toString();
    }
}

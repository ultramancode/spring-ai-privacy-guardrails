package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.core.PiiTokenizationResult;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DemoRegexEvaluationTest {

    @Autowired
    private PrivacyService privacyService;

    @Test
    void demoRegexRulesMatchTheVersionedSyntheticCorpus() throws IOException {
        List<EvaluationCase> cases = loadEvaluationCases();
        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;

        for (EvaluationCase evaluationCase : cases) {
            List<Detection> expected = evaluationCase.expectedDetections();
            List<Detection> actual;
            String tokenized;
            try (PrivacySession session = this.privacyService.openSession()) {
                PiiTokenizationResult result = this.privacyService.analyzeAndTokenize(
                        session.handle(),
                        evaluationCase.text()
                );
                actual = result.analysis().spans().stream()
                        .map(span -> new Detection(
                                span.entityType(),
                                evaluationCase.text().substring(span.start(), span.end())
                        ))
                        .toList();
                tokenized = result.tokenizedText();
            }

            List<Detection> unmatchedActual = new ArrayList<>(actual);
            int matched = 0;
            for (Detection expectedDetection : expected) {
                if (unmatchedActual.remove(expectedDetection)) {
                    matched++;
                }
            }
            truePositives += matched;
            falsePositives += unmatchedActual.size();
            falseNegatives += expected.size() - matched;

            assertThat(actual)
                    .as("detections for corpus case %s", evaluationCase.id())
                    .containsExactlyElementsOf(expected);
            if (!evaluationCase.rawValues().isEmpty()) {
                assertThat(tokenized)
                        .as("tokenized output for corpus case %s", evaluationCase.id())
                        .doesNotContain(evaluationCase.rawValues().toArray(String[]::new));
            }
            assertThat(this.privacyService.activeSessionCount())
                    .as("session cleanup for corpus case %s", evaluationCase.id())
                    .isZero();
        }

        assertThat(cases).hasSize(64);
        assertThat(truePositives).isEqualTo(37);
        assertThat(falsePositives).isZero();
        assertThat(falseNegatives).isZero();
    }

    private List<EvaluationCase> loadEvaluationCases() throws IOException {
        InputStream resource = getClass().getResourceAsStream("/privacy-evaluation-corpus.tsv");
        if (resource == null) {
            throw new IllegalStateException("privacy evaluation corpus is missing");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource, StandardCharsets.UTF_8)
        )) {
            List<EvaluationCase> cases = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\t", -1);
                if (fields.length != 4) {
                    throw new IllegalStateException("invalid privacy evaluation corpus row");
                }
                cases.add(new EvaluationCase(
                        fields[0],
                        splitList(fields[1]),
                        splitList(fields[2]),
                        fields[3]
                ));
            }
            return List.copyOf(cases);
        }
    }

    private static List<String> splitList(String field) {
        return "-".equals(field) ? List.of() : List.of(field.split(",", -1));
    }

    private record EvaluationCase(String id, List<String> entityTypes, List<String> rawValues, String text) {

        private EvaluationCase {
            if (entityTypes.size() != rawValues.size()) {
                throw new IllegalArgumentException("evaluation types and raw values must have the same size");
            }
        }

        private List<Detection> expectedDetections() {
            List<Detection> detections = new ArrayList<>(this.entityTypes.size());
            for (int index = 0; index < this.entityTypes.size(); index++) {
                detections.add(new Detection(this.entityTypes.get(index), this.rawValues.get(index)));
            }
            return List.copyOf(detections);
        }
    }

    private record Detection(String entityType, String rawValue) {
    }
}

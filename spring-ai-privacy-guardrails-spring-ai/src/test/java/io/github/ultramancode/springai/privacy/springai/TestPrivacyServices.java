package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

final class TestPrivacyServices {

    private TestPrivacyServices() {
    }

    static PrivacyService privacyService() {
        PiiAnalyzer analyzer = (text, options) -> {
            List<PiiSpan> spans = new ArrayList<>();
            for (String name : List.of("Alice", "Bob")) {
                int from = 0;
                while ((from = text.indexOf(name, from)) >= 0) {
                    spans.add(new PiiSpan("PERSON", from, from + name.length(), 0.95));
                    from += name.length();
                }
            }
            String email = "alice@example.com";
            int emailStart = text.indexOf(email);
            if (emailStart >= 0) {
                spans.add(new PiiSpan(
                        "EMAIL_ADDRESS",
                        emailStart,
                        emailStart + email.length(),
                        0.95
                ));
            }
            String numericPhone = "821012345678";
            int phoneStart = text.indexOf(numericPhone);
            if (phoneStart >= 0) {
                spans.add(new PiiSpan(
                        "PHONE_NUMBER",
                        phoneStart,
                        phoneStart + numericPhone.length(),
                        0.95
                ));
            }
            return List.copyOf(spans);
        };
        return new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.defaults()
        );
    }

    static PiiAnalyzer countingSegmentedAnalyzer(
            AtomicInteger singleAnalysisCalls,
            AtomicInteger segmentedAnalysisCalls,
            Set<String> trustedEntityTypes,
            Function<List<String>, List<List<PiiSpan>>> operation
    ) {
        return new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                singleAnalysisCalls.incrementAndGet();
                return List.of();
            }

            @Override
            public List<List<PiiSpan>> analyzeSegments(
                    List<String> texts,
                    PiiAnalysisOptions options
            ) {
                segmentedAnalysisCalls.incrementAndGet();
                return operation.apply(texts);
            }

            @Override
            public Set<String> trustedEntityTypes() {
                return trustedEntityTypes;
            }
        };
    }

    static ChatClientResponse response(String text) {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))),
                Map.of()
        );
    }
}

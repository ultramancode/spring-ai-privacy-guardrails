package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyVectorStoreRagIntegrationTest {

    @Test
    void localVectorStoreRetrievalIsProtectedBeforeTheModelBoundary() {
        PrivacyService service = TestPrivacyServices.privacyService();
        VectorStore vectorStore = SimpleVectorStore.builder(new DeterministicEmbeddingModel()).build();
        vectorStore.add(List.of(
                new Document("Customer account owner: Alice"),
                new Document("Weather archive: clear skies")
        ));
        RecordingPromptModel model = new RecordingPromptModel();
        QuestionAnswerAdvisor retrievalAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(1)
                        .similarityThreshold(0.9)
                        .build())
                .build();
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        retrievalAdvisor,
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .build();

        ChatClientResponse response = chatClient.prompt()
                .user("Who owns the customer account?")
                .call()
                .chatClientResponse();

        Object retrievedValue = response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        assertThat(retrievedValue).isInstanceOf(List.class);
        List<?> retrievedDocuments = (List<?>) retrievedValue;
        assertThat(retrievedDocuments)
                .hasSize(1)
                .allSatisfy(document -> assertThat(document).isInstanceOf(Document.class));
        assertThat(retrievedDocuments.stream()
                .map(Document.class::cast)
                .map(Document::getText))
                .containsExactly("Customer account owner: Alice");
        assertThat(model.lastPrompt())
                .containsPattern(OpaquePiiTokenFormat.patternForEntityType("PERSON"))
                .doesNotContain("Alice");
        assertThat(service.activeSessionCount()).isZero();
    }

    private static final class DeterministicEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = request.getInstructions().stream()
                    .map(DeterministicEmbeddingModel::vectorFor)
                    .map(vector -> new Embedding(vector, null))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vectorFor(document.getText());
        }

        @Override
        public int dimensions() {
            return 2;
        }

        private static float[] vectorFor(String content) {
            String normalized = content.toLowerCase(Locale.ROOT);
            if (normalized.contains("customer") || normalized.contains("account")
                    || normalized.contains("owner")) {
                return new float[]{1.0f, 0.0f};
            }
            return new float[]{0.0f, 1.0f};
        }
    }

    private static final class RecordingPromptModel implements ChatModel {

        private volatile String lastPrompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt.getInstructions().stream()
                    .map(Message::getText)
                    .filter(Objects::nonNull)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        String lastPrompt() {
            return this.lastPrompt;
        }
    }
}

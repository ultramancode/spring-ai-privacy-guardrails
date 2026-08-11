package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
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

import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class PrivacyDemoRag {

    private static final String QUERY = "Which email owns the customer account?";
    private static final String RAW_PII = "alice@example.com";
    private static final String RETRIEVED_DOCUMENT = "Customer account owner email: " + RAW_PII;

    private final ChatClient chatClient;
    private final RecordingPromptChatModel chatModel;

    PrivacyDemoRag(PrivacyChatClientConfigurer privacyConfigurer) {
        VectorStore vectorStore = SimpleVectorStore.builder(new DeterministicEmbeddingModel()).build();
        vectorStore.add(List.of(
                new Document(RETRIEVED_DOCUMENT),
                new Document("Weather archive: clear skies")
        ));
        QuestionAnswerAdvisor retrievalAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(1)
                        .similarityThreshold(0.9)
                        .build())
                .build();
        this.chatModel = new RecordingPromptChatModel();
        ChatClient.Builder builder = ChatClient.builder(this.chatModel)
                .defaultAdvisors(retrievalAdvisor);
        this.chatClient = privacyConfigurer.configure(builder).build();
    }

    Result run() {
        try {
            ChatClientResponse response = this.chatClient.prompt()
                    .user(QUERY)
                    .call()
                    .chatClientResponse();
            String retrievedDocument = requireRetrievedDocument(response);
            String modelVisibleContext = this.chatModel.requireRecordedPrompt();
            return new Result(
                    retrievedDocument,
                    modelVisibleContext,
                    retrievedDocument.contains(RAW_PII),
                    modelVisibleContext.contains(RAW_PII),
                    OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS")
                            .matcher(modelVisibleContext)
                            .find()
            );
        } finally {
            this.chatModel.clearRecordedPrompt();
        }
    }

    private static String requireRetrievedDocument(ChatClientResponse response) {
        Object value = response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (!(value instanceof List<?> documents)
                || documents.size() != 1
                || !(documents.get(0) instanceof Document document)) {
            throw new IllegalStateException("RAG demo expected exactly one retrieved document");
        }
        return document.getText();
    }

    record Result(
            String retrievedDocument,
            String modelVisibleContext,
            boolean retrievedDocumentContainsRawPii,
            boolean modelVisibleContextContainsRawPii,
            boolean modelVisibleContextContainsTokenizedPii
    ) {
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

    private static final class RecordingPromptChatModel implements ChatModel {

        private final ThreadLocal<String> recordedPrompt = new ThreadLocal<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            String modelVisibleContext = prompt.getInstructions().stream()
                    .map(Message::getText)
                    .filter(Objects::nonNull)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            this.recordedPrompt.set(modelVisibleContext);
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("ok")
            )));
        }

        String requireRecordedPrompt() {
            String prompt = this.recordedPrompt.get();
            if (prompt == null) {
                throw new IllegalStateException("RAG demo model did not record a prompt");
            }
            return prompt;
        }

        void clearRecordedPrompt() {
            this.recordedPrompt.remove();
        }
    }
}

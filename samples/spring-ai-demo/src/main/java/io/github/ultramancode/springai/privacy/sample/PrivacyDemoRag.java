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
import org.springframework.ai.chat.prompt.PromptTemplate;
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
import java.util.Map;
import java.util.Objects;

final class PrivacyDemoRag {

    private static final String RAW_PII = "alice@example.com";
    private static final RagFixture ENGLISH = new RagFixture(
            "Which email owns the customer account?",
            "Customer account owner email: " + RAW_PII,
            new PromptTemplate("""
                    {query}

                    Context information is below, surrounded by ---------------------

                    ---------------------
                    {question_answer_context}
                    ---------------------

                    Given the context and provided history information and not prior knowledge,
                    reply to the user comment. If the answer is not in the context, inform
                    the user that you can't answer the question.
                    """)
    );
    private static final RagFixture KOREAN = new RagFixture(
            "고객 계정 소유자의 이메일은 무엇인가요?",
            "고객 계정 소유자 이메일: " + RAW_PII,
            new PromptTemplate("""
                    {query}

                    컨텍스트 정보는 아래 --------------------- 사이에 있습니다.

                    ---------------------
                    {question_answer_context}
                    ---------------------

                    사전 지식이 아니라 주어진 컨텍스트와 대화 이력을 사용해 답하세요.
                    답이 컨텍스트에 없다면 질문에 답할 수 없다고 알려주세요.
                    """)
    );

    private final Map<PrivacyDemoLocale, ChatClient> chatClients;
    private final RecordingPromptChatModel chatModel;

    PrivacyDemoRag(PrivacyChatClientConfigurer privacyConfigurer) {
        VectorStore vectorStore = SimpleVectorStore.builder(new DeterministicEmbeddingModel()).build();
        vectorStore.add(List.of(
                new Document(ENGLISH.retrievedDocument()),
                new Document(KOREAN.retrievedDocument()),
                new Document("Weather archive: clear skies")
        ));
        this.chatModel = new RecordingPromptChatModel();
        this.chatClients = Map.of(
                PrivacyDemoLocale.EN, chatClient(privacyConfigurer, vectorStore, ENGLISH),
                PrivacyDemoLocale.KO, chatClient(privacyConfigurer, vectorStore, KOREAN)
        );
    }

    private ChatClient chatClient(
            PrivacyChatClientConfigurer privacyConfigurer,
            VectorStore vectorStore,
            RagFixture fixture
    ) {
        QuestionAnswerAdvisor retrievalAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(1)
                        .similarityThreshold(0.9)
                        .build())
                .promptTemplate(fixture.promptTemplate())
                .build();
        ChatClient.Builder builder = ChatClient.builder(this.chatModel)
                .defaultAdvisors(retrievalAdvisor);
        return privacyConfigurer.configure(builder).build();
    }

    Result run(PrivacyDemoLocale locale) {
        RagFixture fixture = fixture(locale);
        try {
            ChatClientResponse response = this.chatClients.get(locale).prompt()
                    .user(fixture.query())
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

    private static RagFixture fixture(PrivacyDemoLocale locale) {
        return locale == PrivacyDemoLocale.KO ? KOREAN : ENGLISH;
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

    private record RagFixture(
            String query,
            String retrievedDocument,
            PromptTemplate promptTemplate
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
            return 3;
        }

        private static float[] vectorFor(String content) {
            String normalized = content.toLowerCase(Locale.ROOT);
            if (normalized.contains("고객") || normalized.contains("계정")
                    || normalized.contains("소유자")) {
                return new float[]{0.0f, 1.0f, 0.0f};
            }
            if (normalized.contains("customer") || normalized.contains("account")
                    || normalized.contains("owner")) {
                return new float[]{1.0f, 0.0f, 0.0f};
            }
            return new float[]{0.0f, 0.0f, 1.0f};
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

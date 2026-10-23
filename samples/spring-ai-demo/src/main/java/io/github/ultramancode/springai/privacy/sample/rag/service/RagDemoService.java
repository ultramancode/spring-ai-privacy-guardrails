package io.github.ultramancode.springai.privacy.sample.rag.service;

import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.sample.rag.dto.response.RagDemoResponse;
import io.github.ultramancode.springai.privacy.sample.rag.infrastructure.DemoRagChatModel;
import io.github.ultramancode.springai.privacy.sample.rag.infrastructure.DemoRagEmbeddingModel;
import io.github.ultramancode.springai.privacy.sample.rag.model.RagDemoData;
import io.github.ultramancode.springai.privacy.sample.shared.model.DemoLanguage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public final class RagDemoService {

    private static final Pattern EMAIL_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS");

    private final GuardrailsChatClientFactory guardrailsChatClientFactory;
    private final PrivacyService privacyService;
    private final VectorStore vectorStore;

    public RagDemoService(GuardrailsChatClientFactory guardrailsChatClientFactory, PrivacyService privacyService) {
        this.guardrailsChatClientFactory = guardrailsChatClientFactory;
        this.privacyService = privacyService;
        this.vectorStore = createDemoVectorStore();
    }

    public RagDemoResponse runRagDemo(Locale locale) {
        RagDemoData demoData = RagDemoData.getByLanguage(DemoLanguage.from(locale));
        DemoRagChatModel model = new DemoRagChatModel();
        ChatClient client = createRetrievalChatClient(model, demoData);

        ChatClientResponse response = client.prompt().user(demoData.query()).call().chatClientResponse();
        String retrievedDocument = requireRetrievedDocument(response);
        String modelInput = model.requireRecordedPrompt();
        boolean retrievedDocumentContainsRawPii = retrievedDocument.contains(RagDemoData.RAW_PII);
        boolean modelInputContainsRawPii = modelInput.contains(RagDemoData.RAW_PII);
        boolean modelInputContainsTokenizedPii = EMAIL_TOKEN_PATTERN.matcher(modelInput).find();
        return new RagDemoResponse(
                retrievedDocument,
                modelInput,
                retrievedDocumentContainsRawPii,
                modelInputContainsRawPii,
                modelInputContainsTokenizedPii,
                this.privacyService.activeSessionCount()
        );
    }

    private static VectorStore createDemoVectorStore() {
        VectorStore vectorStore = SimpleVectorStore.builder(new DemoRagEmbeddingModel()).build();
        vectorStore.add(List.of(
                new Document(RagDemoData.getByLanguage(DemoLanguage.EN).sourceDocument()),
                new Document(RagDemoData.getByLanguage(DemoLanguage.KO).sourceDocument()),
                new Document("Weather archive: clear skies")
        ));
        return vectorStore;
    }

    private ChatClient createRetrievalChatClient(DemoRagChatModel model, RagDemoData demoData) {
        QuestionAnswerAdvisor retrievalAdvisor = QuestionAnswerAdvisor.builder(this.vectorStore)
                .searchRequest(SearchRequest.builder().topK(1).similarityThreshold(0.9).build())
                .promptTemplate(demoData.promptTemplate())
                .build();
        return this.guardrailsChatClientFactory.builder(model)
                .defaultAdvisors(retrievalAdvisor)
                .build();
    }

    private static String requireRetrievedDocument(ChatClientResponse response) {
        Object value = response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (!(value instanceof List<?> documents) || documents.size() != 1) {
            throw new IllegalStateException("RAG demo expected exactly one retrieved document");
        }
        if (!(documents.get(0) instanceof Document document)) {
            throw new IllegalStateException("RAG demo expected exactly one retrieved document");
        }
        return document.getText();
    }
}

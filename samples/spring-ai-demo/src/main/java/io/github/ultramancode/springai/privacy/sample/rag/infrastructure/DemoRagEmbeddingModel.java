package io.github.ultramancode.springai.privacy.sample.rag.infrastructure;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.Locale;

public final class DemoRagEmbeddingModel implements EmbeddingModel {

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = request.getInstructions().stream()
                .map(DemoRagEmbeddingModel::selectDemoVector)
                .map(vector -> new Embedding(vector, null))
                .toList();
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return selectDemoVector(document.getText());
    }

    @Override
    public int dimensions() {
        return 3;
    }

    private static float[] selectDemoVector(String content) {
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        if (normalizedContent.contains("고객") || normalizedContent.contains("계정")
                || normalizedContent.contains("소유자")) {
            return new float[]{0.0f, 1.0f, 0.0f};
        }
        if (normalizedContent.contains("customer") || normalizedContent.contains("account")
                || normalizedContent.contains("owner")) {
            return new float[]{1.0f, 0.0f, 0.0f};
        }
        return new float[]{0.0f, 0.0f, 1.0f};
    }
}

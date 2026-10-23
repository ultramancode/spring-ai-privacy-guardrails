package io.github.ultramancode.springai.privacy.sample.rag.model;

import io.github.ultramancode.springai.privacy.sample.shared.model.DemoLanguage;
import org.springframework.ai.chat.prompt.PromptTemplate;

public record RagDemoData(String query, String sourceDocument, PromptTemplate promptTemplate) {

    public static final String RAW_PII = "alice@example.com";
    private static final RagDemoData ENGLISH = new RagDemoData(
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
    private static final RagDemoData KOREAN = new RagDemoData(
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

    public static RagDemoData getByLanguage(DemoLanguage language) {
        if (language == DemoLanguage.KO) {
            return KOREAN;
        }
        return ENGLISH;
    }
}

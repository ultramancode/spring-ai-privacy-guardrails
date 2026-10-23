package io.github.ultramancode.springai.privacy.sample.rag.service;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.sample.rag.dto.response.RagDemoResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RagDemoServiceTest {

    @Autowired
    private RagDemoService rag;

    @Autowired
    private PrivacyService privacyService;

    @Test
    @Timeout(20)
    void concurrentLanguagesKeepTheirRetrievedDocumentsAndModelEvidenceSeparate() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RagDemoResponse> english = executor.submit(() -> {
                ready.countDown();
                start.await();
                return this.rag.runRagDemo(Locale.ENGLISH);
            });
            Future<RagDemoResponse> korean = executor.submit(() -> {
                ready.countDown();
                start.await();
                return this.rag.runRagDemo(Locale.KOREAN);
            });
            ready.await();
            start.countDown();

            RagDemoResponse englishResult = english.get();
            RagDemoResponse koreanResult = korean.get();
            assertThat(englishResult.retrievedDocument()).isEqualTo("Customer account owner email: alice@example.com");
            assertThat(koreanResult.retrievedDocument()).isEqualTo("고객 계정 소유자 이메일: alice@example.com");
            assertThat(englishResult.modelInput()).contains("Customer account owner email:").doesNotContain("고객");
            assertThat(koreanResult.modelInput()).contains("고객 계정 소유자 이메일:").doesNotContain("Customer account");
            assertProtectedModelInput(englishResult);
            assertProtectedModelInput(koreanResult);
            assertThat(this.privacyService.activeSessionCount()).isZero();
        }
        finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertProtectedModelInput(RagDemoResponse result) {
        assertThat(result.retrievedDocumentContainsRawPii()).isTrue();
        assertThat(result.modelInputContainsRawPii()).isFalse();
        assertThat(result.modelInputContainsTokenizedPii()).isTrue();
        assertThat(result.modelInput()).doesNotContain("alice@example.com").contains("[PII_EMAIL_ADDRESS_");
    }
}

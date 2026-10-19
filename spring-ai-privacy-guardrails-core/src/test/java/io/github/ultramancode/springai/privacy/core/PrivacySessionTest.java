package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacySessionTest {

    @Test
    void sessionUsesOpaqueNonceTokensAndDestroysMappingsOnClose() {
        PrivacyService service = privacyService();
        PrivacyContextHandle handle;
        String tokenized;

        try (PrivacySession session = service.openSession()) {
            handle = session.handle();
            tokenized = service.tokenize(handle, "Alice");

            assertThat(tokenized).matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
            assertThat(service.detokenize(handle, tokenized)).isEqualTo("Alice");
            assertThat(service.isSessionActive(handle)).isTrue();
        }

        assertThat(service.activeSessionCount()).isZero();
        PrivacyContextHandle closedHandle = handle;
        assertThatThrownBy(() -> service.detokenize(closedHandle, tokenized))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void detokenizeCanRevealOnlyExplicitlyAllowedEntityTypes() {
        PrivacyService service = privacyService();

        try (PrivacySession session = service.openSession()) {
            String raw = "Alice alice@example.com";
            String tokenized = service.tokenize(session.handle(), raw, List.of(
                    new PiiSpan("PERSON", 0, 5, 0.95),
                    new PiiSpan("EMAIL_ADDRESS", 6, raw.length(), 0.95)
            ));

            String disclosed = service.detokenize(
                    session.handle(),
                    tokenized,
                    Set.of("EMAIL_ADDRESS")
            );

            assertThat(disclosed)
                    .contains("alice@example.com")
                    .containsPattern(OpaquePiiTokenFormat.patternForEntityType("PERSON"))
                    .doesNotContain("Alice ");
        }
    }

    @Test
    void tokensKeepDistinctIndexesAndReuseTheNonceWithinOneSession() {
        PrivacyService service = privacyService();

        try (PrivacySession session = service.openSession()) {
            PrivacyContextHandle handle = session.handle();
            String aliceToken = service.tokenize(handle, "Alice");
            String bobToken = service.tokenize(handle, "Bob");
            String emailToken = service.tokenize(handle, "alice@example.com",
                    List.of(new PiiSpan("EMAIL_ADDRESS", 0, 17, 0.95)));
            // Take the nonce after ':' and before the closing ']'.
            String sessionNonce = aliceToken.substring(aliceToken.indexOf(':') + 1, aliceToken.length() - 1);

            assertThat(aliceToken).isEqualTo("[PII_PERSON_1:" + sessionNonce + "]");
            assertThat(bobToken).isEqualTo("[PII_PERSON_2:" + sessionNonce + "]");
            assertThat(emailToken).isEqualTo("[PII_EMAIL_ADDRESS_1:" + sessionNonce + "]");
            assertThat(service.detokenize(handle, aliceToken + " " + bobToken + " " + emailToken))
                    .isEqualTo("Alice Bob alice@example.com");
        }
    }

    @Test
    void detokenizationLeavesUnissuedTokensUnchanged() {
        PrivacyService service = privacyService();

        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), "Alice");
            int nonceStart = token.indexOf(':') + 1;
            char changed = token.charAt(nonceStart) == 'A' ? 'B' : 'A';
            String tokenWithChangedNonce = token.substring(0, nonceStart) + changed + token.substring(nonceStart + 1);
            for (String unissued : List.of(
                    "[PII_PERSON_1]",
                    token.replace("PERSON_1:", "PERSON_2:"),
                    token.replace("PERSON", "EMAIL_ADDRESS"),
                    tokenWithChangedNonce
            )) {
                assertThat(service.detokenize(session.handle(), unissued)).isEqualTo(unissued);
            }
        }
    }

    @Test
    void detokenizeValueTreeAppliesEntityScopeToNestedValues() {
        PrivacyService service = privacyService();

        try (PrivacySession session = service.openSession()) {
            String personToken = service.tokenize(
                    session.handle(),
                    "Alice",
                    List.of(new PiiSpan("PERSON", 0, 5, 0.95))
            );
            String email = "alice@example.com";
            String emailToken = service.tokenize(
                    session.handle(),
                    email,
                    List.of(new PiiSpan("EMAIL_ADDRESS", 0, email.length(), 0.95))
            );

            Object disclosed = service.detokenizeValueTree(
                    session.handle(),
                    Map.of("contact", List.of(personToken, emailToken)),
                    Set.of("PERSON")
            );

            assertThat(disclosed).isEqualTo(Map.of(
                    "contact",
                    List.of("Alice", emailToken)
            ));
        }
    }

    @Test
    void sessionsRemainIsolatedAcrossConcurrentThreads() throws Exception {
        PrivacyService service = privacyService();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<String> alice = CompletableFuture.supplyAsync(() -> roundTrip(service, "Alice"), executor);
            CompletableFuture<String> bob = CompletableFuture.supplyAsync(() -> roundTrip(service, "Bob"), executor);

            assertThat(alice.get()).isEqualTo("Alice");
            assertThat(bob.get()).isEqualTo("Bob");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void contextReferenceCannotBeUsedAfterItsSessionIsDestroyed() {
        PrivacyContextRegistry registry = new PrivacyContextRegistry();
        PrivacySession session = registry.openSession();
        PrivacyContext context = registry.requireActiveContext(session.handle());

        session.close();
        session.close();

        assertThatThrownBy(() -> context.tokenFor("PERSON", "Alice"))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("closed");
    }

    private String roundTrip(PrivacyService service, String name) {
        try (PrivacySession session = service.openSession()) {
            String tokenized = service.tokenize(session.handle(), name);
            return service.detokenize(session.handle(), tokenized);
        }
    }

    private PrivacyService privacyService() {
        PiiAnalyzer analyzer = (text, options, processingLimits) -> List.of("Alice", "Bob").stream()
                .filter(text::contains)
                .map(name -> {
                    int start = text.indexOf(name);
                    return new PiiSpan("PERSON", start, start + name.length(), 0.95);
                })
                .toList();
        return new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
    }
}

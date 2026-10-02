package io.github.ultramancode.springai.privacy.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;

import java.util.List;

import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolAuthorizationSessionTest {

    @Test
    void repeatedCloseRemovesOnlyItsOwnSession() {
        ToolAuthorizationSessionRegistry registry = new ToolAuthorizationSessionRegistry();
        Authentication alice = authentication("alice");
        Authentication bob = authentication("bob");

        try (ToolAuthorizationSession aliceSession = registry.openSession(alice, List.of());
                ToolAuthorizationSession bobSession = registry.openSession(bob, List.of())) {
            assertThat(registry.requireActiveSessionState(aliceSession.handle()).authentication())
                    .isSameAs(alice);

            aliceSession.close();
            aliceSession.close();

            assertThatThrownBy(() -> registry.requireActiveSessionState(aliceSession.handle()))
                    .isInstanceOf(AuthorizationDeniedException.class);
            assertThat(registry.requireActiveSessionState(bobSession.handle()).authentication())
                    .isSameAs(bob);
        }

        assertThat(registry.activeSessionCount()).isZero();
    }

    @Test
    void unregisteredHandleCannotAccessAnActiveSession() {
        ToolAuthorizationSessionRegistry registry = new ToolAuthorizationSessionRegistry();
        Authentication alice = authentication("alice");

        try (ToolAuthorizationSession session = registry.openSession(alice, List.of())) {
            ToolAuthorizationSessionHandle unregisteredHandle = new ToolAuthorizationSessionHandle();

            assertThatThrownBy(() -> registry.requireActiveSessionState(unregisteredHandle))
                    .isInstanceOf(AuthorizationDeniedException.class);
            assertThat(registry.requireActiveSessionState(session.handle()).authentication())
                    .isSameAs(alice);
        }
    }
}

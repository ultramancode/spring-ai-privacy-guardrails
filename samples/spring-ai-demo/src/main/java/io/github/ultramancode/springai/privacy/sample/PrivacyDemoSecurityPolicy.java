package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationPhase;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class PrivacyDemoSecurityPolicy {

    private static final String CUSTOMER_LOOKUP = "customerLookup";

    AuthorizationDecision authorize(
            Authentication authentication,
            ToolAuthorizationContext context
    ) {
        boolean granted = authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> Role.CUSTOMER_SUPPORT.authority()
                                .equals(authority.getAuthority()))
                && CUSTOMER_LOOKUP.equals(context.toolDefinition().name());
        if (authentication != null && authentication.getPrincipal() instanceof DemoPrincipal principal) {
            principal.record(new AuthorizationCheck(
                    context.toolDefinition().name(),
                    context.phase(),
                    granted
            ));
        }
        return new AuthorizationDecision(granted);
    }

    <T> AuthorizedRun<T> runAs(Role role, Supplier<T> action) {
        SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();
        SecurityContext previousContext = strategy.getContext();
        DemoPrincipal principal = new DemoPrincipal(role.name().toLowerCase());
        SecurityContext requestContext = strategy.createEmptyContext();
        requestContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal,
                "not-used",
                List.of(new SimpleGrantedAuthority(role.authority()))
        ));
        strategy.setContext(requestContext);
        try {
            return new AuthorizedRun<>(action.get(), principal.checks());
        }
        finally {
            strategy.setContext(previousContext);
        }
    }

    enum Role {

        GENERAL_EMPLOYEE("ROLE_EMPLOYEE"),
        CUSTOMER_SUPPORT("ROLE_CUSTOMER_SUPPORT");

        private final String authority;

        Role(String authority) {
            this.authority = authority;
        }

        String authority() {
            return this.authority;
        }
    }

    record AuthorizationCheck(
            String toolName,
            ToolAuthorizationPhase phase,
            boolean granted
    ) {
    }

    record AuthorizedRun<T>(T value, List<AuthorizationCheck> checks) {
    }

    private static final class DemoPrincipal implements Principal {

        private final String name;
        private final List<AuthorizationCheck> checks = new ArrayList<>();

        private DemoPrincipal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return this.name;
        }

        private void record(AuthorizationCheck check) {
            this.checks.add(check);
        }

        private List<AuthorizationCheck> checks() {
            return List.copyOf(this.checks);
        }
    }
}

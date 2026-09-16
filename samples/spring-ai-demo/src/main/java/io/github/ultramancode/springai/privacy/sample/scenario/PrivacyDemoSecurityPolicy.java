package io.github.ultramancode.springai.privacy.sample.scenario;

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

public final class PrivacyDemoSecurityPolicy {

    private static final String CUSTOMER_LOOKUP = "customerLookup";

    public AuthorizationDecision authorize(
            Authentication authentication,
            ToolAuthorizationContext context
    ) {
        boolean granted = isToolAllowed(authentication, context.toolDefinition().name());
        if (authentication != null && authentication.getPrincipal() instanceof DemoPrincipal principal) {
            principal.record(new AuthorizationCheck(
                    context.toolDefinition().name(),
                    context.phase(),
                    granted
            ));
        }
        return new AuthorizationDecision(granted);
    }

    private static boolean isToolAllowed(Authentication authentication, String toolName) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (toolName == null) {
            return false;
        }
        return switch (toolName) {
            case CUSTOMER_LOOKUP -> hasRole(authentication, Role.CUSTOMER_SUPPORT);
            default -> false;
        };
    }

    private static boolean hasRole(Authentication authentication, Role role) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> role.authority().equals(authority.getAuthority()));
    }

    <T> AuthenticatedRun<T> runAs(Role role, Supplier<T> action) {
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
            T result = action.get();
            List<AuthorizationCheck> checks = principal.checks();
            return new AuthenticatedRun<>(result, checks);
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

    record AuthenticatedRun<T>(T value, List<AuthorizationCheck> checks) {
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

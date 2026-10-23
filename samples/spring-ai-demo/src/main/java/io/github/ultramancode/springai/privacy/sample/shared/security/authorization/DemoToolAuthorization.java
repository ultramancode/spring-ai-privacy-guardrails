package io.github.ultramancode.springai.privacy.sample.shared.security.authorization;

import io.github.ultramancode.springai.privacy.sample.shared.security.model.DemoRole;
import io.github.ultramancode.springai.privacy.sample.shared.security.model.RoleExecutionResult;
import io.github.ultramancode.springai.privacy.sample.shared.security.model.ToolAuthorizationCheck;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class DemoToolAuthorization implements AuthorizationManager<ToolAuthorizationContext> {

    private static final String CUSTOMER_LOOKUP = "customerLookup";

    @Override
    public AuthorizationDecision authorize(
            Supplier<? extends Authentication> authenticationSupplier,
            ToolAuthorizationContext toolContext
    ) {
        Authentication authentication = authenticationSupplier.get();
        boolean granted = isToolAllowed(authentication, toolContext.toolDefinition().name());
        if (authentication != null && authentication.getPrincipal() instanceof DemoPrincipal principal) {
            principal.record(new ToolAuthorizationCheck(
                    toolContext.toolDefinition().name(),
                    toolContext.phase(),
                    granted
            ));
        }
        return new AuthorizationDecision(granted);
    }

    public <T> RoleExecutionResult<T> executeAsRole(DemoRole role, Supplier<T> action) {
        SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();
        SecurityContext previousContext = strategy.getContext();
        DemoPrincipal principal = new DemoPrincipal(role.name().toLowerCase(Locale.ROOT));
        SecurityContext requestContext = strategy.createEmptyContext();
        requestContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal,
                "not-used",
                List.of(new SimpleGrantedAuthority(role.authority()))
        ));
        strategy.setContext(requestContext);
        try {
            T result = action.get();
            List<ToolAuthorizationCheck> checks = principal.checks();
            return new RoleExecutionResult<>(result, checks);
        }
        finally {
            strategy.setContext(previousContext);
        }
    }

    private static boolean isToolAllowed(Authentication authentication, String toolName) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (toolName == null) {
            return false;
        }
        return switch (toolName) {
            case CUSTOMER_LOOKUP -> hasRole(authentication, DemoRole.CUSTOMER_SUPPORT);
            default -> false;
        };
    }

    private static boolean hasRole(Authentication authentication, DemoRole role) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> role.authority().equals(authority.getAuthority()));
    }

    private static final class DemoPrincipal implements Principal {

        private final String name;
        private final List<ToolAuthorizationCheck> checks = new ArrayList<>();

        private DemoPrincipal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return this.name;
        }

        private void record(ToolAuthorizationCheck check) {
            this.checks.add(check);
        }

        private List<ToolAuthorizationCheck> checks() {
            return List.copyOf(this.checks);
        }
    }
}

package io.github.ultramancode.springai.privacy.security;

import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;

import java.util.Objects;
import java.util.Optional;

/**
 * Indicates that the tool authorization boundary rejected a request.
 */
public final class ToolAuthorizationDeniedException extends AuthorizationDeniedException {

    private final ToolAuthorizationDenialCode code;
    private final ToolAuthorizationPhase phase;

    /**
     * Creates a denial before definition or execution checks.
     *
     * @param code the denial code
     * @param message a diagnostic message without sensitive values
     */
    public ToolAuthorizationDeniedException(ToolAuthorizationDenialCode code, String message) {
        this(code, null, message);
    }

    /**
     * Creates a denial without a policy result.
     *
     * @param code the denial code
     * @param phase the authorization phase, or {@code null} before either phase
     * @param message a diagnostic message without sensitive values
     */
    public ToolAuthorizationDeniedException(
            ToolAuthorizationDenialCode code,
            ToolAuthorizationPhase phase,
            String message
    ) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.phase = phase;
    }

    /**
     * Creates a denial that preserves the policy's authorization result.
     *
     * @param code the denial code
     * @param phase the authorization phase, or {@code null} before either phase
     * @param message a diagnostic message without sensitive values
     * @param authorizationResult the non-null result that denied access
     */
    public ToolAuthorizationDeniedException(
            ToolAuthorizationDenialCode code,
            ToolAuthorizationPhase phase,
            String message,
            AuthorizationResult authorizationResult
    ) {
        super(message, authorizationResult);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.phase = phase;
    }

    /**
     * Returns the denial code.
     *
     * @return the denial code
     */
    public ToolAuthorizationDenialCode code() {
        return this.code;
    }

    /**
     * Returns the phase in which the request was rejected.
     * Empty when the request was rejected before definition or execution checks.
     *
     * @return the authorization phase, if applicable
     */
    public Optional<ToolAuthorizationPhase> phase() {
        return Optional.ofNullable(this.phase);
    }
}

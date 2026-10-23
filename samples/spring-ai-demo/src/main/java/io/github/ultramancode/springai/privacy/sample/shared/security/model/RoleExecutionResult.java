package io.github.ultramancode.springai.privacy.sample.shared.security.model;

import java.util.List;

public record RoleExecutionResult<T>(T result, List<ToolAuthorizationCheck> authorizationChecks) {
}

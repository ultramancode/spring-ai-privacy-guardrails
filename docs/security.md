# Spring Security Tool Authorization

**English** | [한국어](ko/security.md)

The optional Spring Security integration controls whether the current
`Authentication` may discover or execute a Spring AI tool. It complements the
privacy boundary. It does not replace PII detection or per-tool original-value
disclosure.

Use this integration when tool availability depends on the current principal.
Applications that do not need identity-aware tool authorization can continue to
use `PrivacyChatClientConfigurer` without adding Spring Security.

## Guarantees

On the supported path, the integration enforces the following checkpoints.

| Checkpoint | Behavior |
| --- | --- |
| Model exposure | The authorization policy receives `ToolAuthorizationPhase.DEFINITION`. Denied tool definitions are omitted before the model sees the available tools. |
| Model-requested call | A tool that was not exposed is rejected even if the model names it or a resolver fallback could otherwise find it. |
| Execution batch | Every requested tool is authorized before any tool in that batch starts. |
| Callback invocation | Each business callback is authorized again immediately before invocation and before `PrivacyToolCallbackFactory` restores any allowed original PII. |
| Tool result | The existing privacy boundary protects the result before it returns to the model or application. |

`ToolAuthorizationContext` contains only the `ToolDefinition` and the current
authorization phase. Tool arguments and request PII are not included. Spring
Security supplies the current `Authentication` through the standard
`AuthorizationManager` contract.

Authorization and PII disclosure answer different questions:

- `AuthorizationManager<ToolAuthorizationContext>` decides whether the current
  principal may discover or execute a tool.
- `ToolDisclosurePolicy`, configured through `tools.disclosures`, decides which
  PII entity types an authorized tool may receive as original values.

A tool must pass authorization before the privacy wrapper can restore an allowed
original value. Authorizing a tool does not grant it every PII type.

## Add the Spring Boot Starter

The optional starter is available from version `0.3.0`.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-spring-security-spring-boot-starter:0.3.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-spring-security-spring-boot-starter</artifactId>
    <version>0.3.0</version>
</dependency>
```

The Security starter includes the base Privacy Guardrails starter. Among Spring
Security modules, it depends only on Spring Security Core. It does not add login
pages, authenticate users, issue JWTs, configure OAuth, or provide
resource-server support. The application continues to own authentication and
must arrange for an `Authentication` to be available when a protected request
containing tool callbacks begins.

When upgrading an existing application, remove a separately declared base
starter and keep every additional Privacy Guardrails artifact, including
analyzer starters, on version `0.3.0`.

Add an analyzer-specific starter separately when needed. For example, a
Presidio setup uses both the Security starter and the Presidio starter. The
built-in Regex analyzer remains available through the included base starter.

Enable both privacy protection and the optional authorization boundary:

```yaml
spring:
  ai:
    privacy:
      enabled: true
      security:
        enabled: true
```

Also configure at least one analyzer or provide a `PiiAnalyzer` bean. Enabling
Security without `spring.ai.privacy.enabled=true` fails application startup.

## Define the Authorization Policy

Provide one `AuthorizationManager<ToolAuthorizationContext>` bean. This uses the
normal Spring Security extension contract and keeps the tool policy under
application control.

```java
@Bean
AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
    return (authentication, context) -> {
        Authentication current = authentication.get();
        boolean supportUser = current != null
                && current.isAuthenticated()
                && current.getAuthorities().stream()
                        .anyMatch(authority ->
                                authority.getAuthority().equals("ROLE_SUPPORT"));

        boolean granted = switch (context.toolDefinition().name()) {
            case "customerLookup" -> supportUser;
            default -> false;
        };
        return new AuthorizationDecision(granted);
    };
}
```

The authorization policy applies when building the tool list for the model and
when executing a tool requested by the model. It can inspect `context.phase()`
when those phases need different rules. Returning `null` or a denied result
hides the definition during the definition phase and rejects the call during
the execution phase.

For a deliberate allow-all policy, provide the bean explicitly:

```java
@Bean
AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
    return (authentication, context) -> new AuthorizationDecision(true);
}
```

An allow-all policy preserves the boundary checks but provides no principal-
specific restriction. It still requires an `Authentication` when a request
containing tool callbacks enters the boundary.

## Configure the ChatClient

Use `PrivacySecurityChatClientConfigurer` instead of applying
`PrivacyChatClientConfigurer` separately. The combined configurer installs the
existing privacy advisors and the request advisor that captures Spring Security
context.

```java
@Bean
ChatClient securedChatClient(
        ChatClient.Builder builder,
        PrivacySecurityChatClientConfigurer securityConfigurer
) {
    return securityConfigurer.configure(builder).build();
}
```

Continue to wrap tool callbacks with `PrivacyToolCallbackFactory` and configure
`tools.disclosures` only for the entity types that a tool needs:

```java
ToolCallback protectedCustomerLookup =
        privacyToolCallbackFactory.wrap(customerLookupToolCallback);
```

```yaml
spring:
  ai:
    privacy:
      tools:
        disclosures:
          customerLookup:
            - CUSTOMER_ID
```

Do not apply both configurers to the same builder. A `ChatClient` without tool
callbacks may continue using the privacy-only configurer. A `ChatClient` with
tool callbacks must use the combined configurer because the starter installs
the secured `ToolCallingManager` application-wide. A privacy-only tool client is
supported only when the application explicitly wires it to a separate raw or
custom manager.

## ToolCallingManager Selection

With normal Spring AI auto-configuration, the starter decorates the single
auto-configured `DefaultToolCallingManager`, publishes the secured decorator as
the primary `ToolCallingManager`, and verifies that Spring selects it. The
starter does not construct a fallback manager. Startup fails when no default
manager exists or more than one default candidate is present.

The secured manager is available to Spring AI chat models and
`ToolCallingAdvisor` auto-configuration without relying on a specific upstream
bean name. The protection still applies whether Spring AI resolver fallback is
enabled or disabled. A tool that was not exposed is rejected before the
delegate can resolve or execute it.

### Custom ToolCallingManager

Custom managers require explicit configuration because the public interface does
not show how an implementation resolves or invokes callbacks. Provide the
boundary explicitly:

```java
@Bean
SpringSecurityToolBoundary springSecurityToolBoundary(
        @Qualifier("customToolCallingManager") ToolCallingManager delegate,
        AuthorizationManager<ToolAuthorizationContext> authorizationManager
) {
    return SpringSecurityToolBoundary.builder(delegate, authorizationManager)
            .build();
}
```

The delegate must execute tool calls using the callbacks supplied in the
secured prompt. Paths that deliberately inject and invoke the raw delegate
instead of the primary secured manager are outside this boundary.

When using `spring-ai-privacy-guardrails-spring-security` directly without the
Spring Boot starter, install both `boundary.toolCallingManager()` and
`boundary.advisor()`. The manager and advisor share one request registry. Both
components are required.

## Tool Search

Spring AI Tool Search indexes tool definitions and exposes a control tool that
lets the model search for a smaller set of business tools during a request. The
Security starter does not add or enable Tool Search, but the integration
supports an application-configured `ToolSearchToolCallingAdvisor` under these
rules:

- Only definition-authorized business tools are added to the index.
- The Tool Search query remains privacy-protected.
- Only Spring AI's control callback, identified by its reserved name and
  request-scoped session marker, may be added after request capture.
- The admitted control callback cannot be replaced.
- A selected business callback must be the same callback captured at the
  original authorization boundary and must have passed definition
  authorization.

Other late callback additions or callback replacements are rejected. Callback
removal and reordering are allowed because they cannot expand the authorized
set. The Tool Search control callback is not a business tool and is therefore
not passed to the application's tool authorization policy.

## Blocking, Reactive, and Asynchronous Context

For a blocking call, the request advisor captures the `Authentication` from the
configured `SecurityContextHolderStrategy` when the protected request enters the
boundary. For streaming calls, a Reactor `SecurityContext` entry is authoritative
when present. The thread-local context is used only when the Reactor subscriber
context contains no `SecurityContext` entry. An existing but empty entry is
rejected instead of falling back to a thread-local identity.

After capture, the library stores `Authentication` in a request-scoped internal
registry and places only an opaque handle in Spring AI tool context. Later tool
execution resolves that handle, so it does not depend on the execution thread's
thread-local or Reactor context.

If the application moves the `ChatClient` invocation itself to another executor
before the request advisor runs, it must propagate Spring Security context to
that executor. For example, blocking and virtual-thread executors can use
Spring Security's `DelegatingSecurityContextExecutorService`. A missing
`Authentication` is rejected when the request carries tool callbacks.

The captured `Authentication` represents the request identity. Authorization is
still evaluated again at execution time, but detecting a permission revocation
during a long-running request requires the application policy to consult current
external state rather than relying only on authorities captured in the object.

Authorization sessions are removed after blocking completion or failure and
after stream completion, failure, or cancellation. A stream that never
terminates and is never cancelled retains its request session until the
application ends that subscription.

## Compatibility

| Component | Supported baseline |
| --- | --- |
| Java | 17 or later |
| Spring AI | `2.0.0` or later in the `2.0.x` line. `2.0.1` is recommended. |
| Spring Boot | `4.0.0` or later in the `4.x` line |
| Spring Security | `7.0.0` or later. Spring Boot `4.1.1` manages version `7.1.1` by default. |

The integration uses Spring AI and Spring Security public interfaces and does
not require reflection or Spring AI private APIs.

This feature authorizes tools. It does not provide identity-specific control
over PII disclosure. It also does not replace ingestion-time protection for
embeddings or VectorStore persistence or secure application-owned execution
paths outside the configured boundary.

# Spring Security Tool Authorization

**English** | [한국어](ko/security.md)

The optional Spring Security integration controls which Spring AI tools the
model can discover and execute for the current principal (`Authentication`).
It can be used on its own or together with the privacy boundary. PII detection
and per-tool original-value disclosure are provided by the privacy boundary.

Use this integration when tool availability depends on the current principal.
Applications that do not need identity-aware tool authorization can continue to
use `PrivacyChatClientConfigurer` without adding Spring Security.

## Guarantees

On the supported path, the integration enforces the following checkpoints.

| Checkpoint | Behavior |
| --- | --- |
| Model exposure | The authorization policy is evaluated before the model receives the available tool definitions. Denied tools are omitted from that list. |
| Model-requested call | A tool that was not exposed is rejected even if the model names it or Spring AI's fallback lookup could otherwise find it. |
| Multiple calls in one response | Every requested tool is authorized before the first callback in that response starts. |
| Callback invocation | Each tool callback is authorized again immediately before invocation. When the privacy boundary is also configured, this check occurs before any allowed original PII is restored. |

When both boundaries are configured, tool authorization and PII disclosure are
controlled by separate policies:

- `AuthorizationManager<ToolAuthorizationContext>` decides whether the current
  principal may discover or execute a tool.
- `ToolDisclosurePolicy`, configured through `tools.disclosures`, decides which
  original PII values an authorized tool may receive, based on their entity types.

A tool must pass authorization before the privacy wrapper can restore an allowed
original value. Authorizing a tool does not grant it every PII type.

## Add the Spring Boot Starter

The optional starter is introduced in `0.3.0`.

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

The Security starter is independent of the base Privacy Guardrails starter. It
uses the `Authentication` established by the application's existing Spring
Security configuration. An `Authentication` must be available when a protected
request containing tool callbacks begins.

Enable the authorization boundary for Security-only use:

```yaml
spring:
  ai:
    privacy:
      security:
        enabled: true
```

To combine tool authorization with PII protection, add the base Privacy
Guardrails starter or an analyzer starter that includes it. Keep all Privacy
Guardrails artifacts on version `0.3.0`, then enable both boundaries:

```yaml
spring:
  ai:
    privacy:
      enabled: true
      security:
        enabled: true
```

PII protection also requires at least one configured analyzer or a `PiiAnalyzer`
bean. Tool authorization alone does not require an analyzer or
`spring.ai.privacy.enabled=true`.

## Define the Authorization Policy

Provide one `AuthorizationManager<ToolAuthorizationContext>` bean to define your
application's tool authorization policy. `AuthorizationManager` is a standard
Spring Security extension interface.

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

`ToolAuthorizationContext` exposes the `ToolDefinition` and the current
authorization phase to the policy. It does not contain tool arguments or request
PII.

For a deliberate allow-all policy, provide the bean explicitly:

```java
@Bean
AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
    return (authentication, context) -> new AuthorizationDecision(true);
}
```

An allow-all policy preserves the boundary checks but does not restrict tools
by principal. It still requires an `Authentication` when a request
containing tool callbacks enters the boundary.

## Configure the ChatClient

For Security-only use, apply `ToolAuthorizationChatClientConfigurer` to each
`ChatClient.Builder` that can carry tool callbacks:

```java
@Bean
ChatClient authorizedToolClient(
        ChatClient.Builder builder,
        ToolAuthorizationChatClientConfigurer authorizationConfigurer
) {
    return authorizationConfigurer.configure(builder).build();
}
```

When privacy protection is also enabled, use
`PrivacySecurityChatClientConfigurer`. It applies both boundaries in the
supported order:

```java
@Bean
ChatClient securedChatClient(
        ChatClient.Builder builder,
        PrivacySecurityChatClientConfigurer privacySecurityConfigurer
) {
    return privacySecurityConfigurer.configure(builder).build();
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

Apply exactly one configuration to a builder: privacy-only, authorization-only,
or combined. When using `PrivacySecurityChatClientConfigurer`, do not also apply
either individual configurer. A `ChatClient` without tool callbacks does not
need authorization configuration. A `ChatClient` with tool callbacks must use
either authorization-only or combined configuration, or its tool calls are
rejected. To keep a separate privacy-only tool path while the Security starter
is enabled, wire that path explicitly to a `ToolCallingManager` outside this
authorization boundary.

## ToolCallingManager Selection

The starter installs an authorization-aware `ToolCallingManager` as the primary
manager for Spring AI chat models and auto-configured tool-calling advisors.
The default setup requires exactly one Spring AI `DefaultToolCallingManager`.
Startup fails when that manager is missing or multiple candidates are present.

Denied tools remain unavailable even when Spring AI resolver fallback is enabled.

### Custom ToolCallingManager

For a custom manager, provide a `SpringSecurityToolBoundary` explicitly:

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
execution prompt. Paths that deliberately inject and invoke the raw delegate
instead of the primary authorization-aware manager are outside this boundary.

When using `spring-ai-privacy-guardrails-spring-security` directly without the
Spring Boot starter, install both `boundary.toolCallingManager()` and
`boundary.toolAuthorizationAdvisor()` together.

## Tool Search

Tool Search is an optional Spring AI feature that lets a model search for the
tools it needs. If your application uses `ToolSearchToolCallingAdvisor`, supply
the authorization boundary's manager explicitly. Its builder creates a separate
manager by default:

```java
@Bean
ToolSearchToolCallingAdvisor toolSearchToolCallingAdvisor(
        SpringSecurityToolBoundary boundary,
        ToolIndex toolIndex
) {
    return ToolSearchToolCallingAdvisor.builder()
            .toolIndex(toolIndex)
            .toolCallingManager(boundary.toolCallingManager())
            .build();
}
```

Register that advisor on a builder configured for authorization. For example,
to combine Tool Search with privacy protection:

```java
@Bean
ChatClient toolSearchClient(
        ChatClient.Builder builder,
        PrivacySecurityChatClientConfigurer privacySecurityConfigurer,
        ToolSearchToolCallingAdvisor toolSearchAdvisor
) {
    return privacySecurityConfigurer.configure(builder)
            .defaultAdvisors(toolSearchAdvisor)
            .build();
}
```

For authorization alone, use `ToolAuthorizationChatClientConfigurer` instead.
The chat model and Tool Search advisor must use the same boundary manager.
The starter wires it into auto-configured chat models. Manually built models
and tool-calling advisors need explicit wiring.

With this setup, only authorized business definitions enter the index and
business tools are authorized again before execution. When privacy protection
is also configured, detected PII in search arguments is tokenized before the
search runs.

If Tool Search uses a raw manager, it can index denied tool definitions before
a later model or execution check rejects the request. Execution denial cannot
undo that earlier disclosure to the index.

During Tool Search, the integration permits Spring AI's control callback and
tool callbacks from the authorized tool set. Unexpected callback additions
or replacements during the request are rejected. The application and its
extensions remain part of the trusted boundary described in the
[Threat Model](threat-model.md).

## Blocking, Reactive, and Asynchronous Context

For a blocking call, the integration captures `Authentication` from the
configured `SecurityContextHolderStrategy` when the protected request begins.
For a streaming call, the Reactor `SecurityContext` is authoritative when
present. The thread-local context is used only when no reactive security context
is present. An explicitly empty reactive context is treated as unauthenticated.

The captured `Authentication` is used throughout the request, including tool
execution on other threads.

If the application moves the `ChatClient` invocation itself to another executor
before the protected request begins, it must propagate Spring Security context to
that executor. For example, blocking and virtual-thread executors can use
Spring Security's `DelegatingSecurityContextExecutorService`. A missing
`Authentication` is rejected when the request carries tool callbacks.

The captured `Authentication` represents the request identity. Authorization is
still evaluated again at execution time, but detecting a permission revocation
during a long-running request requires the application policy to consult current
external state rather than relying only on authorities captured in the object.

The library cleans up request authorization state when a blocking call completes
or fails, or when a stream completes, fails, or is cancelled.

## Compatibility

| Component | Supported baseline |
| --- | --- |
| Java | 17 or later |
| Spring AI | `2.0.0` or later in the `2.0.x` line. `2.0.1` is recommended. |
| Spring Boot | `4.0.0` or later in the `4.x` line |
| Spring Security | `7.0.0` or later in the `7.x` line. Spring Boot `4.1.1` manages version `7.1.1` by default. |

Compatibility checks cover Spring AI `2.0.1` / Boot `4.1.1` / Security `7.1.1`
and the minimum combination of Spring AI `2.0.0` / Boot `4.0.0` / Security
`7.0.0`. These checks do not establish compatibility with every future release.

The integration uses Spring AI and Spring Security public interfaces and does
not require reflection or Spring AI private APIs.

This feature authorizes tools. It does not provide identity-specific control
over PII disclosure. It also does not replace ingestion-time protection for
embeddings or VectorStore persistence or secure application-owned execution
paths outside the configured boundary.

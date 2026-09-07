# Spring Security Tool Authorization

**English** | [한국어](ko/security.md)

The optional Spring Security integration limits which Spring AI tools the model
can see and which tools it may execute for the current principal
(`Authentication`). Tool authorization can be used on its own or together with
privacy protection.

When both are used, Spring Security authorizes tool exposure and execution,
while privacy protection detects PII and controls which original values each
tool may receive.

## Guarantees

The integration checks authorization at the following stages of a protected
tool-calling path.

| Stage | Guarantee |
| --- | --- |
| Before tools are shown to the model | The authorization policy is evaluated, and denied tools are omitted from the list provided to the model. |
| When the model requests a tool | A tool that was not included in the model's list is not executed, even if the model requests it by name. |
| When one response requests multiple tools | Authorization is checked for the complete set of requested tools before execution begins. |
| Immediately before tool execution | Authorization is checked again for each tool. When privacy protection is also enabled, this happens before any allowed original PII is restored. |

Tool authorization and original PII disclosure are configured independently:

- `AuthorizationManager<ToolAuthorizationContext>` decides whether the current
  principal may discover or execute a tool.
- `tools.disclosures` decides which PII entity types an authorized tool may
  receive as original values.

Authorizing a tool does not automatically disclose original PII. An original
value is provided only when the authorization policy allows execution and its
entity type is configured under `tools.disclosures`.

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

The Security starter can be used without the base Privacy Guardrails starter.
Tool authorization uses the `Authentication` established by the application's
existing Spring Security configuration, so it must be available when a request
that uses tools begins.

### Tool Authorization Only

Enable the following property:

```yaml
spring:
  ai:
    privacy:
      security:
        enabled: true
```

This configuration does not require a privacy analyzer or
`spring.ai.privacy.enabled=true`.

### With Privacy Protection

Add the base Privacy Guardrails starter or an analyzer starter that includes
it, and keep all Privacy Guardrails artifacts on version `0.3.0`. Then enable
both features:

```yaml
spring:
  ai:
    privacy:
      enabled: true
      security:
        enabled: true
```

To use privacy protection, configure at least one analyzer: Regex, Presidio,
OpenNLP, or a custom `PiiAnalyzer`.

## Define the Authorization Policy

Register your tool authorization policy as an
`AuthorizationManager<ToolAuthorizationContext>` bean. `AuthorizationManager`
is a standard Spring Security extension interface.

`ToolAuthorizationContext` provides the tool's specification (`ToolDefinition`)
and the authorization phase. It does not contain tool arguments or original
request data.

The following example allows only users with the `ROLE_SUPPORT` authority to
use `customerLookup`. All other tools are denied.

```java
@Bean
AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
    return (authentication, context) -> {
        Authentication currentAuthentication = authentication.get();
        if (currentAuthentication == null || !currentAuthentication.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }

        boolean hasSupportRole = currentAuthentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPPORT"));

        boolean granted = switch (context.toolDefinition().name()) {
            case "customerLookup" -> hasSupportRole;
            default -> false;
        };
        return new AuthorizationDecision(granted);
    };
}
```

Use `context.phase()` if you need different rules for each phase. Its value is
`ToolAuthorizationPhase.DEFINITION` when building the tool list for the model
and `ToolAuthorizationPhase.EXECUTION` when checking permission to execute a
tool.

If the policy returns `null` or a denied result, the integration omits the tool
from the list during the definition phase and rejects the call during the
execution phase.

The following example allows all tools:

```java
@Bean
AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
    return (authentication, context) -> new AuthorizationDecision(true);
}
```

The tool authorization integration captures the request's `Authentication`
before evaluating the policy. This is required even when the policy allows
all tools.

## Configure the ChatClient

With the Security starter's default setup, apply one of the configurations
below to each `ChatClient` that uses tools. Otherwise, its tool calls are
rejected. A `ChatClient` without tools does not need authorization configuration.

### Apply Tool Authorization Only

To use tool authorization on its own, configure the `ChatClient` as follows:

```java
@Bean
ChatClient authorizedToolClient(
        ChatClient.Builder builder,
        ToolAuthorizationChatClientConfigurer authorizationConfigurer
) {
    return authorizationConfigurer.configure(builder).build();
}
```

### Combine with Privacy Protection

When privacy protection is also enabled, use
`PrivacySecurityChatClientConfigurer`. It applies tool authorization and privacy
protection together:

```java
@Bean
ChatClient securedChatClient(
        ChatClient.Builder builder,
        PrivacySecurityChatClientConfigurer privacySecurityConfigurer
) {
    return privacySecurityConfigurer.configure(builder).build();
}
```

After applying `PrivacySecurityChatClientConfigurer`, do not also apply the
privacy-only or authorization-only configurer to the same builder.

When using privacy protection, wrap tool callbacks with
`PrivacyToolCallbackFactory`. Configure `tools.disclosures` only for the PII
types that should be passed to the tool as original values:

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

## ToolCallingManager Selection

The starter installs an authorization-aware `ToolCallingManager` as the primary
manager for Spring AI chat models and auto-configured tool-calling advisors.
The default setup requires exactly one Spring AI `DefaultToolCallingManager`.
Startup fails when that manager is missing or multiple candidates are present.

Denied tools remain unavailable even when Spring AI resolver fallback is enabled.

To keep a separate privacy-only tool path while the Security starter is
enabled, explicitly wire that path to a `ToolCallingManager` without tool
authorization. This integration's tool authorization checks do not apply to
that path.

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

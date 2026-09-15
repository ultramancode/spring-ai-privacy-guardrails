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

Register an `AuthorizationManager<ToolAuthorizationContext>` bean as shown
below. The starter then provides a `ToolAuthorizationChatClientFactory` for
creating clients with tool authorization. No privacy analyzer is required.

### With Privacy Protection

Add the base Privacy Guardrails starter or an analyzer starter that includes
it, and keep all Privacy Guardrails artifacts on version `0.3.0`. Configure
Regex, Presidio, OpenNLP, or a custom `PiiAnalyzer` as described in
[Getting Started](getting-started.md). When privacy and authorization are both
configured, the starter also provides a `PrivacySecurityChatClientFactory`.

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

Create each client that needs tool authorization with one of the factories
below. The starter leaves other clients and the shared `ChatModel` unchanged;
adding the dependency or policy bean does not protect them automatically.

### Apply Tool Authorization Only

To use tool authorization on its own, configure the `ChatClient` as follows:

```java
@Bean
ChatClient authorizedToolClient(
        ChatModel chatModel,
        ToolAuthorizationChatClientFactory authorizationFactory
) {
    return authorizationFactory.builder(chatModel).build();
}
```

### Combine with Privacy Protection

When privacy protection is also configured, use
`PrivacySecurityChatClientFactory`. It applies tool authorization and privacy
protection together:

```java
@Bean
ChatClient securedChatClient(
        ChatModel chatModel,
        PrivacySecurityChatClientFactory privacySecurityFactory
) {
    return privacySecurityFactory.builder(chatModel).build();
}
```

The combined factory already configures privacy protection. Do not apply
`PrivacyChatClientConfigurer` again to its builders.

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

The factories configure their clients' tool-calling advisors with an
authorization-aware manager. The shared `ChatModel` and Spring AI's existing
`ToolCallingManager` bean keep their original configuration.

When a tool policy bean is present, the default setup requires exactly one
Spring AI `DefaultToolCallingManager` to delegate execution to. Startup fails
if that manager is missing or multiple candidates are present, unless an
explicit `SpringSecurityToolBoundary` is provided.

Denied tools remain unavailable even when Spring AI resolver fallback is enabled.

A privacy-only client can continue to use `PrivacyChatClientConfigurer`.
It does not acquire tool authorization merely because the Security starter is
present.

Keep Spring AI's automatic tool-advisor registration enabled for factory-created
clients that use tools (`spring.ai.chat.client.tool-calling.enabled`, enabled
by default). Do not disable it per request with
`AdvisorParams.toolCallingAdvisorAutoRegister(false)`. To customize tool
calling, pass a `ToolCallingAdvisor.Builder<?>` to `factory.builder(chatModel,
toolAdvisorBuilder)`. Registering a separate tool advisor with
`defaultAdvisors(...)` or request-level `advisors(...)` is rejected.

Custom tool-advisor builders must honor Spring AI's `copy()`,
`toolCallingManager(...)`, and `build()` contracts. Incompatible advisor orders
and tool advisors implementing `PriorityOrdered` are rejected.

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
execution prompt. The starter's factories use this explicit boundary. Calling
the raw delegate directly is outside its protection scope.

When using `spring-ai-privacy-guardrails-spring-security` directly without the
Spring Boot starter, register `boundary.toolAuthorizationAdvisor()` and
`boundary.toolDefinitionAuthorizationAdvisor()` on the client, and configure
its tool-calling advisor with `boundary.toolCallingManager()`. Advisors that
change tools must run before definition authorization. When composing privacy
advisors manually, definition authorization must run after the privacy model
boundary. The starter factories handle this setup for you.

## Tool Search

Tool Search is an optional Spring AI feature that lets a model search for the
tools it needs. Add `org.springframework.ai:spring-ai-tool-search-advisor`, using
the version managed by your application's Spring AI BOM. Pass its advisor
builder to the factory, which supplies the authorization-aware manager.
For example, to combine Tool Search with privacy
protection:

```java
@Bean
ChatClient toolSearchClient(
        ChatModel chatModel,
        PrivacySecurityChatClientFactory privacySecurityFactory,
        ToolIndex toolIndex
) {
    return privacySecurityFactory.builder(chatModel,
                    ToolSearchToolCallingAdvisor.builder().toolIndex(toolIndex))
            .build();
}
```

For authorization alone, use the same overload on
`ToolAuthorizationChatClientFactory`. Register your tools on the returned
builder or on each request; privacy-protected tools must still be wrapped with
`PrivacyToolCallbackFactory`.

Tool Search also requires a conversation ID. Pass your application's
`conversationId` on each request, for example:

```java
String response = toolSearchClient.prompt()
        .user("Find customer CUST-123456.")
        .tools(protectedCustomerLookup)
        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
        .call()
        .content();
```

With this setup, only authorized business definitions enter the index and
business tools are authorized again before execution. When privacy protection
is also configured, detected PII in search arguments is tokenized before the
search runs.

Spring AI's Tool Search control callback is permitted separately from the
business-tool policy. Unexpected callback additions or replacements during the
request are rejected. Custom Tool Search wiring that uses a raw manager can
expose denied definitions to the index before later checks run. See the
[Threat Model](threat-model.md)
for the application's responsibilities.

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

| Component | Supported versions |
| --- | --- |
| Java | 17 or later |
| Spring AI | `2.0.x` |
| Spring Boot | `4.x` |
| Spring Security | `7.x` |

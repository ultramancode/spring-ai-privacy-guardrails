---
description: >-
  Apply Spring Security authorization to tools exposed to and called by Spring AI
  models, either on its own or together with privacy protection.
---

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
| Before model exposure | The authorization policy is evaluated, and denied tools are omitted from the list provided to the model. |
| Model tool request | A tool that was not included in the model's list is not executed, even if the model requests it by name. |
| Multiple tools in one response | Authorization is checked for the complete set of requested tools before execution begins. |
| Immediately before execution | Authorization is checked again for each tool. When privacy protection is also enabled, this happens before any allowed original PII is restored. |

When combining authorization with privacy protection, configure tool permissions
and original PII disclosure separately:

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

Register an `AuthorizationManager<ToolAuthorizationContext>` bean as shown in
[Define the Authorization Policy](#define-the-authorization-policy).
The starter then provides a `ToolAuthorizationChatClientFactory` for
creating clients with tool authorization. No privacy analyzer is required.

### With Privacy Protection

Add the base Privacy Guardrails starter or an analyzer starter that includes
it, and use the same version for all Privacy Guardrails modules. Configure
Regex, Presidio, OpenNLP, or a custom `PiiAnalyzer` as described in
[Getting Started](getting-started.md). When privacy and authorization are both
configured, the starter also provides a `PrivacySecurityChatClientFactory`.

## Define the Authorization Policy

Register your tool authorization policy as an
`AuthorizationManager<ToolAuthorizationContext>` bean. `AuthorizationManager`
is a standard Spring Security extension interface.

The policy receives user information through `Authentication` and the tool's
specification (`ToolDefinition`) and authorization phase through
`ToolAuthorizationContext`. Tool argument values and the original request text
are not passed to the policy. For example, it can check whether the user may
use `customerLookup`, but it does not receive the actual `customerId` being
looked up. Implement checks that depend on argument values, such as permission
to view a particular customer, in the tool or application service.

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

After adding the dependency and registering the authorization policy bean,
create a `ChatClient` through a factory as shown below. Choose tool authorization
alone or combine it with privacy protection.

A client that only needs privacy protection can use `PrivacyChatClientConfigurer`.

In the examples below, `customerLookupToolCallback` is a customer lookup tool
registered as a bean by the application. Registering it with `defaultTools(...)`
makes it available to requests through that client.

### Apply Tool Authorization Only

To use tool authorization on its own, configure the `ChatClient` as follows:

```java
@Bean
ChatClient authorizedToolClient(
        ChatModel chatModel,
        ToolAuthorizationChatClientFactory authorizationFactory,
        ToolCallback customerLookupToolCallback
) {
    return authorizationFactory.builder(chatModel)
            .defaultTools(customerLookupToolCallback)
            .build();
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
        PrivacySecurityChatClientFactory privacySecurityFactory,
        PrivacyToolCallbackFactory privacyToolCallbackFactory,
        ToolCallback customerLookupToolCallback
) {
    ToolCallback protectedCustomerLookup =
            privacyToolCallbackFactory.wrap(customerLookupToolCallback);

    return privacySecurityFactory.builder(chatModel)
            .defaultTools(protectedCustomerLookup)
            .build();
}
```

The combined factory already configures privacy protection. Do not apply
`PrivacyChatClientConfigurer` again to its builders.

As shown above, wrap tools with `PrivacyToolCallbackFactory` before registering
them for privacy protection. Configure the PII types to disclose as original
values under `tools.disclosures`. This example allows only the original customer
ID (`CUSTOMER_ID`) to reach `customerLookup`:

```yaml
spring:
  ai:
    privacy:
      tools:
        disclosures:
          customerLookup:
            - CUSTOMER_ID
```

## Tool Advisor Configuration

A tool-calling advisor executes tools requested by the model and passes their
results back to it. `ToolCallingAdvisor` and the search-enabled
`ToolSearchToolCallingAdvisor` serve this role and implement Spring AI's
`ToolAdvisor` interface.

Factory-created clients can also use ordinary advisors, such as conversation
memory or RAG advisors, registered through `defaultAdvisors(...)` or request-level
`advisors(...)`. When combining them with privacy protection, follow the
[advisor ordering guidance](configuration.md#custom-advisor-order) so added data
passes through privacy inspection.

The factory configures the tool-calling advisor with authorization checks.
For clients that use tools:

- Keep Spring AI's automatic tool-advisor registration enabled.
  `spring.ai.chat.client.tool-calling.enabled` is enabled by default. Do not
  disable it per request with `AdvisorParams.toolCallingAdvisorAutoRegister(false)`.
- Registering a separate tool-calling advisor (a `ToolAdvisor` implementation)
  with `defaultAdvisors(...)` or request-level `advisors(...)` is rejected.
  To change tool calling, pass its builder to the factory as described below.

### Custom Tool Advisor

Pass the builder for your chosen tool-calling advisor to
`factory.builder(chatModel, toolAdvisorBuilder)`. The factory connects tool
authorization checks when configuring the client.

The following example sets the tool-calling advisor's execution order to `100`
on a client using tool authorization alone:

```java
@Bean
ChatClient customToolAdvisorClient(
        ChatModel chatModel,
        ToolAuthorizationChatClientFactory authorizationFactory,
        ToolCallback customerLookupToolCallback
) {
    ToolCallingAdvisor.Builder<?> toolAdvisorBuilder =
            ToolCallingAdvisor.builder().advisorOrder(100);

    return authorizationFactory.builder(chatModel, toolAdvisorBuilder)
            .defaultTools(customerLookupToolCallback)
            .build();
}
```

`100` is an example value; choose an execution order that fits the other advisors
in your application. Orders incompatible with the protection setup are rejected.

The factory supports `ToolCallingAdvisor.Builder<?>` and its subclasses.
See the [Tool Search example](#tool-search) for passing a tool-search advisor builder.

**Requirements when implementing an advisor or builder**

The factory copies the supplied builder, sets an authorization-aware
`ToolCallingManager` on the copy, and builds the advisor. Custom advisors and
builders must support this process:

- A builder returned by `copy()` must still create the same type of advisor and
  retain the existing settings.
- The advisor produced by `build()` must use the `ToolCallingManager` set by the
  factory through `toolCallingManager(...)` so tool authorization checks apply.
- Do not implement `PriorityOrdered` on a tool-calling advisor. It could start
  tool calling before authorization checks, so the factory rejects it. Set the
  execution order through the builder's `advisorOrder(...)` instead.

The following minimal example extends `ToolCallingAdvisor`. It uses the default
tool-calling behavior and implements builder copying and advisor creation.

```java
final class CustomToolCallingAdvisor extends ToolCallingAdvisor {

    private CustomToolCallingAdvisor(ToolCallingManager manager,
            ToolExecutionEligibilityChecker checker, int order, boolean history) {
        super(manager, checker, order, history);
    }

    static final class Builder extends ToolCallingAdvisor.Builder<Builder> {

        @Override
        public Builder copy() {
            return (Builder) super.copy();
        }

        @Override
        protected Builder newCopy() {
            return new Builder();
        }

        @Override
        public CustomToolCallingAdvisor build() {
            ToolCallingManager manager = getToolCallingManager();
            return new CustomToolCallingAdvisor(
                    manager,
                    getToolExecutionEligibilityChecker(),
                    getAdvisorOrder(),
                    isConversationHistoryEnabled()
            );
        }
    }
}
```

`super.copy()` calls `copy()` in the parent class, `ToolCallingAdvisor.Builder`.
The three methods have the following roles:

| Method | Role in this example |
| --- | --- |
| `copy()` | Delegates copying to the parent's `copy()` and returns the result as this example's `Builder` type. |
| `newCopy()` | Called by the parent's `copy()`. Creates the new `Builder` that will receive the copied settings. |
| `build()` | Reads the `ToolCallingManager` set by the factory through `getToolCallingManager()` and passes it, along with the remaining settings, to the `CustomToolCallingAdvisor` constructor. |

The factory sets the authorization-aware `ToolCallingManager` on the builder.
Your `build()` implementation reads it through `getToolCallingManager()` and
passes it to the advisor constructor. The `manager` variable above holds this value.

The constructor passes that same `ToolCallingManager` to the parent
`ToolCallingAdvisor` as the first argument of `super(manager, checker, order, history)`.
The remaining three arguments configure tool execution eligibility, advisor
execution order, and conversation history. The parent advisor uses the supplied
`ToolCallingManager` to call tools.

The parent's `copy()` copies the `ToolCallingManager`, execution eligibility checker,
execution order, and conversation history setting into the new builder.

If your builder adds configuration fields, assign their values to the copy
returned by `super.copy()` inside your `copy()` implementation, then return that
copy. As shown above, `newCopy()` is responsible for creating the new builder.

Pass this builder to the factory when creating the client:

```java
ChatClient client = authorizationFactory
        .builder(chatModel, new CustomToolCallingAdvisor.Builder().advisorOrder(100))
        .defaultTools(customerLookupToolCallback)
        .build();
```

## ToolCallingManager Selection

`ToolCallingManager` handles the actual execution of tools. When a tool policy
bean is present, the starter's default setup uses Spring AI's
`DefaultToolCallingManager` bean.

The default setup fails at startup if that bean is missing or multiple candidates
are present. To select a `ToolCallingManager` explicitly, provide a
`SpringSecurityToolBoundary` bean as shown below.

### Custom ToolCallingManager

A `ToolCallback` is an object that invokes a tool. When delegating execution, the
library wraps each application tool callback with a permission recheck and places
the wrapped callbacks in the tool options of the `Prompt` passed to your custom
`ToolCallingManager`. Each wrapper checks permission before invoking the callback
it wraps.

When combining authorization with privacy protection, the library automatically
adds this permission-checking wrapper around the callback wrapped by
`PrivacyToolCallbackFactory`. Your custom `ToolCallingManager` must invoke the
outermost callback supplied in the `Prompt`.

This example delegates execution to Spring AI's default implementation,
`DefaultToolCallingManager`. Passing the received `Prompt` and model response
unchanged lets this implementation use the callbacks in the `Prompt`, preserving
the permission recheck.

```java
@Bean
SpringSecurityToolBoundary springSecurityToolBoundary(
        ToolCallingManager delegate,
        AuthorizationManager<ToolAuthorizationContext> authorizationManager
) {
    ToolCallingManager customManager = new ToolCallingManager() {
        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
            return delegate.resolveToolDefinitions(options);
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
            return delegate.executeToolCalls(prompt, response);
        }
    };

    return SpringSecurityToolBoundary.builder(customManager, authorizationManager)
            .build();
}
```

The example receives Spring AI's `DefaultToolCallingManager` bean through the
`delegate` parameter, declared as `ToolCallingManager`. If you already have a
custom `ToolCallingManager`, pass that object in place of `customManager`.

In this example, `delegate.executeToolCalls(prompt, response)` finds the callbacks
and passes along `ToolContext`, which carries additional data needed for tool
execution. The delegating code does not need to implement callback selection or
context passing separately.

Any custom `ToolCallingManager` you connect must execute tools through the callbacks
in the supplied `Prompt`. These callbacks recheck permission immediately before
tool execution, so calling the original tool separately skips this check. Pass the
tool context as well to preserve data needed for execution, including privacy
protection when enabled.

The starter's factories use the `SpringSecurityToolBoundary` bean shown above
when configuring clients. Create a client as shown in
[Configure the ChatClient](#configure-the-chatclient) and call tools through that
client.

## Without the Spring Boot Starter

When using `spring-ai-privacy-guardrails-spring-security` directly, create a
`SpringSecurityToolBoundary` and pass it to `ToolAuthorizationChatClientFactory`.
The factory configures the client to capture the request's authentication and
check permissions before exposing tools to the model and before executing tool calls.

The following example creates a client with **tool authorization only**, using
your model, authorization policy, and customer lookup tool.

```java
ChatClient createAuthorizedClient(
        ChatModel chatModel,
        AuthorizationManager<ToolAuthorizationContext> authorizationManager,
        ToolCallback customerLookupToolCallback
) {
    SpringSecurityToolBoundary boundary = SpringSecurityToolBoundary.builder(
            ToolCallingManager.builder().build(), authorizationManager
    ).build();

    ToolAuthorizationChatClientFactory authorizationFactory =
            new ToolAuthorizationChatClientFactory(boundary);

    return authorizationFactory.builder(chatModel)
            .defaultTools(customerLookupToolCallback)
            .build();
}
```

The factory places the tool-calling advisor before the common model request
boundary. Do not register a separate tool advisor; pass its builder to the
factory when customizing the tool loop.

Callback additions and replacements performed by application advisors must
finish before `boundary.toolAuthorizationAdvisor()` captures the request's tool
list.

To include privacy protection without Spring Boot, create a
`PrivacyChatClientConfigurer` from the privacy integration module and pass it
with this authorization factory to `PrivacySecurityChatClientFactory`.

Both factories support `builderWithBoundary(model, additionalConfigurer)` and
`builder(model, toolAdvisorBuilder, additionalConfigurer)` for composing extra
features, such as final model-request inspection, into the same boundary.
Do not apply another boundary configurer separately to the returned builder.

## Tool Search

Tool Search is an optional Spring AI feature that lets a model search for the
tools it needs.

Add `org.springframework.ai:spring-ai-tool-search-advisor`, using
the version managed by your application's Spring AI BOM.

The following example combines tool authorization, Tool Search, and privacy
protection by passing the tool-search advisor's builder to the factory.
`ToolIndex` is the tool search index provided as a bean by the application.

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

For tool authorization alone, replace the injected parameter
`PrivacySecurityChatClientFactory privacySecurityFactory` in the code directly
above with `ToolAuthorizationChatClientFactory authorizationFactory`.
Update the return statement to call `authorizationFactory.builder(...)`.
Pass the same model and tool-search advisor builder to `builder(...)`.

Register tools with `defaultTools(...)` when building the client or with
`tools(...)` on each request. When also applying privacy protection, register
callbacks wrapped by `PrivacyToolCallbackFactory.wrap(...)`.

Tool Search also requires a conversation ID on each request. The following example
registers a tool and passes the application's `conversationId` to a client that
also uses privacy protection:

```java
ToolCallback protectedCustomerLookup =
        privacyToolCallbackFactory.wrap(customerLookupToolCallback);

String response = toolSearchClient.prompt()
        .user("Find customer CUST-123456.")
        .tools(protectedCustomerLookup)
        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
        .call()
        .content();
```

With this setup, only authorized tools registered for the request are searchable,
and permission is checked again immediately before a selected tool executes.
When privacy protection is also used, detected PII in the search query is
replaced with **opaque tokens** before the search runs. These replacement
strings do not directly reveal the original values.

The library allows the tool that performs the search; the application's
authorization policy applies to the tools being searched. Tool replacements
and unsupported additions during a request are rejected before execution.

If you configure Tool Search directly with a `ToolCallingManager` that does not
apply authorization, denied tools may also become searchable. See the
[Threat Model](threat-model.md) for considerations when configuring this yourself.

## Blocking, Reactive, and Asynchronous Context

When a tool runs on a different thread during a request, its authorization
policy still receives the user's `Authentication` obtained at the start of
that request.

- **Blocking calls:** Authentication is obtained from the Spring Security
  context when the request begins.
- **Streaming:** The Reactor security context takes precedence. The calling
  thread's authentication is used only when no Reactor security context is
  present. A Reactor security context that is registered but empty causes the
  request to be rejected.
- **Asynchronous calls:** If the `ChatClient` invocation itself starts on another
  thread, propagate Spring Security authentication to it. Use
  `DelegatingSecurityContextExecutorService` for the executor running the task
  to propagate authentication. The same requirement applies to virtual threads.

A request with registered tools is rejected if it has no `Authentication`.

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

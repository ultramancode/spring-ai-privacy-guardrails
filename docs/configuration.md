---
description: >-
  Configure dependencies, shared services and protection policies for each ChatClient.
---

# Configuration

**English** | [한국어](ko/configuration.md)

Use `GuardrailsChatClientFactory` to select privacy, content inspection and tool
authorization for each client. When privacy is selected, it protects tools registered
through Spring AI, including request-specific and MCP tools.

## Dependencies and components

Add the client starter alongside your Spring AI model integration:

```gradle
implementation "io.github.ultramancode:spring-ai-privacy-guardrails-chat-client-spring-boot-starter:0.4.0"
```

Use the same version for all Guardrails modules. This starter includes the privacy
and inspection integrations, but does not include Spring Security, ONNX Runtime,
Presidio or an inspection backend.

For PII detection with an additional analyzer, add the [Presidio](privacy.md#presidio-analyzer)
or [OpenNLP](privacy.md#opennlp-analyzer) starter.

| Selected feature | Required component | Setup |
| --- | --- | --- |
| Privacy | `PrivacyService` | Configure [an analyzer](privacy.md). |
| Inspection | `InspectionService` | Register a `ContentInspector` or your own service. See [content inspection](inspection.md). |
| Tool authorization | Authorization integration | Add the [Security starter](security.md) and an `AuthorizationManager<ToolAuthorizationContext>` bean. |

Enable each feature in `GuardrailsPolicy` after configuring its required components.

## Policy per client

Select features, output handling and tool disclosure in `GuardrailsPolicy`.
Configure analyzers through their starter properties. Supply model paths and
connection settings when creating HTTP or ONNX inspector beans.

### Start with One Feature

Select privacy protection for a client in an application `@Configuration` class:

```java
@Bean
ChatClient chatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
    GuardrailsPolicy policy = GuardrailsPolicy.builder()
            .privacy()
            .build();
    return guardrailsChatClientFactory.builder(chatModel, policy)
            .build();
}
```

For a service call, see the [client example](getting-started.md#call-and-check). For inspection alone,
use `GuardrailsPolicy.builder().inspection().build()`. For authorization alone, use
`GuardrailsPolicy.builder().toolAuthorization(true).build()`. See [inspection setup](inspection.md#set-up-input-inspection)
or [authorization setup](security.md#add-the-spring-boot-starter) for their required components.

### Combine Features and Output Policies

This `GuardrailsConfiguration` combines privacy, content inspection and tool authorization.
The example uses the application's `sendEmailToolCallback` and `mcpToolCallbackProvider` beans.

```java
@Configuration(proxyBeanMethods = false)
public class GuardrailsConfiguration {
    @Bean
    ToolDisclosurePolicy toolDisclosurePolicy() {
        return ToolDisclosurePolicy.allowEntityTypesByToolName(
                Map.of("sendEmail", List.of("EMAIL_ADDRESS")));
    }

    @Bean
    GuardrailsPolicy guardrailsPolicy(ToolDisclosurePolicy toolDisclosurePolicy) {
        GuardrailsPolicy.Privacy privacySettings = GuardrailsPolicy.Privacy.builder()
                .toolDisclosurePolicy(toolDisclosurePolicy)
                .outputProtection().redact()
                .build();
        GuardrailsPolicy.Inspection inspectionSettings = GuardrailsPolicy.Inspection.builder()
                .finalOutputInspectionEnabled(true)
                .build();
        return GuardrailsPolicy.builder()
                .privacy(privacySettings)
                .inspection(inspectionSettings)
                .toolAuthorization(true)
                .build();
    }

    @Bean
    ChatClient chatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory,
            GuardrailsPolicy guardrailsPolicy, ToolCallback sendEmailToolCallback,
            ToolCallbackProvider mcpToolCallbackProvider) {
        return guardrailsChatClientFactory.builder(chatModel, guardrailsPolicy)
                .defaultTools(sendEmailToolCallback, mcpToolCallbackProvider)
                .build();
    }
}
```

This configuration discloses email addresses only to `sendEmail`, redacts PII from final output,
and inspects both model requests and final responses. Tool access follows the application's authorization policy.

For application logic, use a [disclosure policy class](privacy.md#custom-tool-disclosure-policy)
or an [authorization manager class](security.md#define-the-authorization-policy).

Register raw callbacks, objects with annotated tools, or providers. Do not wrap them
with `PrivacyToolCallbackFactory` before passing them to this factory.

Providers are resolved for each request, so later MCP tool-list changes are included. Tools passed
to `chatClient.prompt().tools(...)` receive the same protection.

Unlisted tool/entity combinations keep opaque tokens instead of receiving original PII.

Tool execution permission and original-value disclosure are separate decisions.

Selecting `privacy()` protects model requests and tools. Selecting `inspection()`
checks model requests. Final output protection and inspection are separate opt-in settings.
For output-only inspection, select:

```java
.inspection(GuardrailsPolicy.Inspection.builder()
        .modelRequestInspectionEnabled(false)
        .finalOutputInspectionEnabled(true)
        .build())
```

The settings builders start with defaults. Passing a settings object to `privacy(...)`
or `inspection(...)` replaces that feature's settings. Call `mutate()` on the settings
object to change selected values while preserving the others.

## Reuse policies

Inject the shared `guardrailsPolicy` bean to reuse it across clients. Call `mutate()`
to create a builder initialized with its settings. Changing this builder leaves the original
policy unchanged.

To keep the same features without final-output protection or inspection:

```java
GuardrailsPolicy policy = guardrailsPolicy.mutate()
        .privacy(guardrailsPolicy.privacy().mutate().outputProtection().disable().build())
        .inspection(guardrailsPolicy.inspection().mutate().finalOutputInspectionEnabled(false).build())
        .build();

ChatClient chatClient = guardrailsChatClientFactory.builder(chatModel, policy)
        .build();
```

To use privacy protection without content inspection or tool authorization:

```java
GuardrailsPolicy policy = guardrailsPolicy.mutate()
        .inspection(false)
        .toolAuthorization(false)
        .build();

ChatClient chatClient = guardrailsChatClientFactory.builder(chatModel, policy)
        .build();
```

`privacy(false)` and `inspection(false)` remove the entire feature.
A policy must select at least one feature.

`chatClientBuilder.clone()` and `chatClient.mutate()` preserve the configured protection.
To change protection, derive a policy and create a new client through the factory.

## Default policy in Spring Boot

Register a `GuardrailsPolicy` bean if clients should share a default:

```java
@Bean
GuardrailsPolicy guardrailsPolicy() {
    return GuardrailsPolicy.builder()
            .privacy()
            .build();
}

@Bean
ChatClient chatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
    return guardrailsChatClientFactory.builder(chatModel)
            .build();
}
```

`builder(chatModel)` uses the factory's default policy. `builder(chatModel, policy)`
uses only the policy passed to that call. If the factory has no default policy,
pass one explicitly.

Use `ChatClientBuilderCustomizer` beans to set shared client defaults.
The factory applies `ChatClientCustomizer` beans first, followed by
`ChatClientBuilderCustomizer` beans. Within each type, Spring bean ordering is respected.

The starter provides `GuardrailsChatClientFactory` and a configured `GuardrailsChatClientFactory.Builder`.
Inject the builder to customize the starter's service connections and defaults.
See [separate inspection services](inspection.md#choose-separate-input-and-output-services)
for input and output service settings, or [Without Spring Boot](#without-spring-boot) to construct a factory directly.

## Advisor configuration

Supply a tool advisor builder to customize its order or use Tool Search:

```java
ChatClient chatClient = guardrailsChatClientFactory.builder(chatModel, guardrailsPolicy,
        ToolCallingAdvisor.builder().advisorOrder(100))
    .defaultTools(sendEmailToolCallback)
    .build();
```

The factory copies the builder and preserves its advisor type, including Tool Search.
Do not register another tool advisor separately on the client.

Register memory, RAG and other ordinary advisors through `defaultAdvisors(...)`
or request-specific `advisors(...)`. Data added or changed outside the model-request
protection boundary may remain unprotected.

## Without Spring Boot

Depend on `spring-ai-privacy-guardrails-chat-client` and provide the services:

```java
GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder()
        .privacyService(privacyService)
        .inputInspectionService(inspectionService)
        .defaultPolicy(GuardrailsPolicy.builder()
                .privacy()
                .inspection()
                .build())
        .build();
```

Use `outputInspectionService(...)` to supply a service for final output inspection.
Select the inspection directions in the policy. See [content inspection](inspection.md#choose-separate-input-and-output-services)
for details.

For authorization, add the Security module, construct its
`SpringSecurityToolBoundary` and `ToolAuthorizationChatClientFactory`, and pass
`.toolAuthorization(authorizationFactory::prepareToolAuthorization)` to the factory builder.

Configure observations with `observationRegistry(...)` and client customization
with `clientCustomizer(...)` on `GuardrailsChatClientFactory.builder()`.

## Execution and diagnostics

Before a model call, the factory runs privacy processing, tool-definition
authorization and content inspection, in that order, for the selected features.
Tool authorization checks execution permission before restoring allowed original
values. With privacy selected, tool results are protected before reaching the model or application.
Content inspection does not inspect newly generated tool arguments immediately
before execution.

Privacy output protection and output inspection buffer streaming responses until
completion. This delays delivery. Set `privacy.responseLimits(...)` and
`inspection.maxOutputFrames(...)` / `outputStreamTimeout(...)` in the policy together
with the [inspection limits](inspection.md#scope-and-limits). Collection errors, timeouts
and cancellation release no buffered output. Blocking final output cannot undo
side effects of tools that have already run.

Use `InspectionObserver` to record inspection reports and failures. Its callbacks
also receive the inspection phase and call or stream mode. See
[observability](inspection.md#observability).

Direct calls to `ChatModel` bypass this client. Detection coverage depends on the
configured analyzers and inspectors. See [scope and limits](inspection.md#scope-and-limits)
and the [threat model](threat-model.md).

## Advanced manual assembly

To configure features directly on a Spring AI builder, wrap tools with
`PrivacyToolCallbackFactory` and configure privacy with `PrivacyChatClientConfigurer`.
Use `ModelRequestBoundaryConfigurer.compose(...)` to combine multiple feature configurers
and apply them once to the builder.

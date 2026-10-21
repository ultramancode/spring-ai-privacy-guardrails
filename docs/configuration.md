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
implementation "io.github.ultramancode:spring-ai-privacy-guardrails-chat-client-spring-boot-starter:0.4.0-SNAPSHOT"
```

Use the same version for all Guardrails modules. This starter includes the privacy
and inspection integrations, but does not include Spring Security, ONNX Runtime,
Presidio or an inspection backend.

For PII detection with an additional analyzer, add the [Presidio](getting-started.md#5-use-presidio-for-pii-detection)
or [OpenNLP](getting-started.md#6-use-opennlp-for-jvm-local-detection) starter.

| Selected feature | Required component | Setup |
| --- | --- | --- |
| Privacy | `PrivacyService` | Configure [an analyzer](privacy.md). |
| Inspection | `InspectionService` | Register a `ContentInspector` or your own service. See [content inspection](inspection.md). |
| Tool authorization | Authorization integration | Add the [Security starter](security.md) and an `AuthorizationManager<ToolAuthorizationContext>` bean. |

Providing a component alone does not enable its feature. A selected feature with
missing components causes an error when creating the client builder.

## Policy per client

Select features, output handling and tool disclosure in `GuardrailsPolicy`.
Configure analyzers through their starter properties. Supply model paths and
connection settings when creating HTTP or ONNX inspector beans.

```java
GuardrailsPolicy.Privacy privacyPolicy = GuardrailsPolicy.Privacy.builder()
        .toolDisclosurePolicy(ToolDisclosurePolicy.allowEntityTypesByToolName(
                Map.of("customerLookup", List.of("CUSTOMER_ID"))))
        .outputProtection().redact()
        .build();
GuardrailsPolicy.Inspection inspectionPolicy = GuardrailsPolicy.Inspection.builder()
        .finalOutputInspectionEnabled(true)
        .build();

GuardrailsPolicy supportPolicy = GuardrailsPolicy.builder()
        .privacy(privacyPolicy)
        .inspection(inspectionPolicy)
        .toolAuthorization(true)
        .build();

ChatClient chatClient = guardrails.builder(chatModel, supportPolicy)
        .defaultTools(customerLookup, mcpTools)
        .build();
```

This example requires all three components listed above. Privacy protects model
requests and tools. It discloses the original customer ID only to `customerLookup`
and redacts detected PII from final output. Inspection checks every model request
and the final output. Tool authorization uses the application's current authentication
and authorization policy.

Register raw callbacks, objects with annotated tools, or providers. Do not wrap them
with `PrivacyToolCallbackFactory` before passing them to this factory. Providers are
resolved for each request, so later MCP tool-list changes are included. Tools passed
to `chatClient.prompt().tools(...)` receive the same protection. Unlisted tool/entity
combinations keep opaque tokens instead of receiving original PII.

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

Callback overloads offer an inline alternative, such as
`privacy(privacy -> privacy.outputProtection().tokenize())`.

## Reuse policies

Reuse policies across clients. To change individual settings, call `mutate()` to
create a new builder initialized with the policy's settings. Changing this builder
leaves the original policy unchanged.

```java
GuardrailsPolicy inputPolicy = supportPolicy.mutate()
        .privacy(supportPolicy.privacy().mutate().outputProtection().disable().build())
        .inspection(supportPolicy.inspection().mutate().finalOutputInspectionEnabled(false).build())
        .build();

GuardrailsPolicy privacyOnly = supportPolicy.mutate()
        .inspection(false)
        .toolAuthorization(false)
        .build();

ChatClient inputClient = guardrails.builder(chatModel, inputPolicy).build();
ChatClient privacyClient = guardrails.builder(chatModel, privacyOnly).build();
```

`privacy(false)` and `inspection(false)` remove the entire feature.
A policy must select at least one feature.

`chatClientBuilder.clone()` and `chatClient.mutate()` preserve the configured protection.
To change protection, derive a policy and create a new client through the factory.

## Default policy in Spring Boot

Register a `GuardrailsPolicy` bean if clients should share a default:

```java
@Bean
GuardrailsPolicy defaultGuardrailsPolicy() {
    return GuardrailsPolicy.builder().privacy().build();
}

@Bean
ChatClient chatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
    return guardrailsChatClientFactory.builder(chatModel).build();
}
```

`builder(chatModel)` uses the factory's default policy. `builder(chatModel, policy)`
uses only the policy passed to that call. If the factory has no default policy,
pass one explicitly.

Use `ChatClientBuilderCustomizer` beans to set shared client defaults.
The factory applies `ChatClientCustomizer` beans first, followed by
`ChatClientBuilderCustomizer` beans. Within each type, Spring bean ordering is respected.

## Advisor configuration

Supply a tool advisor builder to customize its order or use Tool Search:

```java
ChatClient chatClient = guardrails.builder(chatModel, supportPolicy,
        ToolCallingAdvisor.builder().advisorOrder(100))
    .defaultTools(customerLookup)
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
        .inspectionService(inspectionService)
        .defaultPolicy(GuardrailsPolicy.builder().privacy().inspection().build())
        .build();
```

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

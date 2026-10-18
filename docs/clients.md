---
description: >-
  Select privacy, content inspection and tool authorization with one ChatClient factory.
---

# Client policies

**English** | [한국어](ko/clients.md)

Use `GuardrailsChatClientFactory` to select privacy, content inspection and tool
authorization for each client. The factory assembles the selected features and
protects tools registered through Spring AI, including request-specific and MCP tools.

## Dependencies and components

Add the client starter alongside your Spring AI model integration:

```gradle
implementation "io.github.ultramancode:spring-ai-privacy-guardrails-client-spring-boot-starter:0.4.0-SNAPSHOT"
```

Use the same version for all Guardrails modules. This starter includes the privacy
and inspection integrations, but does not include Spring Security, ONNX Runtime,
Presidio or an inspection backend.

| Selected feature | Required component | Setup |
| --- | --- | --- |
| Privacy | `PrivacyService` | Configure [Regex or another analyzer](getting-started.md). |
| Inspection | `InspectionService` | Register a `ContentInspector` or your own service. See [content inspection](inspection.md). |
| Tool authorization | Authorization integration | Add the [Security starter](security.md) and an `AuthorizationManager<ToolAuthorizationContext>` bean. |

Providing a component alone does not enable its feature. A selected feature with
missing components causes an error when creating the client builder.

## Policy per client

Select features, output handling and tool disclosure in `GuardrailsPolicy`.
Configure analyzers through their starter properties. Supply model paths and
connection settings when creating HTTP or ONNX inspector beans.

```java
GuardrailsPolicy supportPolicy = GuardrailsPolicy.builder()
        .privacy(privacy -> privacy
                .toolDisclosurePolicy(ToolDisclosurePolicy.byToolName(
                        Map.of("customerLookup", List.of("CUSTOMER_ID"))))
                .outputAction(PrivacyOutputAction.REDACT))
        .inspection(inspection -> inspection.applicationOutput(true))
        .toolAuthorization(true)
        .build();

ChatClient client = guardrails.builder(chatModel, supportPolicy)
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
to `client.prompt().tools(...)` receive the same protection. Unlisted tool/entity
combinations keep opaque tokens instead of receiving original PII.

Tool execution permission and original-value disclosure are separate decisions.
Enabling tool authorization does not supply a business authorization rule.

Selecting `privacy()` protects model requests and tools. Selecting `inspection()`
checks model requests. Final output protection and inspection are separate opt-in settings.
For output-only inspection, select
`.inspection(inspection -> inspection.modelRequests(false).applicationOutput(true))`.

## Reuse policies

Create policies and clients once and reuse them across requests. Policies do not each
need a Spring bean. `mutate()` returns a builder with the existing settings. It changes
only the settings you specify and leaves the original policy unchanged.

```java
GuardrailsPolicy inputPolicy = supportPolicy.mutate()
        .privacy(privacy -> privacy.outputProtection(false))
        .inspection(inspection -> inspection.applicationOutput(false))
        .build();

GuardrailsPolicy privacyOnly = supportPolicy.mutate()
        .inspection(false)
        .toolAuthorization(false)
        .build();

ChatClient inputClient = guardrails.builder(chatModel, inputPolicy).build();
ChatClient privacyClient = guardrails.builder(chatModel, privacyOnly).build();
```

`privacy(false)` and `inspection(false)` remove the entire feature.
A policy must select at least one feature. Different clients share services without
changing each other's policies or sessions.

`builder.clone()` and `client.mutate()` preserve the configured protection.
To change protection, derive a policy and create a new client through the factory.

## Default policy in Spring Boot

Register a `GuardrailsPolicy` bean if clients should share a default:

```java
@Bean
GuardrailsPolicy defaultGuardrailsPolicy() {
    return GuardrailsPolicy.builder().privacy().build();
}

@Bean
ChatClient chatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrails) {
    return guardrails.builder(chatModel).build();
}
```

`builder(chatModel)` uses the default policy. If there is no unique default, supply
an explicit policy. `builder(chatModel, policy)` uses only the supplied policy.

## Custom tool advisors and non-Boot applications

Supply a tool advisor builder to customize its order or use Tool Search:

```java
ChatClient client = guardrails.builder(chatModel, supportPolicy,
        ToolCallingAdvisor.builder().advisorOrder(100))
    .defaultTools(customerLookup)
    .build();
```

The factory copies the builder and preserves its subtype. Custom builders must
honor Spring AI's `copy()`, `toolCallingManager(...)` and `build()` contracts.
Do not register another tool advisor separately on the client.

Without Boot, depend on `spring-ai-privacy-guardrails-client` and provide the services:

```java
GuardrailsChatClientFactory guardrails = GuardrailsChatClientFactory.builder()
        .privacyService(privacyService)
        .inspectionService(inspectionService)
        .defaultPolicy(GuardrailsPolicy.builder().privacy().inspection().build())
        .build();
```

For authorization, add the Security module, construct its
`SpringSecurityToolBoundary` and `ToolAuthorizationChatClientFactory`, and pass
`.toolAuthorization(authorizationFactory::builder)` to the factory builder.
Observation settings and client customizers belong on `GuardrailsChatClientFactory`.

## Execution and diagnostics

Before a model call, the factory runs privacy processing, tool-definition
authorization and content inspection, in that order, for the selected features.
Tool authorization checks execution permission before restoring allowed original
values. Tool results are protected before reaching the model or application.
Content inspection does not inspect newly generated tool arguments immediately
before execution.

Privacy output protection and output inspection buffer streaming responses until
completion. This delays delivery. Set `privacy.responseLimits(...)` and
`inspection.maxOutputFrames(...)` / `outputStreamTimeout(...)` in the policy together
with the [inspection limits](inspection.md#scope-and-limits). Collection errors, timeouts
and cancellation release no buffered output. Blocking final output cannot undo
side effects of tools that have already run.

Use `InspectionObserver` to record inspection reports and failures. Its callbacks
also receive the inspection boundary and call or stream mode. See
[observability](inspection.md#observability).

Direct calls to `ChatModel` bypass this client. Detection coverage depends on the
configured analyzers and inspectors. See [scope and limits](inspection.md#scope-and-limits)
and the [threat model](threat-model.md).

For custom assembly, use the individual configurers as described in
[advanced manual assembly](configuration.md#advanced-manual-assembly).
Apply them directly to a Spring AI builder. A factory-built client already has
its selected features configured.

For a first run without a model API key, use the [local demo](sample.md).
For inspection without a ChatClient, see the [standalone example](inspection.md#inspect-without-a-chatclient).

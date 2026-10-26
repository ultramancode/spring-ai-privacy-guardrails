---
description: >-
  Configure privacy protection, tool authorization and content inspection in an existing Spring AI app.
---

# Getting Started

**English** | [한국어](ko/getting-started.md)

Create a `ChatClient` through `GuardrailsChatClientFactory` and select the features
your application needs with `GuardrailsPolicy`.

## Prerequisites

Use Java 17 or later, Spring AI 2.0.0 or later and Spring Boot 4.0.0 or later.
Start with a Spring AI application that provides a `ChatModel` bean.

## Add the Client Starter

Use the same version for every Guardrails module.

Add to `build.gradle`:

```gradle
repositories {
    mavenCentral()
}

dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-chat-client-spring-boot-starter:0.4.0"
}
```

Maven uses Central by default. Add to `dependencies` in `pom.xml`:

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-chat-client-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

## Choose Features

Configure the features you need below, then select them in the client policy.
Use one feature or combine several.

| Feature | Policy selection |
| --- | --- |
| [Privacy protection](#privacy-protection) | `privacy()` |
| [Tool authorization](#tool-authorization) | `toolAuthorization(true)` |
| [Content inspection](#content-inspection) | `inspection()` |

## Configure the Selected Features

### Privacy Protection

Privacy protection tokenizes detected PII before it reaches the model, controls which
original values each tool receives, and protects tool results. Choose analyzers for
the data your application handles:

| Analyzer | Detection |
| --- | --- |
| [Presidio](privacy.md#presidio-analyzer) | PII detection through a Presidio service |
| [OpenNLP](privacy.md#opennlp-analyzer) | Named entity recognition with models in the application JVM |
| [Local rules](privacy.md#local-rules-and-presets) | Built-in rules or application-defined patterns and validation logic |

You can also [combine analyzers](privacy.md#detection-and-resolution).
To connect another detection model or service, implement `PiiAnalyzer` and register it
as a [custom analyzer](privacy.md#custom-analyzers).

#### Configure Built-in Presets

Select built-in presets for local rules in `src/main/resources/application.yml`:

<a id="enable-built-in-presets"></a>

```yaml
spring:
  ai:
    privacy:
      rules:
        enabled: true
        presets: [COMMON, KR]
```

`COMMON` covers supported email and card-number formats. `KR` covers Korean mobile-phone and
resident-registration-number formats. See [built-in scope](privacy.md#built-in-rules).

### Tool Authorization

Add the Security starter alongside the client starter.

For Gradle:

```gradle
implementation "io.github.ultramancode:spring-ai-privacy-guardrails-spring-security-spring-boot-starter:0.4.0"
```

For Maven:

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-spring-security-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

Register an authorization manager in your application's `@Configuration` class.
This example permits registered tools only for users with the `TOOL_USE` authority:

```java
@Bean
AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
    return AuthorityAuthorizationManager.hasAuthority("TOOL_USE");
}
```

Tool authorization uses the request's `Authentication` from your application's Spring Security setup.
For permissions that differ by tool, implement an [authorization manager class](security.md#define-the-authorization-policy).

### Content Inspection

Content inspection evaluates model input and optional final output. An inspection
policy uses the findings to allow or block processing. Choose inspectors for your
deployment and inspection needs:

| Inspector | Inspection approach |
| --- | --- |
| [Local ONNX](inspection.md#local-content-inspection-with-onnx) | Text classification with models in the application JVM |
| [HTTP](inspection.md#http-guard-models-and-privacy) | A guard model served over HTTP |
| [Rules](#configure-a-rule-based-inspector) | Explicit phrases and patterns |

For application-specific inspection logic, implement `ContentInspector` and register it
as a [custom inspector](inspection.md#custom-inspectors).

#### Configure a Rule-Based Inspector

Add the rules module alongside the client starter.

For Gradle:

```gradle
implementation "io.github.ultramancode:spring-ai-privacy-guardrails-inspection-rules:0.4.0"
```

For Maven:

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-inspection-rules</artifactId>
    <version>0.4.0</version>
</dependency>
```

Register an inspector in your application's `@Configuration` class.
This example blocks text containing the case-sensitive phrase `ignore previous instructions`:

```java
@Bean
ContentInspector applicationRules() {
    InspectionRule blockedPhrase = InspectionRule.literal(
            "blocked-phrase", InspectionFinding.Category.PROMPT_INJECTION, "ignore previous instructions");
    return new RuleBasedContentInspector("application-rules", List.of(blockedPhrase));
}
```

<a id="create-a-protected-client"></a>

## Create the Client

Select the configured features in `GuardrailsPolicy`, then create the `ChatClient` bean
through `GuardrailsChatClientFactory`. The following example combines all three features.
Keep only the selections you need:

```java
@Configuration(proxyBeanMethods = false)
public class GuardrailsConfiguration {
    @Bean
    ChatClient chatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
        GuardrailsPolicy policy = GuardrailsPolicy.builder()
                .privacy()
                .toolAuthorization(true)
                .inspection()
                .build();
        return guardrailsChatClientFactory.builder(chatModel, policy)
                .build();
    }
}
```

For example, a client that only needs content inspection uses
`GuardrailsPolicy.builder().inspection().build()`.

Register your application's tools through `defaultTools(...)` on the builder or
`tools(...)` on a request. Objects with `@Tool` methods, `ToolCallback` instances and
MCP `ToolCallbackProvider` instances use the selected policy.
To allow a tool to receive original PII, configure [tool disclosure](privacy.md#per-tool-original-disclosure).

For analyzer configuration, tool implementation and client registration in a complete application,
see the [customer lookup sample](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/v0.4.0/samples/spring-ai-demo/README.md#reading-the-sample-code).

<a id="call-and-check"></a>

## Call the Client

Inject the client into your application service:

```java
@Service
public class ChatService {
    private final ChatClient chatClient;

    public ChatService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String answer(String message) {
        return this.chatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
```

## Verify the Selected Features

Verify the example configurations on this page:

| Feature | Check |
| --- | --- |
| Privacy protection | Enable the `COMMON` and `KR` presets and send `alice@example.com` and `010-2345-6789`. Both values are replaced with opaque tokens in the model input. |
| Tool authorization | Apply the example authorization policy and compare authenticated users with and without `TOOL_USE`. Only users with the authority may expose registered tools to the model and execute them. |
| Content inspection | Send the blocked phrase configured in the rule-based example. That rule triggers `InspectionBlockedException` before the model is called. |

With the privacy example configured, the email address is tokenized as shown below.
The token's random part is abbreviated as `<opaque>`:

```text
Input:
Contact alice@example.com.

Model input:
Contact [PII_EMAIL_ADDRESS_1:<opaque>].
```

## Next Steps

| Need | Guide |
| --- | --- |
| Custom PII detection, per-tool original disclosure or final-response protection | [Privacy Protection](privacy.md) |
| Per-tool permissions, Tool Search or asynchronous authentication | [Tool Authorization](security.md) |
| ONNX or HTTP guard models, output inspection or inspection policies | [Content Inspection](inspection.md) |
| Shared policies and client customization | [Configuration](configuration.md) |
| Diagnose unexpected behavior | [Troubleshooting](troubleshooting.md) |
| Explore Local Tool, RAG, MCP, Security and Inspection scenarios | [Sample Guide](sample.md) |

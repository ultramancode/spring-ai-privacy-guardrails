---
description: >-
  Set up Spring AI Privacy Guardrails: choose a starter and analyzer, then protect
  model requests, local and MCP tool calls, and final responses.
---

# Getting Started

**English** | [한국어](ko/getting-started.md)

This guide shows the basic path for adding Spring AI Privacy Guardrails to an
existing Spring AI application and applying privacy protection across model,
tool, MCP, and output boundaries.

Start with the built-in Regex analyzer for a setup that requires no external
analyzer service. For PII detection beyond application-specific patterns, you
can integrate Presidio, an open-source framework for PII detection and
de-identification, as an external analyzer service.

Use OpenNLP when you want to run your own NER models inside the JVM, or a
custom `PiiAnalyzer` when you need detection tailored to your application.

The application provides a `ChatModel`. The client starter supplies
`GuardrailsChatClientFactory`. See [Configuration](configuration.md) to select and reuse client protection policies.

To check protection with a local model and fixed examples, without an external model API key, see
the [Sample / Demo Guide](sample.md).

## Prerequisites

Minimum versions: Java 17, Spring AI 2.0.0 and Spring Boot 4.0.0.

## 1. Choose an Analyzer

Use the client starter to configure your `ChatClient`, then choose an analyzer.
Presidio and OpenNLP require an additional starter. Each linked section includes
its Gradle and Maven dependencies.

| Analyzer | Use |
| --- | --- |
| [Regex](#2-quick-start-with-regex) | Application-defined patterns. Included in the client starter. |
| [Presidio](#5-use-presidio-for-pii-detection) | Detect various types of PII through an external Presidio service. |
| [OpenNLP](#6-use-opennlp-for-jvm-local-detection) | Detect PII inside the application using OpenNLP models you provide. No external analysis service is needed. |

Use the same version for all Privacy Guardrails modules used together. Adding a
starter does not enable privacy protection or an analyzer automatically.

Tool authorization uses a separate Spring Security starter. It can be used on
its own or together with a privacy starter. See
[Spring Security Tool Authorization](security.md) for its dependency and setup.

## 2. Quick Start with Regex

The built-in Regex analyzer is the easiest way to verify the privacy boundary
without an external analyzer service.

Add the client starter. Analyzer-specific starters can be added as needed.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-chat-client-spring-boot-starter:0.4.0-SNAPSHOT"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-chat-client-spring-boot-starter</artifactId>
    <version>0.4.0-SNAPSHOT</version>
</dependency>
```

Enable Regex and define application-specific identifiers:

```yaml
spring:
  ai:
    privacy:
      regex:
        enabled: true
        rules:
          - entity-type: EMPLOYEE_ID
            pattern: "(?<![A-Za-z0-9_])EMP-[0-9]{4}(?![A-Za-z0-9_])"
            score: 0.90
          - entity-type: CUSTOMER_ID
            pattern: "(?<![A-Za-z0-9_])CUST-[0-9]{6}(?![A-Za-z0-9_])"
            score: 0.90
```

These rules detect only the configured formats. Regex rules are useful for
structured application identifiers. They are not intended to provide complete
general-purpose PII detection.

Once a `PiiAnalyzer` bean is available, the starter provides `PrivacyService`
and the components used by `GuardrailsChatClientFactory`.

If a format match also needs a checksum or business-rule check, add a
[custom Regex validator](privacy.md#regex-analyzer).

## 3. Protect a ChatClient

Configuring an analyzer does not automatically protect every
`ChatClient`.

Create the protected client through the factory:

```java
@Bean
ChatClient privacyChatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
    GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder().privacy().build();
    return guardrailsChatClientFactory.builder(chatModel, guardrailsPolicy).build();
}
```

Use the configured client normally:

```java
String response = privacyChatClient.prompt()
        .user("Employee EMP-1234 requested customer CUST-123456.")
        .call()
        .content();
```

In this library, **PII tokenization** replaces detected PII with request-scoped
**opaque tokens** before the model call. These replacement strings do not directly
reveal the original values.

The example below shows what the model receives. `<opaque>` represents a random
value generated for each privacy session.

```text
Employee [PII_EMPLOYEE_ID_1:<opaque>] requested customer
[PII_CUSTOMER_ID_1:<opaque>].
```

In this example, the detected employee and customer IDs are not sent to the
model as original values. The library manages mappings between opaque tokens
and original values for each request. Application logic must not parse opaque
token internals or depend on a specific opaque token format.

Within one session, different values of the same type receive separate indexes,
and repeated values reuse their token. Mappings are discarded when the session closes.

Direct calls to a `ChatModel` are outside this automatic boundary.

To inspect the input sent to the model, run the fixed examples in the
[Privacy Boundary Inspector](sample.md).

## 4. Protect Local and MCP Tools

Once privacy protection is applied to a tool, detected PII is passed as opaque tokens
by default. If a tool needs an original value, such as a customer ID for a
lookup, specify the tool name and permitted PII types in `ToolDisclosurePolicy`.

```java
GuardrailsPolicy toolPolicy = GuardrailsPolicy.builder()
        .privacy(GuardrailsPolicy.Privacy.builder()
                .toolDisclosurePolicy(ToolDisclosurePolicy.allowEntityTypesByToolName(
                        Map.of("customerLookup", List.of("CUSTOMER_ID"))))
                .build())
        .build();
```

For both local and MCP tools, the name configured in `ToolDisclosurePolicy` is
case-sensitive and must exactly match the actual `ToolDefinition.name()`. With
this policy, `customerLookup` receives only customer IDs (`CUSTOMER_ID`) as
original values. Other detected PII is passed as opaque tokens.

### Local ToolCallback

Register existing callbacks or objects with annotated tools directly:

```java
ChatClient toolClient = guardrails.builder(chatModel, toolPolicy)
        .defaultTools(customerLookupToolCallback)
        .build();
```

Detected values in tool results are protected before being returned to the model
or application. Request-specific tools passed to `.tools(...)` follow the same policy.

### MCP and Dynamic ToolCallbackProvider

Register the provider itself so Spring AI resolves its current tools for every request:

```java
ChatClient mcpClient = guardrails.builder(chatModel, toolPolicy)
        .defaultTools(mcpToolCallbackProvider)
        .build();
```

If an MCP provider adds a tool-name prefix, configure `ToolDisclosurePolicy` with
the final prefixed tool name.

Privacy protection is not applied automatically to separate tool-calling paths
that directly configure `ToolCallingManager` or `ToolCallbackResolver`. If your
application uses these paths, integrate privacy protection with them separately.

For an actual local Streamable HTTP MCP round trip showing scoped disclosure and
tool-result re-protection, see the
[Sample / Demo Guide](sample.md#mcp).

### Optional Spring Security Tool Authorization

To limit which tools the model can discover and execute based on the current
user's permissions, add the Spring Security starter and register a tool
authorization policy as an `AuthorizationManager<ToolAuthorizationContext>`
bean. Select `toolAuthorization(true)` in `GuardrailsPolicy` and pass it to
`guardrailsChatClientFactory.builder(chatModel, guardrailsPolicy)`. Privacy is selected independently.
When both
features are used, permission is checked again immediately before tool
execution, and only then are the original PII values allowed for that tool
restored.

See [Spring Security Tool Authorization](security.md) for starter dependencies,
an authorization policy, and client configuration examples.

## 5. Use Presidio for PII Detection

Presidio is an open-source framework for PII detection and de-identification.
Use the Presidio starter when you want to detect PII beyond
application-specific patterns through an external Presidio Analyzer service.

Add the Presidio starter alongside the [client starter](#2-quick-start-with-regex):

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.4.0-SNAPSHOT"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-presidio-spring-boot-starter</artifactId>
    <version>0.4.0-SNAPSHOT</version>
</dependency>
```

Enable Presidio and configure its Analyzer endpoint:

```yaml
spring:
  ai:
    privacy:
      analysis:
        language: en
      presidio:
        enabled: true
        analyzer-url: http://localhost:5002
```

If you cloned this repository, start its pinned local Presidio service with:

```bash
docker compose -f samples/presidio/docker-compose.yml up -d --wait
```

Create the client as shown in [Protect a ChatClient](#3-protect-a-chatclient).

Regex and Presidio may also be enabled together. In the default `UNION` mode,
all configured analyzers run and their detection findings are merged. The
default `REQUIRE_ALL` failure policy fails the request if any configured
analyzer fails. Review
[Privacy Protection](privacy.md#detection-and-resolution) before combining
analyzers in production.

## 6. Use OpenNLP for JVM-Local Detection

Use the OpenNLP starter when detection should run inside the application JVM
with application-supplied compatible OpenNLP models. Add it alongside the
[client starter](#2-quick-start-with-regex).

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.4.0-SNAPSHOT"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-opennlp-spring-boot-starter</artifactId>
    <version>0.4.0-SNAPSHOT</version>
</dependency>
```

A minimal `PERSON` configuration can look like:

```yaml
spring:
  ai:
    privacy:
      analysis:
        language: en
      opennlp:
        enabled: true
        tokenizer-model: classpath:/models/en-token.bin
        entity-models:
          PERSON: classpath:/models/en-ner-person.bin
```

OpenNLP model binaries are not bundled with this project. The application owns
the model files and must validate model provenance, tokenizer compatibility, and
detection quality for the target environment.

`tokenizer-model` is optional. When omitted, the integration uses OpenNLP's
`SimpleTokenizer`.

See the
[Full Sample Guide](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/samples/spring-ai-demo/README.md)
for the reproducible OpenNLP smoke-test setup.

## 7. Optional: Protect Final Responses

Final-response privacy protection is disabled by default. Enable it in the client policy:

```java
GuardrailsPolicy outputPolicy = GuardrailsPolicy.builder()
        .privacy(GuardrailsPolicy.Privacy.builder()
                .outputProtection().tokenize()
                .build())
        .build();
ChatClient outputClient = guardrails.builder(chatModel, outputPolicy).build();
```

Choose the output action through `outputProtection()` in the privacy settings.

Supported output actions are:

- `TOKENIZE`: replace detected PII with request-scoped opaque tokens.
- `REDACT`: replace detected PII with typed markers that cannot be restored to
  the original values.
- `BLOCK`: stop response delivery when PII is detected and throw
  `PrivacyOutputBlockedException`.

The streaming API remains available when output protection is enabled, but
response chunks are not released to the application as soon as they arrive. The
library first buffers the complete response, inspects it, and then releases the
protected result. This allows PII split across multiple chunks to be detected, but model output
cannot be delivered in real time as it is generated.

See [Output Policy and Streaming](privacy.md#output-policy-and-streaming)
and [Input and Response Limits](privacy.md#input-and-response-limits) for
details.

## 8. Custom Analyzers

Applications can provide custom `PiiAnalyzer` Spring beans when Regex, Presidio,
or OpenNLP is not the right detector.

Custom analyzers participate in the same detection and resolution flow as the
built-in and optional analyzers, and their findings are enforced at the same
model, tool, and optional output privacy boundaries. A custom `PiiAnalyzer` may
be invoked concurrently by multiple requests, so it must be thread-safe and
reentrant. Apply finite timeouts to blocking work such as external service calls
and bound its own memory and other resource usage.

See [Privacy Protection](privacy.md#detection-and-resolution) for analyzer
selection, provider IDs, entity aliases, confidence floors, and failure
policies. See [Custom Analyzers](privacy.md#custom-analyzers) for
implementation requirements and analysis of multiple texts.

## Boundary Notes

A protected `ChatClient` protects supported message content before it is sent to
the model. This includes supported memory and RAG context that is sent to the
model. It does not automatically modify PII that is already stored in
chat-memory storage, vector stores, databases, logs, or traces.

## Next Steps

- See [Configuration](configuration.md) to combine features and reuse client policies.
- See [Privacy Protection](privacy.md) for privacy properties, behavior and API usage.
- See [Privacy-Safe Runtime Observation](privacy.md#privacy-safe-runtime-observation)
  to observe boundary outcomes without exposing PII or payloads.
- See the [Sample / Demo Guide](sample.md) to check protection in Local Tool,
  RAG, and Streamable HTTP MCP scenarios.
- See [Architecture](architecture.md) for model, tool, session, and request
  lifecycle boundaries.
- See [Evaluation](evaluation.md) for the reproducible privacy-boundary
  verification matrix and benchmarks.
- Review the [Threat Model](threat-model.md) before production use.

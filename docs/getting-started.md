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

The application is expected to already provide a `ChatModel` and
`ChatClient.Builder`.

To check protection with a local model and fixed examples, without an external model API key, see
the [Sample / Demo Guide](sample.md).

## Prerequisites

The current code is verified with:

- Java 17
- Spring AI 2.0.1
- Spring Boot 4.1.1

## 1. Choose a Privacy Starter

Choose the starter for the analyzer you want to use. Each linked section
includes its Gradle and Maven dependencies.

| Starter | Use |
| --- | --- |
| [Base](#2-quick-start-with-regex) | Application-defined Regex rules or custom analyzers |
| [Presidio](#5-use-presidio-for-pii-detection) | Detect various types of PII through an external Presidio service. |
| [OpenNLP](#6-use-opennlp-for-jvm-local-detection) | Detect PII inside the application using OpenNLP models you provide. No external analysis service is needed. |

The Presidio and OpenNLP starters already include the base starter. Use the
same version for all Privacy Guardrails modules used together. Adding a starter
does not enable privacy protection or an analyzer automatically.

Tool authorization uses a separate Spring Security starter. It can be used on
its own or together with a privacy starter. See
[Spring Security Tool Authorization](security.md) for its dependency and setup.

## 2. Quick Start with Regex

The built-in Regex analyzer is the easiest way to verify the privacy boundary
without an external analyzer service.

Add the base starter.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-spring-boot-starter:0.3.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-spring-boot-starter</artifactId>
    <version>0.3.0</version>
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
structured application identifiers; they are not intended to provide complete
general-purpose PII detection.

Once a `PiiAnalyzer` bean is available, the starter provides `PrivacyService`
and `PrivacyChatClientConfigurer`.

If a format match also needs a checksum or business-rule check, add a
[custom Regex validator](configuration.md#regex-analyzer).

## 3. Protect a ChatClient

Configuring an analyzer does not automatically protect every
`ChatClient`.

Apply the starter-provided `PrivacyChatClientConfigurer` to each builder that
should be inside the privacy boundary:

```java
@Bean
ChatClient privacyChatClient(
        ChatClient.Builder builder,
        PrivacyChatClientConfigurer privacyConfigurer
) {
    return privacyConfigurer.configure(builder).build();
}
```

Use the configured client normally:

```java
String response = privacyChatClient.prompt()
        .user("Employee EMP-1234 requested customer CUST-123456.")
        .call()
        .content();
```

Before the model call, detected PII is replaced with strings that do not reveal
the original values (tokens). The example below shows what the model receives.
The `<opaque>` part represents a value generated for each request.

```text
Employee [[PII_EMPLOYEE_ID_<opaque>]] requested customer
[[PII_CUSTOMER_ID_<opaque>]].
```

In this example, the detected employee and customer IDs are not sent to the
model as original values. The library manages token-to-original mappings for
each request. Application logic must not parse token internals or depend on a
specific token format.

Direct calls to a `ChatModel` are outside this automatic boundary.

To inspect the input sent to the model, run the fixed examples in the
[Privacy Boundary Inspector](sample.md).

## 4. Protect Local and MCP Tools

Once privacy protection is applied to a tool, detected PII is passed as tokens
by default. If a tool needs an original value, such as a customer ID for a
lookup, specify the tool name and permitted PII types in `tools.disclosures`.

```yaml
spring:
  ai:
    privacy:
      tools:
        disclosures:
          customerLookup:
            - CUSTOMER_ID
```

For both local and MCP tools, the name configured in `tools.disclosures` is
case-sensitive and must exactly match the actual `ToolDefinition.name()`. With
this policy, `customerLookup` receives only customer IDs (`CUSTOMER_ID`) as
original values. Other detected PII is passed as tokens.

### Local ToolCallback

Wrap an existing Spring AI `ToolCallback` before registering it with a protected
`ChatClient`:

```java
ToolCallback protectedCustomerLookup =
        privacyToolCallbackFactory.wrap(customerLookupToolCallback);
```

Here, `customerLookupToolCallback` is the application's existing Spring AI
`ToolCallback`.

Register the wrapped `ToolCallback` with the client's `defaultTools(...)`:

```java
ChatClient toolClient = privacyConfigurer.configure(
        ChatClient.builder(chatModel)
                .defaultTools(protectedCustomerLookup)
).build();
```

Detected values in tool results are protected again before being sent back to
the model or returned directly to the application.

### MCP and Dynamic ToolCallbackProvider

MCP tool lists can be registered through a `ToolCallbackProvider`. Wrap the
provider itself with `wrapProvider(...)` so protection also applies to tools it
supplies in later requests. In this example, `mcpToolCallbackProvider` is the
`ToolCallbackProvider` supplied by the application's MCP integration.

```java
ToolCallbackProvider protectedMcpTools =
        privacyToolCallbackFactory.wrapProvider(mcpToolCallbackProvider);
```

Register the wrapped `ToolCallbackProvider` with the client's `defaultTools(...)`:

```java
ChatClient mcpClient = privacyConfigurer.configure(builder)
        .defaultTools(protectedMcpTools)
        .build();
```

If an MCP provider adds a tool-name prefix, configure `tools.disclosures` with
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
bean. Create the `ChatClient` with the factory bean for the features you need:

- **Tool authorization alone:** Use `ToolAuthorizationChatClientFactory`.
  No privacy starter or analyzer is required.
- **Combined with privacy protection:** Configure a privacy starter and an
  analyzer, then use `PrivacySecurityChatClientFactory`. If you configured
  privacy protection in the preceding steps, use this factory to create the
  client.

Both factories create clients with `builder(chatModel).build()`. When both
features are used, permission is checked again immediately before tool
execution, and only then are the original PII values allowed for that tool
restored.

See [Spring Security Tool Authorization](security.md) for starter dependencies,
an authorization policy, and client configuration examples.

## 5. Use Presidio for PII Detection

Presidio is an open-source framework for PII detection and de-identification.
Use the Presidio starter when you want to detect PII beyond
application-specific patterns through an external Presidio Analyzer service.

The Presidio starter already includes the base Privacy Guardrails starter, so
do not add the base starter separately when using Presidio. Add another analyzer
starter only when that analyzer is also needed.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.3.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-presidio-spring-boot-starter</artifactId>
    <version>0.3.0</version>
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

Presidio also requires the `PrivacyChatClientConfigurer` setup in
[Protect a ChatClient](#3-protect-a-chatclient). If you already applied it above,
you do not need to configure the client again.

Regex and Presidio may also be enabled together. In the default `UNION` mode,
all configured analyzers run and their detection findings are merged. The
default `REQUIRE_ALL` failure policy fails the request if any configured
analyzer fails. Review
[Configuration](configuration.md#detection-and-resolution) before combining
analyzers in production.

## 6. Use OpenNLP for JVM-Local Detection

Use the OpenNLP starter when detection should run inside the application JVM
with application-supplied compatible OpenNLP models.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.3.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-opennlp-spring-boot-starter</artifactId>
    <version>0.3.0</version>
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

`tokenizer-model` is optional; when omitted, the integration uses OpenNLP's
`SimpleTokenizer`.

See the
[Full Sample Guide](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/samples/spring-ai-demo/README.md)
for the reproducible OpenNLP smoke-test setup.

## 7. Optional: Protect Final Responses

Input and tool boundaries do not automatically enable final-response
inspection.

Enable output protection when application-facing responses also need a final
privacy check:

```yaml
spring:
  ai:
    privacy:
      output:
        enabled: true
        action: tokenize
```

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

See [Output Policy and Streaming](configuration.md#output-policy-and-streaming)
and [Input and Response Limits](configuration.md#input-and-response-limits) for
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

See [Configuration](configuration.md#detection-and-resolution) for analyzer
selection, provider IDs, entity aliases, confidence floors, and failure
policies. See [Custom Analyzers](configuration.md#custom-analyzers) for
implementation requirements and analysis of multiple texts.

## Boundary Notes

A protected `ChatClient` protects supported message content before it is sent to
the model. This includes supported memory and RAG context that is sent to the
model. It does not automatically modify PII that is already stored in
chat-memory storage, vector stores, databases, logs, or traces.

## Next Steps

- See [Configuration](configuration.md) for the complete property and API
  reference.
- See [Privacy-Safe Runtime Observation](configuration.md#privacy-safe-runtime-observation)
  to observe boundary outcomes without exposing PII or payloads.
- See the [Sample / Demo Guide](sample.md) to check protection in Local Tool,
  RAG, and Streamable HTTP MCP scenarios.
- See [Architecture](architecture.md) for model, tool, session, and request
  lifecycle boundaries.
- See [Evaluation](evaluation.md) for the reproducible privacy-boundary
  verification matrix and benchmarks.
- Review the [Threat Model](threat-model.md) before production use.

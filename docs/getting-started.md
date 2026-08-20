# Getting Started

[English](getting-started.md) | [한국어](ko/getting-started.md)

This guide shows the basic path for adding Spring AI Privacy Guardrails to an
existing Spring AI application and applying privacy protection across model,
tool, MCP, and output boundaries.

Start with the built-in Regex analyzer for a setup that requires no external
analyzer service. For PII detection beyond application-specific patterns, you
can integrate Presidio, an open-source framework for PII detection and
de-identification, as an external analyzer service. Use OpenNLP when you want to run your own NER models inside the JVM, or a
custom `PiiAnalyzer` when you need detection tailored to your application.

The application is expected to already provide a `ChatModel` and
`ChatClient.Builder`.

For a deterministic demonstration that requires no cloud model credentials, see
the [Sample / Demo Guide](sample.md).

## Prerequisites

The current release is verified with:

- Java 17
- Spring AI 2.0.0
- Spring Boot 4.1.0

## 1. Choose a Starter

Choose the starter for the analyzer you want to use:

| Starter | Dependency | Use |
| --- | --- | --- |
| Base Spring Boot starter | `io.github.ultramancode:spring-ai-privacy-guardrails-spring-boot-starter:0.2.0` | Built-in Regex rules or custom analyzers |
| Presidio Spring Boot starter | `io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.2.0` | PII detection through an external Presidio Analyzer service |
| OpenNLP Spring Boot starter | `io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.2.0` | JVM-local NER with application-supplied compatible models |

The Presidio and OpenNLP starters already include the base starter. Adding a
starter does not enable privacy protection or an analyzer automatically.

## 2. Quick Start with Regex

The built-in Regex analyzer is the easiest way to verify the privacy boundary
without an external analyzer service.

Add the base starter.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-spring-boot-starter:0.2.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

Enable privacy protection and define application-specific identifiers:

```yaml
spring:
  ai:
    privacy:
      enabled: true
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

After `spring.ai.privacy.enabled=true`, at least one analyzer must be configured
or application startup fails.

### Optional: Validate Regex Matches

A Regex rule can reference an application-provided validator when a format match
also needs a checksum or domain-specific validation step.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      regex:
        enabled: true
        rules:
          - entity-type: CUSTOMER_ID
            pattern: "(?<![A-Za-z0-9_])CUST-[0-9]{6}(?![A-Za-z0-9_])"
            score: 0.90
            capture-group: 0
            validator-id: customer-id-check
```

Provide a `RegexPiiMatchValidator` with the matching stable ID:

```java
@Bean
RegexPiiMatchValidator customerIdMatchValidator() {
    return new RegexPiiMatchValidator() {

        @Override
        public String id() {
            return "customer-id-check";
        }

        @Override
        public boolean isValid(String candidate) {
            return CustomerIds.hasValidChecksum(candidate);
        }
    };
}
```

`CustomerIds.hasValidChecksum(...)` is application-provided validation logic,
not a method supplied by this library. A Regex match becomes a detection finding
only when the validator returns `true`. Validator implementations may be shared
across requests, so they must be thread-safe. Because the value being validated
may itself contain PII, do not log it or retain it long term.

See [Configuration](configuration.md#regex-analyzer) for `validator-id`,
`capture-group`, startup configuration validation, and analyzer failure-policy
details.

## 3. Protect a ChatClient

Enabling privacy and an analyzer does not automatically protect every
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

Before the model call, detected values are replaced with request-scoped opaque
tokens. Conceptually, the model sees content like:

```text
Employee [[PII_EMPLOYEE_ID_<opaque>]] requested customer
[[PII_CUSTOMER_ID_<opaque>]].
```

Original values are not sent to the model; token-to-original mappings are
managed per request through `PrivacySession`. Application logic should not
depend on the specific format of opaque tokens.

Direct calls to a `ChatModel` are outside this automatic boundary.

To inspect the actual model-visible content without relying on provider logs,
run the deterministic
[Privacy Boundary Inspector](sample.md).

## 4. Protect Local and MCP Tools

Tool disclosure is deny-by-default. Registering a tool does not grant it access
to original PII.

Configure each tool to receive original values only for the entity types it needs. For example:

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
this policy, `customerLookup` may receive the original `CUSTOMER_ID`; other
detected entity types remain protected.

### Local ToolCallback

Wrap an existing Spring AI `ToolCallback` before registering it with a protected
`ChatClient`:

```java
ToolCallback protectedCustomerLookup =
        privacyToolCallbackFactory.wrap(customerLookupToolCallback);
```

Here, `customerLookupToolCallback` is the application's existing Spring AI
`ToolCallback`.

Register the wrapped `ToolCallback` using the normal Spring AI tool registration flow:

```java
ChatClient toolClient = privacyConfigurer.configure(
        ChatClient.builder(chatModel)
                .defaultTools(protectedCustomerLookup)
).build();
```

Detected values in tool results are protected again before model re-entry or
direct application delivery.

### MCP and Dynamic ToolCallbackProvider

MCP integrations commonly expose discovered tools through a
`ToolCallbackProvider`. When the tool list can change at runtime, wrap the
provider itself instead of reading its current tool list once and wrapping only
that snapshot:

```java
ToolCallbackProvider protectedMcpTools =
        privacyToolCallbackFactory.wrapProvider(mcpToolCallbackProvider);
```

Then register the wrapped provider normally:

```java
ChatClient mcpClient = privacyConfigurer.configure(builder)
        .defaultTools(protectedMcpTools)
        .build();
```

If an MCP provider adds a tool-name prefix, configure `tools.disclosures` with
the final prefixed tool name.

Wrapping the `ToolCallbackProvider` itself with `wrapProvider(...)` keeps
privacy protection in place even when the tool list changes and new tools are
returned later.

Privacy protection is not applied automatically to separate tool-calling paths
that directly configure `ToolCallingManager` or `ToolCallbackResolver`. If your
application uses these paths, integrate privacy protection with them separately.

For an actual local Streamable HTTP MCP round trip showing scoped disclosure and
tool-result re-protection, see the
[Sample / Demo Guide](sample.md#mcp).

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
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.2.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-presidio-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

Enable Presidio and configure its Analyzer endpoint:

```yaml
spring:
  ai:
    privacy:
      enabled: true
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

Regardless of which analyzer performs detection, `ChatClient` protection uses the
same `PrivacyChatClientConfigurer` and the same model and tool boundaries.

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
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.2.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-opennlp-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

A minimal `PERSON` configuration can look like:

```yaml
spring:
  ai:
    privacy:
      enabled: true
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
      enabled: true
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

See [Configuration](configuration.md#output-policy-and-streaming) for output
policies and response-inspection limits.

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
policies.

## Boundary Notes

A protected `ChatClient` protects supported message content before it is sent to
the model. This includes supported memory and RAG context that is sent to the
model. It does not automatically modify PII that is already stored in
chat-memory storage, vector stores, databases, logs, or traces.

Direct `ChatModel` calls and custom execution paths outside the configured
`ChatClient` boundary are not protected automatically.

## Next Steps

- See [Configuration](configuration.md) for the complete property and API
  reference.
- See [Privacy-Safe Runtime Observation](configuration.md#privacy-safe-runtime-observation)
  to observe boundary outcomes without exposing PII or payloads.
- See the [Sample / Demo Guide](sample.md) for Local Tool, RAG, and Streamable
  HTTP MCP runtime evidence.
- See [Architecture](architecture.md) for model, tool, session, and request
  lifecycle boundaries.
- See [Evaluation](evaluation.md) for the reproducible privacy-boundary
  verification matrix and benchmarks.
- Review the [Threat Model](threat-model.md) before production use.

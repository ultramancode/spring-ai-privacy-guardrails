# Spring AI Privacy Guardrails

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ultramancode/spring-ai-privacy-guardrails-chat-client-spring-boot-starter?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.ultramancode/spring-ai-privacy-guardrails-chat-client-spring-boot-starter)
[![CI](https://github.com/ultramancode/spring-ai-privacy-guardrails/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ultramancode/spring-ai-privacy-guardrails/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[English](README.md) | [한국어](README.ko.md) | [Documentation](https://ultramancode.github.io/spring-ai-privacy-guardrails/)

**Tokenize detected PII before it reaches the model. Reveal only the originals a tool needs, then protect its result again.**

| What you need | Feature |
| --- | --- |
| Keep detected PII out of the model and allow originals only for selected tools | [Privacy](docs/privacy.md) |
| Limit tool visibility and execution using the current user's permissions | [Tool Authorization](docs/security.md) |
| Check requests and responses with rules, local ONNX or an HTTP guard model | [Content Inspection](docs/inspection.md) |

Use these features independently or together.

<p align="center">
  <a href="docs/images/hero.svg"><img src="docs/images/hero.svg" alt="PII tokenization and per-tool disclosure, with optional tool authorization and content inspection" width="100%"></a>
</p>

**Featured In**

- **Spring Blog** — Featured in [This Week in Spring](https://spring.io/blog/2026/08/18/this-week-in-spring-august-18-2026/)
- **Spring AI Community** — Listed in [Awesome Spring AI's Extensions and Forks](https://github.com/spring-ai-community/awesome-spring-ai#extensions-and-forks)

<br>

## Add Protection to an Application

Use Java 17 or later, Spring AI 2.0.0 or later and Spring Boot 4.0.0 or later.
See [repository configuration and Maven setup](docs/getting-started.md#add-the-client-starter).

Add the client starter:

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-chat-client-spring-boot-starter:0.4.0"
}
```

For this privacy example, enable the `COMMON` preset to detect email addresses.
Add to `src/main/resources/application.yml`:

```yaml
spring:
  ai:
    privacy:
      rules:
        enabled: true
        presets: [COMMON]
```

Create the client through `GuardrailsChatClientFactory` in an application `@Configuration` class.
Select privacy protection in its policy:

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

Inject this bean into your service and call it:

```java
String response = chatClient.prompt()
        .user("Contact alice@example.com.")
        .call()
        .content();
```

The model receives the following input, with the token's random part abbreviated as `<opaque>`:

```text
Contact [PII_EMAIL_ADDRESS_1:<opaque>].
```

Configure the features your application needs:

| Feature setup | Policy selection |
| --- | --- |
| [Privacy protection](docs/getting-started.md#privacy-protection) | `privacy()` |
| [Tool authorization](docs/getting-started.md#tool-authorization) | `toolAuthorization(true)` |
| [Content inspection](docs/getting-started.md#content-inspection) | `inspection()` |

See [Getting Started](docs/getting-started.md) for feature combinations, tool registration and verification.

## Per-Tool Original Disclosure

The model receives detected values as opaque tokens. Immediately before a tool runs,
only permitted entity types are restored, and its result is protected again.

In the `chatClient` bean, update the policy's privacy settings:

```java
ToolDisclosurePolicy toolDisclosurePolicy = ToolDisclosurePolicy.allowEntityTypesByToolName(
        Map.of("sendEmail", List.of("EMAIL_ADDRESS")));
GuardrailsPolicy.Privacy privacySettings = GuardrailsPolicy.Privacy.builder()
        .toolDisclosurePolicy(toolDisclosurePolicy)
        .build();
policy = policy.mutate()
        .privacy(privacySettings)
        .build();
```

This allows the application's `sendEmail` tool to receive original `EMAIL_ADDRESS` values.
Other entity types and tools keep opaque tokens.

See [tool registration and MCP integration](docs/privacy.md#per-tool-original-disclosure).
For custom disclosure logic, use a [separate policy class](docs/privacy.md#custom-tool-disclosure-policy).

## Add the Features You Need

| Need | Next step |
| --- | --- |
| Detect your own employee or customer identifiers | [Java rules and presets](docs/privacy.md#local-rules-and-presets) |
| Broader detection or NER models | [Presidio and OpenNLP choices](docs/privacy.md#choose-an-analyzer) |
| Per-user tool visibility and execution | [Spring Security setup](docs/security.md#add-the-spring-boot-starter) |
| Input and optional output inspection | [Content inspection setup](docs/inspection.md#set-up-input-inspection) |
| Tokenize, redact or block PII in the final answer | [Output protection](docs/privacy.md#output-policy-and-streaming) |
| Diagnose unexpected behavior | [Troubleshooting](docs/troubleshooting.md) |

## Sample

The sample application uses fixed examples to illustrate Local Tool, RAG, MCP, Security and Inspection.
See the [Sample Guide](docs/sample.md) for screenshots and run instructions.

<p align="center">
  <a href="docs/images/privacy-boundary-inspector-demo.gif"><img src="docs/images/privacy-boundary-inspector-demo.gif" alt="Inspector showing Local Tool, RAG, MCP, Security and Inspection" width="960"></a>
</p>

<p align="center">
  <strong>Demo videos:</strong>
  <a href="https://youtu.be/IeeA5ogIX_I">English</a> ·
  <a href="https://youtu.be/vir-x78e9j8">한국어</a>
</p>

## Compatibility

| Component | Minimum supported version |
| --- | --- |
| Java | 17 |
| Spring AI | 2.0.0 |
| Spring Boot | 4.0.0 |
| Spring Security | 7.0.0 |

We continue to verify compatibility and support new releases of Java, Spring AI, Spring Boot
and Spring Security as they become available.
The Boot starter uses the Spring Security version selected by the Spring Boot BOM.

## Documentation

- [Getting Started](docs/getting-started.md): common setup and first steps for privacy protection,
  tool authorization and content inspection
- [Configuration](docs/configuration.md): dependencies, client creation and reusable policies
- [Privacy Protection](docs/privacy.md): analyzers, tokenization, tool disclosure,
  output protection and processing limits
- [Spring Security Tool Authorization](docs/security.md): tool authorization
  policy, advanced tool configuration, Tool Search, and security-context handling
- [Content Inspection](docs/inspection.md): opt-in rules, local ONNX classifiers and HTTP guard models
- [Architecture](docs/architecture.md): modules and model, tool, and session
  execution flow
- [Threat Model](docs/threat-model.md): protected assets, trust boundaries,
  controls, limitations, and separately managed areas
- [Evaluation and Benchmarks](docs/evaluation.md): verification coverage and
  interpretation limits
- [Sample Guide](docs/sample.md): Inspector scenarios, runtime endpoints,
  and the evidence each view verifies
- [Full Sample Guide](samples/spring-ai-demo/README.md): optional analyzer and
  real-model integration examples

## Build and Verify

```bash
./gradlew --no-daemon clean check
```

This command runs the repository's tests and verifies its module rules.
Run `./gradlew verifyDocTranslations` to check translation synchronization.
See [Evaluation](docs/evaluation.md) for the demo analyzer
regression test and JMH benchmarks.

## Contributing

See [Contributing](CONTRIBUTING.md). All contributions are provided under the
Apache License 2.0.

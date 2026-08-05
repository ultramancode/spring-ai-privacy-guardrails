# Spring AI Privacy Guardrails

[English](README.md) | [한국어](README.ko.md)

<p align="center">
  <img src="docs/images/hero.svg" alt="Spring AI Privacy Guardrails execution boundary" width="100%">
</p>

Keep detected PII out of the model. Reveal only what each trusted tool needs.
Protect every tool result before it leaves the tool boundary.

Spring AI Privacy Guardrails combines a Spring-independent privacy core with a
production-oriented Spring AI integration for chat, RAG, memory, tool calling,
and output boundaries. Pluggable analyzers find sensitive spans; this project
turns that evidence into request-scoped enforcement.

## Why It Exists

A detector answers **what text is sensitive**. A Spring AI application still
has to decide **where the original value may travel**.

This project provides the missing execution boundary:

```text
user + memory + RAG
        ↓
detect → resolve → request-scoped opaque tokens → model
                                            ↓
                         allow listed entity types per tool
                                            ↓
                         retokenize result → model/output
                                            ↓
                                   remove request mapping
```

## Choose a Starter

Most applications declare one primary starter:

| Use case | Declare |
| --- | --- |
| General PII with Presidio (recommended) | `spring-ai-privacy-guardrails-presidio-spring-boot-starter` |
| Regex rules or custom analyzers only | `spring-ai-privacy-guardrails-spring-boot-starter` |
| Existing compatible OpenNLP models in a JVM-only deployment | `spring-ai-privacy-guardrails-opennlp-spring-boot-starter` |

The Presidio and OpenNLP starters already include the base starter, core,
Spring AI integration, and Spring Boot baseline. Do not declare the base
starter alongside either provider starter. The base starter intentionally
includes no Presidio or OpenNLP provider.

Adding only a provider starter dependency does not activate the privacy
infrastructure or its Presidio or OpenNLP analyzer. Explicitly enable the
global privacy switch and the analyzer you intend to use in `application.yml`,
then configure it as shown in
[Configuration](docs/configuration.md#artifacts).

## Quick Start

> **Pre-release:** Version `0.1.0` is not yet available from Maven Central.
> Until the first release, clone this repository and use the checked-in sample
> or build from source. The dependency coordinate below is the planned
> first-release coordinate and will not resolve before it is published.

For a no-service first run, use the base starter with a small regex rule for an
application-specific identifier:

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-spring-boot-starter:0.1.0"
}
```

```yaml
spring:
  ai:
    privacy:
      enabled: true
      output:
        enabled: true
        action: tokenize
      regex:
        enabled: true
        rules:
          - entity-type: EMPLOYEE_ID
            pattern: "\\bEMP-\\d{4}\\b"
            score: 0.90
```

Select the `ChatClient` that needs protection by applying the starter-managed
boundary explicitly:

```java
@Bean
ChatClient privacyChatClient(
        ChatClient.Builder builder,
        PrivacyChatClientConfigurer privacyConfigurer
) {
    return privacyConfigurer.configure(builder).build();
}
```

The configurer installs the mandatory advisor bundle once and leaves other
`ChatClient` instances unchanged. A protected builder's `clone()` and a
protected client's `mutate()` already copy that bundle; configuring the copy
again creates duplicate lifecycle boundaries and fails before model execution.

Enabling privacy without a `PiiAnalyzer` fails startup. Analyzers compose under
the default `UNION` mode, while `REQUIRE_ALL` fails closed with a sanitized
exception if any configured analyzer fails. These advisors protect configured
`ChatClient` calls only; direct `ChatModel` calls require separate application
protection. See [Configuration](docs/configuration.md) for advanced profiles,
policies, direct builders, scoped tools, and test support.

## Capability-Scoped Tools

Wrapped tools are default-deny. Configure only the canonical entity types that
one exact, case-sensitive tool name needs:

```yaml
spring:
  ai:
    privacy:
      enabled: true
      tools:
        disclosures:
          customerLookup:
            - CUSTOMER_ID
```

```java
@Bean
ToolCallback customerLookup(
        PrivacyToolCallbackFactory toolCallbackFactory,
        CustomerLookupTool delegate
) {
    return toolCallbackFactory.wrap(delegate);
}
```

For MCP or another `ToolCallbackProvider`, wrap the provider on the explicitly
selected client instead of freezing its callbacks into an array:

```java
@Bean
ChatClient privacyMcpChatClient(
        ChatClient.Builder builder,
        PrivacyChatClientConfigurer privacyConfigurer,
        PrivacyToolCallbackFactory toolCallbackFactory,
        ToolCallbackProvider mcpTools
) {
    return privacyConfigurer.configure(builder)
            .defaultTools(toolCallbackFactory.wrapProvider(mcpTools))
            .build();
}
```

When one client has several dynamic sources, combine them before registration:

```java
toolCallbackFactory.wrapProviders(mcpTools, localToolProvider)
```

Each source must return raw callbacks. The combined provider refreshes once per
request, preserves source order, and rejects duplicate names. Registration
remains default-deny: only configured entity types for the exact final tool name
are restored, and new PII in tool results is retokenized before the next model
call. See [Configuration](docs/configuration.md#tool-disclosure) for provider
refresh, metadata, failure, and MCP prefix contracts.

Unwrapped callbacks and providers are explicitly outside this contract.
Register a wrapped callback or provider only with a `ChatClient` configured by
the same privacy starter. An ordinary, unprotected client must use the original
callback or provider; invoking a wrapper without an active privacy session
fails before the delegate runs.

## Detection vs. Enforcement

<p align="center">
  <img src="docs/images/execution-boundary.svg" alt="Detection providers, the privacy core, and the Spring AI execution integration" width="100%">
</p>

| Layer | Responsibility | Spring dependency |
| --- | --- | --- |
| Analyzer providers | Return type, source offsets, and confidence | No |
| Privacy core | Canonicalize, filter, resolve overlaps, own token sessions | No |
| Spring AI integration | Enforce model, tool, output, and lifecycle boundaries | Spring AI |

See [Architecture](docs/architecture.md) for provider and module trade-offs.

## What Is Enforced

- One opaque `PrivacySession` per ChatClient request.
- Final model-bound protection after memory and RAG advisors.
- Stable identity inside a request and a random namespace across requests.
- Privacy-first overlap resolution with explicit provider failure policies.
- Exact-tool, entity-type-scoped disclosure with no wildcard capability.
- Lossless JSON tool argument protection, including
  exponent-form numeric PII with original numeric-type restoration only inside
  authorized tools.
- Retokenize ordinary tool results before they return to the model, and protect
  developer-enabled `returnDirect` results before application return.
- When output protection is enabled, support stream APIs but buffer and validate
  the complete logical response before replay instead of delivering protected
  text token by token.
- Cleanup on success, error, cancellation, and stream termination.
- Sanitized analyzer and privacy-boundary failures, while authorized delegate
  tool exceptions propagate unchanged so retry, fallback, and diagnostics remain
  under host-application control.

`returnDirect` controls the tool loop, and the library preserves each delegate's
setting. Mixed registration is allowed: Spring AI returns immediately only when
all callbacks selected in that response are direct; otherwise tokenized results
continue through the model loop. Every tool result is retokenized first, so a
direct result remains `TOKENIZE`-protected even when output protection is off.

`output.enabled` controls only final model-output inspection and defaults to
`false`. When disabled, input, model, and tool protections remain active and
streaming stays incremental, but final model output is outside this library's
inspection boundary. When enabled, each logical response is buffered and
`TOKENIZE`, `REDACT`, or `BLOCK` is applied before replay.

The normal build runs focused unit and integration tests across model, tool,
output, and lifecycle boundaries.

## Runnable Inspector

The sample includes a deterministic local `ChatModel`; no cloud credentials are
required.

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:run
```

Open `http://127.0.0.1:8080` to run the sample-only **Privacy Boundary
Inspector**. It shows analyzer evidence, the actual tokenized model input,
model-issued tool arguments, the single entity type disclosed inside the CRM
tool, result retokenization, and `activeSessionsAfterCall = 0`.

<p align="center">
  <img src="docs/images/privacy-boundary-inspector-demo.gif" alt="Privacy Boundary Inspector showing zero raw PII at the model, one scoped tool disclosure, and zero active sessions after the call" width="960">
</p>

Text outside the demo's configured detector rules can be returned unchanged by
its deterministic model. The inspector does not expose token mappings. API
examples and the optional Docker Presidio and JVM-only OpenNLP profiles, plus
the local Streamable HTTP MCP round-trip test, are documented in the
[sample guide](samples/spring-ai-demo/README.md).
The same guide includes an opt-in OpenAI-compatible live harness for verifying
blocking, streaming, tool-loop, and `returnDirect` boundaries against an actual
model endpoint. Normal repository checks compile that harness but never make a
cloud request.

## Artifacts and Modules

The table lists four non-starter runtime building blocks plus the separate test
support publication. Provider starters listed above resolve matching runtime
modules transitively; test support is added separately to an application's test
scope:

| Artifact | Purpose |
| --- | --- |
| `spring-ai-privacy-guardrails-core` | Analyzer SPI, resolution, sessions, regex, and tokenization |
| `spring-ai-privacy-guardrails-spring-ai` | Advisors and scoped tool wrappers |
| `spring-ai-privacy-guardrails-presidio` | Presidio Analyzer HTTP adapter |
| `spring-ai-privacy-guardrails-opennlp` | JVM-only adapter for user-supplied OpenNLP models |
| `spring-ai-privacy-guardrails-test` | Optional model/tool probes and AssertJ assertions; add with `testImplementation` |

These are publication boundaries, not parallel policy implementations. Core
owns evidence resolution and token identity; the Spring AI module owns model
and tool execution boundaries. The build verifies the same dependency graph in
source modules and published metadata.

The repository-only benchmark module is never published as a library artifact.
Its reproducible JMH profiles and interpretation rules are documented in
[Evaluation](docs/evaluation.md#repository-benchmarks).

## Compatibility and Status

No public version has been released. Until the first public release, APIs and
configuration may change without compatibility shims or migration paths.

| Component | Verified version |
| --- | --- |
| Java baseline | 21 |
| Java compatibility CI | 25 |
| Spring AI | 2.0.0 |
| Spring Boot | 4.1.0 |
| Gradle wrapper | 9.6.1 |

CI runs the complete suite on Java 21 and 25.

## Security Boundary

This library reduces accidental PII disclosure in Spring AI execution paths;
it is not a complete DLP system or a legal-compliance guarantee.

Applications remain responsible for authentication, authorization, transport
security, log redaction, persistent ChatMemory/vector/database protection,
retention, detector calibration, and unsupported provider/application metadata
or non-text media. Analyzer
services belong inside an authenticated and encrypted network boundary.

Read [Security](SECURITY.md), the [Threat Model](docs/threat-model.md), and
[Architecture](docs/architecture.md) before production use.

## Build and Verify

```bash
./gradlew --no-daemon clean check
```

This runs the test suite and the repository's module and documentation checks.
[Evaluation](docs/evaluation.md) documents the synthetic regex baseline and its
limitations.

## Contributing

See [Contributing](CONTRIBUTING.md). Contributions are licensed under Apache
License 2.0.

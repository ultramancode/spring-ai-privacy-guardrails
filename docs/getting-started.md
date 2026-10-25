---
description: >-
  Make a first privacy-protected call with built-in presets, then add tools, analyzers and output features as needed.
---

# Getting Started

**English** | [한국어](ko/getting-started.md)

Add privacy protection to an existing Spring AI app. **Complete steps 1–4 for your first call**,
then choose optional features. No external analyzer service or custom rule is needed for that call.

[Try the demo without an API key](sample.md#run-the-inspector) · [Tool authorization only](security.md#add-the-spring-boot-starter) · [Content inspection only](inspection.md#set-up-input-inspection)

## Prerequisites

Use Java 17, Spring AI 2.0.0 and Spring Boot 4.0.0 or later. Your app must provide a `ChatModel`
bean and its model connection settings, including credentials if it calls an external model.
The client starter supplies `GuardrailsChatClientFactory`.

<a id="add-the-client-starter"></a>

## 1. Add the Client Starter

These examples use the development version `0.4.0-SNAPSHOT`. First run `./gradlew publishToMavenLocal`
from this repository. In the consuming app, use Gradle's `mavenLocal()` in your development repository
configuration, or Maven's default local repository. For a released version, use the same actually
published version for every Guardrails module. See [local snapshot setup](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/516982fd6644f50f38b2ff3b6c4ce0a28c2fab39/CONTRIBUTING.md#development).

Add to `build.gradle`:

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-chat-client-spring-boot-starter:0.4.0-SNAPSHOT"
}
```

For Maven, add to `dependencies` in `pom.xml`:

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-chat-client-spring-boot-starter</artifactId>
    <version>0.4.0-SNAPSHOT</version>
</dependency>
```

<a id="enable-built-in-presets"></a>

## 2. Enable Built-in Presets

Add to `src/main/resources/application.yml`:

```yaml
spring:
  ai:
    privacy:
      rules:
        enabled: true
        presets: [COMMON, KR]
```

`COMMON` covers supported email and card-number formats. `KR` covers Korean mobile-phone and
resident-registration-number formats. No custom rule bean is needed. Presets are not selected by
language, and `COMMON` must also be selected explicitly. Review the [built-in scope](privacy.md#built-in-rules).

<a id="create-a-protected-client"></a>

## 3. Create a Protected Client

Place `PrivacyConfiguration.java` under your application's component-scan package and add your
project's `package` declaration. Analyzer configuration alone does not protect existing clients,
so use this bean:

```java
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsPolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PrivacyConfiguration {
    @Bean
    ChatClient privacyChatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
        GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder().privacy().build();
        return guardrailsChatClientFactory.builder(chatModel, guardrailsPolicy).build();
    }
}
```

<a id="call-and-check"></a>

## 4. Call and Check

Place `PrivacyExample.java` in the same scan scope and add your project's `package` declaration.
Inject `PrivacyExample` into an existing controller or application entry point and call `reply()`:

```java
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class PrivacyExample {
    private final ChatClient privacyChatClient;

    public PrivacyExample(@Qualifier("privacyChatClient") ChatClient privacyChatClient) {
        this.privacyChatClient = privacyChatClient;
    }

    public String reply() {
        return this.privacyChatClient.prompt()
                .user("Contact alice@example.com or 010-2345-6789.")
                .call()
                .content();
    }
}
```

The model receives input of this form. `<opaque>` stands for a request-scoped random value:

```text
Contact [PII_EMAIL_ADDRESS_1:<opaque>] or [PII_PHONE_NUMBER_1:<opaque>].
```

The value returned by `reply()` is the model's generated answer, so it need not equal that string.
To verify actual model input in your application, use a test model probe from [Test Support](privacy.md#test-support).
To see enforcement independently of your app setup, run the [Inspector's fixed examples](sample.md).
Repeated values reuse their token within a request, and mappings are discarded when the request ends.
Do not depend on token internals.

This completes the first protected call. Direct calls to `ChatModel` bypass this boundary.
If originals remain, see [model-input troubleshooting](troubleshooting.md#raw-input).
Final-response protection is off by default and can be enabled below.

<a id="choose-an-analyzer"></a>

## Choose an Analyzer or Extension

| Need | Setup and scope |
| --- | --- |
| Supported email, card, Korean mobile-phone and resident-registration-number formats | Built-in presets above. Review [formats and limits](privacy.md#built-in-rules). |
| Application-specific employee or customer identifiers | [Java rules and presets](privacy.md#local-rules-and-presets). Register, then select the rules. |
| Broader PII types in natural language | [Presidio](#use-presidio). Provide an external service and NLP/recognizers for your target language. |
| JVM-local NER | [OpenNLP](#use-opennlp). Supply compatible models for the target language and entities. |

KR is not general Korean name or address recognition. The sample's Presidio profile uses English
NLP and does not configure Korean NER. Documentation and UI language do not determine detection coverage.

<a id="local-and-mcp-tools"></a>

## Optional: Disclose Originals to Local and MCP Tools

This example gives a customer lookup tool only the original customer ID. `CUSTOMER_ID` is not in
the built-in presets, so both rule definition and selection are required. Extend the YAML to:

```yaml
spring:
  ai:
    privacy:
      rules:
        enabled: true
        presets: [COMMON, KR]
        include: [customer-id]
```

Place `CustomerToolConfiguration.java` in the same scan scope and add your project's `package`
declaration. The tool returns fixed data to demonstrate configuration. Replace it with your
application's customer lookup implementation:

```java
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class CustomerToolConfiguration {
    @Bean
    PiiRule customerIdRule() {
        PatternDefinition pattern = PatternDefinition.builder()
                .expression("(?<![A-Za-z0-9_])CUST-[0-9]{6}(?![A-Za-z0-9_])")
                .score(0.90)
                .build();
        return PatternPiiRule.builder()
                .id("customer-id")
                .entityType("CUSTOMER_ID")
                .addPattern(pattern)
                .build();
    }

    @Bean
    ChatClient customerChatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
        GuardrailsPolicy toolPolicy = GuardrailsPolicy.builder()
                .privacy(GuardrailsPolicy.Privacy.builder()
                        .toolDisclosurePolicy(ToolDisclosurePolicy.allowEntityTypesByToolName(
                                Map.of("customerLookup", List.of("CUSTOMER_ID"))))
                        .build())
                .build();
        return guardrailsChatClientFactory.builder(chatModel, toolPolicy)
                .defaultTools(new CustomerLookup())
                .build();
    }

    public static class CustomerLookup {
        @Tool(description = "Look up a customer by customer ID")
        public String customerLookup(String customerId) {
            return "Customer " + customerId + ", email alice@example.com";
        }
    }
}
```

Inject `@Qualifier("customerChatClient")` and ask it to `Look up CUST-123456.`. If the model calls
`customerLookup`, the tool receives the original `CUST-123456`, and the customer ID and email in
its result are tokenized before returning to the model. A real model may choose not to call a tool.
Use the [Local Tool demo](sample.md#local-tool) for a reproducible tool call.

### Use an MCP Provider

In the bean method above, also accept the `ToolCallbackProvider mcpToolCallbackProvider` supplied by
your application's MCP configuration. Replace `.defaultTools(new CustomerLookup())` with
`.defaultTools(mcpToolCallbackProvider)`. The policy must match the provider's actual
`ToolDefinition.name()`, including case and any prefix. Providers resolve their current tools for
each request. See the [MCP demo](sample.md#mcp).

Request-specific `.tools(...)` uses the same policy. Tool results, including `returnDirect`, are
protected before reaching the model or application. Separately configured `ToolCallingManager`
or `ToolCallbackResolver` execution paths need their own protection integration.
See [disclosure rules](privacy.md#per-tool-original-disclosure).

<a id="use-presidio"></a>

## Optional: Use Presidio

Presidio is an open-source framework for PII detection and de-identification.
Use the Presidio starter when you want to detect PII beyond
application-specific patterns through an external Presidio Analyzer service.

Add the Presidio starter alongside the [client starter](#add-the-client-starter):

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

Create the client as shown in [Protect a ChatClient](#create-a-protected-client).

Local rules and Presidio may also be enabled together. In the default `UNION` mode,
all configured analyzers run and their detection findings are merged. The
default `REQUIRE_ALL` failure policy fails the request if any configured
analyzer fails. Review
[Privacy Protection](privacy.md#detection-and-resolution) before combining
analyzers in production.

<a id="use-opennlp"></a>

## Optional: Use OpenNLP

Use the OpenNLP starter when detection should run inside the application JVM
with application-supplied compatible OpenNLP models. Add it alongside the
[client starter](#add-the-client-starter).

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

<a id="protect-final-responses"></a>

## Optional: Protect Final Responses

Final-response privacy protection is disabled by default. Enable it in the client policy:

```java
GuardrailsPolicy outputPolicy = GuardrailsPolicy.builder()
        .privacy(GuardrailsPolicy.Privacy.builder()
                .outputProtection().tokenize()
                .build())
        .build();
ChatClient outputClient = guardrailsChatClientFactory.builder(chatModel, outputPolicy).build();
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

<a id="custom-analyzers"></a>

## Optional: Custom Analyzers

Applications can provide custom `PiiAnalyzer` Spring beans when local rules, Presidio,
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

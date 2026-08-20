# Configuration and Usage

[English](configuration.md) | [한국어](ko/configuration.md)

This document is the comprehensive application-facing reference for Spring AI
Privacy Guardrails. The base Spring Boot starter provides the `core` module and
the Spring AI integration boundary. Analyzer-specific Spring Boot starters add
their analyzer integration without defining a separate privacy policy.

## Starter Selection

Choose the starter or starters that match the analyzers you plan to use.

| Starter | Dependency | Use |
| --- | --- | --- |
| Presidio Spring Boot starter | `io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.2.0` | For detecting PII beyond application-specific patterns. Includes the base Spring Boot starter, Presidio HTTP integration, and conditional health support. |
| Base Spring Boot starter | `io.github.ultramancode:spring-ai-privacy-guardrails-spring-boot-starter:0.2.0` | For Regex or custom analyzers. Does not include a separate analyzer integration. |
| OpenNLP Spring Boot starter | `io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.2.0` | Advanced JVM-only configuration for applications that already own compatible NER models. |

Adding a starter dependency does not enable privacy protection automatically.
Explicitly enable global privacy and each analyzer you want to use. For
Presidio, also configure `analyzer-url` when the service does not run at
`http://localhost:5002`.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      presidio:
        enabled: true
```

Analyzers may be combined. The Presidio Spring Boot starter already includes
the base Spring Boot starter, so a Presidio + OpenNLP setup declares the two
analyzer starters without adding the base starter separately. Every selected
analyzer receives the source text, so configure only the combination you need.

## Apply Privacy Protection to ChatClient

Enabling the starter and an analyzer does not automatically apply protection to
every `ChatClient`. Apply `PrivacyChatClientConfigurer` to each
`ChatClient.Builder` that needs privacy protection.

```java
@Bean
ChatClient chatClient(
        ChatClient.Builder builder,
        PrivacyChatClientConfigurer privacyConfigurer
) {
    return privacyConfigurer.configure(builder).build();
}
```

`PrivacyChatClientConfigurer` configures the privacy boundaries required for
input, model calls, tool execution, and request lifecycle handling. When output
protection is enabled, it also adds the output boundary.

If a protected `ChatClient` or builder was derived with `mutate()` or `clone()`,
do not apply `PrivacyChatClientConfigurer` again.

```java
ChatClient protectedClient = privacyConfigurer.configure(builder).build();
ChatClient derivedClient = protectedClient.mutate().build();
```

The privacy boundary can be used with other Spring AI Advisors. However, if a
separate Advisor adds or changes input, tool, or response data outside the
privacy boundary, that content may not be protected automatically. Use
`PrivacyChatClientConfigurer` instead of manually composing its individual
components so the privacy boundaries remain correctly ordered.

Tool names, descriptions, and JSON schemas are also checked for PII before they
are sent to the model.

The boundary supports the standard Spring AI `UserMessage`, `SystemMessage`,
`AssistantMessage`, and `ToolResponseMessage` classes, plus
`DeepSeekAssistantMessage`. Other provider-specific `Message` subclasses and
application-defined `Message` implementations are rejected. This prevents PII
in unknown fields from passing through unprotected.

## Configuration Properties

Unless a full path is shown, all properties in the following table are
configured under `spring.ai.privacy`.

| Property | Default | Meaning |
| --- | --- | --- |
| `spring.ai.privacy.enabled` | `false` | Enables privacy-protection components. Apply `PrivacyChatClientConfigurer` separately to every `ChatClient.Builder` that requires protection. |
| `analysis.language` | `en` | Case-insensitive ASCII language code, canonicalized to lowercase before it is passed to analyzers. |
| `analysis.included-entity-types` | empty | Detection allowlist. This does not register trusted types. |
| `analysis.minimum-score` | `0.0` | Global confidence floor. |
| `analysis.mode` | `UNION` | Detection-evidence selection strategy. |
| `analysis.primary-provider` | unset | Case-insensitive ID of the primary analyzer used by `PRIMARY` and `PRIMARY_WITH_FALLBACK` modes and the `REQUIRE_PRIMARY` failure policy. |
| `analysis.supplemental-providers` | empty | IDs of supplemental analyzers that run with the primary analyzer to extend detection coverage. |
| `analysis.failure-policy` | `REQUIRE_ALL` | Failure policy for analyzer availability. |
| `analysis.provider-minimum-scores` | empty | Confidence floor by provider ID. The effective floor is the greater of the global and provider-specific values. |
| `analysis.entity-aliases` | empty | Explicit mappings from analyzer entity labels to canonical types. |
| `analysis.type-conflict-fallback` | `PII` | Type used when an overlap type conflict cannot be resolved. |
| `output.enabled` | `false` | Enables output protection. |
| `output.action` | `TOKENIZE` | Selects `TOKENIZE`, `REDACT`, or `BLOCK`, which throws a typed exception. |
| `output.block-exception-message` | `Response blocked by privacy guardrail.` | Safe message used by a `BLOCK` exception. |
| `response-inspection.max-stream-frames` | `1024` | Maximum number of frames inspected in one streaming response. |
| `response-inspection.max-characters` | `1000000` | Maximum cumulative characters of text-based content inspected in one call or streaming response. |
| `response-inspection.max-media-bytes` | `16777216` | Maximum cumulative media-data size allowed in one response. |
| `response-inspection.stream-idle-timeout` | `60s` | Maximum time to wait without receiving a streaming-response frame. |
| `tools.disclosures` | empty | Entity types that may be restored to original values for specific tools. |
| `regex.enabled` | `false` | Enables application-supplied Regex rules. |
| `regex.rules[].entity-type` | required per rule | Canonical entity type assigned to each rule match. |
| `regex.rules[].pattern` | required per rule | Java regular expression evaluated against source text. |
| `regex.rules[].score` | `0.85` | Confidence assigned to each match. |
| `regex.rules[].capture-group` | `0` | Capturing-group number whose range becomes the detected span. `0` means the complete match. |
| `regex.rules[].validator-id` | unset | Stable ID of an optional `RegexPiiMatchValidator`. This is not a Spring bean name. |
| `presidio.enabled` | `false` | Enables the Presidio analyzer. |
| `presidio.analyzer-url` | `http://localhost:5002` | Base analyzer URI. |
| `presidio.timeout` | `5s` | Timeout for each HTTP attempt, including response-body completion, and for each health check. |
| `presidio.max-retries` | `1` | Number of retries after the initial attempt. |
| `presidio.retry-backoff` | `300ms` | Delay between attempts. |
| `presidio.max-response-bytes` | `8388608` | Maximum Presidio response-body size retained for validation. |
| `presidio.headers` | empty | Additional HTTP headers sent with Presidio requests. `Content-Type` is managed by the library and cannot be configured here. |
| `opennlp.enabled` | `false` | Enables user-supplied local OpenNLP models. |
| `opennlp.tokenizer-model` | unset | Optional tokenizer-model resource. If omitted, `SimpleTokenizer` is used. |
| `opennlp.entity-models` | empty | Canonical entity types mapped to required name-finder model resources. |

Regex `rules`, Presidio `headers`, and OpenNLP `entity-models` are empty by
default. Spring Boot configuration metadata provides IDE completion.
`analysis.language` accepts a 1-to-64-character ASCII identifier made of
alphanumeric segments separated by single hyphens or underscores. Letter case
is ignored, and `core` passes the lowercase canonical form to every analyzer.
Whitespace, punctuation outside that grammar, and empty or repeated separators
are rejected rather than trimmed or repaired.

Adding only a starter dependency leaves privacy auto-configuration disabled, so
no analyzer is required. After setting `spring.ai.privacy.enabled=true`,
configure at least one analyzer. Otherwise, application startup fails.

This library does not limit model-call count, tool-call count, the number of
registered tools, or cumulative agent-loop iterations. Call-count, cost,
concurrency, and tool side-effect limits belong in the application or
orchestration framework. `response-inspection.*` limits do not restrict those
execution counts; they bound the amount and streaming scope of response content
the library must inspect or retain for privacy protection.

### Configuration Typo Diagnostics

The base Spring Boot starter warns when it encounters an unknown property name
inside the fixed `spring.ai.privacy` configuration defined by this library.
Warnings never prevent application startup. Diagnostics run independently of
`spring.ai.privacy.enabled`, so they can still report a top-level `enabled`
misspelling when privacy auto-configuration does not activate.

For example, misspelling `output.enabled` as shown below still allows the
application to start, but records a warning that suggests the correct property
name.

```yaml
spring:
  ai:
    privacy:
      output:
        enabledd: true
```

At the top-level `spring.ai.privacy` namespace, diagnostics check only names that
look like misspellings of `enabled`, so analyzer and application extension
settings are left untouched. Within the fixed `output`, `response-inspection`,
`analysis`, `regex`, and `tools` areas defined by this library, an unknown
property name produces a warning.

Dynamic keys below `analysis.provider-minimum-scores`,
`analysis.entity-aliases`, and `tools.disclosures` are not diagnostic targets.
Entries in the `analysis.included-entity-types` and
`analysis.supplemental-providers` lists are not treated as property names.
Dynamic maps used by analyzer-specific configuration, such as Presidio
`headers` and OpenNLP `entity-models`, are also excluded.

When there is only one likely supported property name, the warning suggests it.
Diagnostic messages never include configuration values, credentials, or dynamic
map keys.

## Detection and Resolution

Analyzers return evidence such as the detected source range, type, and
confidence. The `core` module applies entity aliases, detection allowlists,
confidence floors, analyzer selection, and overlap-resolution rules. Detected
ranges are always interpreted against the original source text. Regex,
Presidio, OpenNLP, and custom `PiiAnalyzer` Spring beans may be combined.

`UNION` mode runs every configured analyzer and merges their detection results.
When one overlapping range fully contains another, the containing range keeps
its type. Partial overlaps are merged so that no substring of the sensitive
value is exposed. If a type conflict cannot be resolved, the result becomes the
generic `PII` type.

Analyzer execution failures are handled according to
`analysis.failure-policy`. With `REQUIRE_ALL`, request processing fails if any
analyzer fails. `REQUIRE_PRIMARY` requires the primary analyzer to succeed but
tolerates other analyzer failures. `ALLOW_PARTIAL` uses results from the
analyzers that succeeded, which may reduce protection coverage; the request
still fails when every analyzer fails.

The following configuration runs Presidio and an application-specific Regex
analyzer together and merges their results in the default `UNION` mode.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      analysis:
        entity-aliases:
          US_SSN: NATIONAL_ID
      presidio:
        enabled: true
      regex:
        enabled: true
        rules:
          - entity-type: EMPLOYEE_ID
            pattern: "(?<![A-Za-z0-9_])EMP-[0-9]{4}(?![A-Za-z0-9_])"
            score: 0.90
```

`PRIMARY` mode runs only the primary analyzer and supplemental analyzers and
treats any configured analyzer outside that set as a configuration error.
`PRIMARY_WITH_FALLBACK` requires the `ALLOW_PARTIAL` failure policy, a primary
analyzer, and at least one configured non-primary analyzer. Supplemental
analyzers always run with the primary analyzer. Fallback analyzers run only
after the primary analyzer fails.

Adding a provider ID to `analysis.provider-minimum-scores` does not register or
enable an analyzer. It only sets the confidence floor applied to detection
results from an analyzer that is already registered. In the example above, the
Regex analyzer runs alongside Presidio and detects `EMPLOYEE_ID`.

Provider IDs are 1-to-128-character ASCII identifiers made of alphanumeric
segments separated by single hyphens (`-`) or underscores (`_`). The `core`
module treats provider IDs case-insensitively and canonicalizes them to
uppercase. Unsupported punctuation or whitespace, invalid or repeated
separators, and duplicate provider IDs are rejected.

Entity labels are 1-to-128-character ASCII identifiers composed of segments of
uppercase letters and digits separated by single underscores (`_`). Lowercase,
whitespace, hyphens (`-`), unsupported punctuation, and empty segments are
rejected instead of repaired. Invalid configuration values or invalid labels
returned by an analyzer are treated as errors.

The canonical entity types recognized by default are `PII`, `PERSON`,
`ORGANIZATION`, `LOCATION`, `EMAIL_ADDRESS`, `PHONE_NUMBER`, `NATIONAL_ID`,
`CREDIT_CARD`, `DATE_TIME`, `IP_ADDRESS`, `URL`, `IBAN_CODE`, `CRYPTO`, `NRP`,
and `MEDICAL_LICENSE`. Analyzer-specific aliases are not registered
automatically. If a label such as Presidio's `US_SSN` is specific to one
analyzer, model, country, or application but should participate in a shared
policy type, map it explicitly through `entity-aliases`.

```yaml
spring:
  ai:
    privacy:
      analysis:
        entity-aliases:
          US_SSN: NATIONAL_ID
          KR_RRN: NATIONAL_ID
```

A well-formed label that is not in the default canonical entity-type set is
treated as `PII`. If the detection allowlist excludes `PII`, that result is
filtered. Map it to an allowed canonical type when needed.
`PiiAnalyzer.trustedEntityTypes()` lets a local analyzer declare entity types
that it trusts in its own detection results.

Adding an entity type to the detection allowlist does not automatically make it
trusted. A trusted type declared by one analyzer does not apply to labels
returned by another analyzer. Entity-alias mappings and explicitly registered
trusted canonical types apply across analyzer results rather than being limited
to a single analyzer.

A custom analyzer implemented directly as `PiiAnalyzer` may be shared across
requests, so it must be thread-safe and reentrant. Each analyzer must provide a
unique provider ID. Apply finite deadlines to blocking work and cooperate with
thread interruption.

One analysis may return at most 100,000 spans
(`PiiAnalyzer.MAX_RESULT_SPANS`). The library does not bound memory allocated
inside a custom analyzer before it returns, so custom analyzers must also bound
their own memory usage.

## Regex Analyzer

The Regex analyzer is intended for application-specific identifiers with
well-defined formats, such as employee or customer IDs, rather than complete PII
detection. Treat regular-expression patterns as trusted application-managed
configuration and avoid unnecessarily complex patterns.

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

Each rule requires `entity-type` and `pattern`; `score` defaults to `0.85` and
`capture-group` to `0`. `validator-id` is optional and refers to the stable ID
returned by an application-provided `RegexPiiMatchValidator`, not its Spring
bean name:

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

Validator IDs use lowercase ASCII letters and digits separated by single
hyphens. They are resolved at startup. Blank, malformed, or unknown IDs, and
duplicate IDs exposed by two or more validator beans, fail startup. Multiple
rules may reference the same validator ID. `isValid()` receives exactly the
candidate selected by `capture-group`; `true` keeps the existing span and score,
and `false` excludes the candidate. Exceptions follow the configured analyzer
failure policy.

`RegexPiiMatchValidator` implementations are shared across requests and must be
thread-safe. Validation candidates may contain PII, so do not log them, include
them in exception messages, or retain them long term. Without `validator-id`, a
rule uses regex-matched candidates without additional validation.

## Presidio Analyzer

```yaml
spring:
  ai:
    privacy:
      enabled: true
      analysis:
        language: en
      presidio:
        enabled: true
        analyzer-url: https://presidio.internal
        timeout: 5s
        max-retries: 1
        retry-backoff: 300ms
        max-response-bytes: 8388608
        headers:
          X-API-Key: ${PRESIDIO_API_KEY}
```

Set `analyzer-url` to the base HTTP(S) address of the Presidio server. When
credentials are required, keep them out of the URL and use `headers` together
with the application's secret-management facilities.

`timeout` applies to each HTTP request through complete Presidio response-body
receipt. Transport failures, `timeout` expiration, HTTP 408/429 responses, and
5xx responses are retried according to `max-retries`; other 4xx responses fail
immediately.

`max-response-bytes` is the maximum Presidio response-body size and defaults to
8 MiB. Increasing it may also increase the maximum memory required to process
one response. A response that exceeds the limit or cannot be processed safely
is treated as an analysis failure.

When Spring Boot health support is available, the Presidio service can also be
included in application health checks.

The local Docker setup is in
[samples/presidio](https://github.com/ultramancode/spring-ai-privacy-guardrails/tree/main/samples/presidio).

## OpenNLP Analyzer (JVM-Only, Optional)

This configuration is suitable when an application wants to analyze PII in the
same JVM by using compatible OpenNLP NER models instead of a separate remote
analyzer service. OpenNLP NER models are not bundled, so provide the model files
separately.

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
          ORGANIZATION: classpath:/models/en-ner-organization.bin
```

When `tokenizer-model` is not configured, OpenNLP `SimpleTokenizer` is used.
Configure `entity-models` with the NER model file for each entity type. Detection
quality may change when the NER model and tokenization strategy are not
compatible, so validate the configuration against representative application
data.

OpenNLP integration is an optional configuration for applications that already
use suitable NER models. It is not the recommended default for general PII
detection.

The
[runnable sample guide](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/samples/spring-ai-demo/README.md#optional-jvm-only-opennlp-adapter)
shows how to prepare models, configure the application, and run a live
integration test.

## Per-Tool Original Disclosure

Tools do not receive original PII by default. Only when a tool needs the real
value should `tools.disclosures` specify the entity types that may be disclosed
to that tool.

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

With the configuration above, only the `customerLookup` tool receives the
original value of `CUSTOMER_ID`. Entity types not listed for disclosure and
tools not registered under `tools.disclosures` continue to receive protected
values.

Wrap every `ToolCallback` used by a privacy-enabled `ChatClient` with
`PrivacyToolCallbackFactory`.

```java
List<ToolCallback> protectedTools = toolCallbackFactory.wrapAll(
        List.of(customerLookup, knowledgeSearch));

ChatClient chatClient = privacyConfigurer.configure(ChatClient.builder(chatModel)
        .defaultTools(protectedTools.toArray(ToolCallback[]::new)))
        .build();
```

When a tool list can change at runtime, as with MCP, wrap the
`ToolCallbackProvider` itself rather than only its current callbacks. Use
`wrapProvider(...)` for one provider or `wrapProviders(...)` to combine several
providers into one protected `ToolCallbackProvider`.

```java
ToolCallbackProvider protectedTools = toolCallbackFactory.wrapProviders(
        mcpTools,
        localToolProvider
);

return privacyConfigurer.configure(builder)
        .defaultTools(protectedTools)
        .build();
```

Tool names in `tools.disclosures` are case-sensitive and must match the final
`ToolDefinition.name()`. If a dynamic provider adds a prefix, configure the
resulting final name. Wildcards are not supported, and every entity type that
requires original disclosure, including `PII`, must be listed explicitly. If a
tool requires no original values, do not register it in `tools.disclosures`.

Before a tool call, the library inspects the input and restores original values
only for entity types permitted for that tool. It inspects the tool result again
and protects PII before the result is passed to the model.

A tool that is authorized to receive originals may see real PII, so make sure
the tool implementation does not expose PII in exception messages or logs.

This feature covers Spring AI's standard `ToolCallback` and
`ToolCallbackProvider` registration paths. Execution through a custom
`ToolCallingManager` or `ToolCallbackResolver` is outside the supported
boundary.

## Output Policy and Streaming

```yaml
spring:
  ai:
    privacy:
      enabled: true
      output:
        enabled: true
        action: tokenize
```

When output protection is enabled, the configured policy is applied to PII
returned from the model or tools to the application.

- `TOKENIZE` consistently replaces the same PII value with the same token within
  one request.
- `REDACT` replaces PII with a typed marker that cannot be restored to the
  original value.
- `BLOCK` blocks output that contains PII and throws
  `PrivacyOutputBlockedException`.

Output protection applies to normal model responses, tool-call arguments, and
`returnDirect` results.

### `returnDirect` Tool Flow

`returnDirect` is a Spring AI setting that controls whether the model is invoked
again after tool execution. A tool with `returnDirect=true` can return its result
as the final response without sending that result back to the model. It does not
enable or disable privacy protection.

| `returnDirect` | Behavior | Use when |
| --- | --- | --- |
| `false` | Protect the tool result and send it back to the model. The model can use the result to compose an answer or call another tool. | The model must continue processing the tool result. |
| `true` | Protect the tool result and allow it to be returned directly as the final response. | The tool result itself should be used as the final response. |

A single `ChatClient` may register both kinds of tools. Spring AI skips the
additional model call only when every tool selected in one model response has
`returnDirect = true`. Otherwise, tool results are returned to the model.

Even when `output.enabled=false`, PII in a directly returned `returnDirect`
result remains tokenized. When output protection is enabled, the final result is
processed according to the configured `TOKENIZE`, `REDACT`, or `BLOCK` policy.

### Final Model Output Inspection

`output.enabled` controls whether the output policy is applied to the final
model-generated response. Disabling it does not disable privacy protection for
inputs, model-call boundaries, or tool results.

| `output.enabled` | Final model-output inspection | Delivery behavior |
| --- | --- | --- |
| `false` | No | The final model output is not inspected separately. Streaming calls may deliver model-generated text incrementally. Other privacy boundaries and fail-safe tokenization of `returnDirect` results remain active. |
| `true` | Yes | The complete response is inspected, then `TOKENIZE`, `REDACT`, or `BLOCK` is applied before delivery. |

The Spring AI streaming API remains usable when output protection is enabled,
but the library must first buffer the complete response for privacy inspection.
Model-generated text therefore cannot be delivered in real time as it is
generated. If real-time streaming is required, leave `output.enabled=false`
and handle privacy protection for final model output in the application.

`response-inspection.*` is independent of `output.enabled`. These settings bound
the amount and streaming scope of content the library must inspect, including
intermediate responses during tool-call processing. When output protection is
enabled, `response-inspection.max-characters` and
`response-inspection.max-media-bytes` also apply to the final response. The
media limit measures data size only; it does not detect PII in image or audio
content.

Separate from configurable limits, internal safety maxima limit memory usage
caused by excessively large or complex inputs. The main maxima are:

| Scope | Maximum |
| --- | ---: |
| One text or JSON payload processed at a Spring AI boundary, or the aggregate content of one `core` value tree | 1,000,000 characters |
| JSON or value-tree nodes | 100,000 |
| JSON or value-tree nesting levels | 128 |
| Output produced after transformation | 8,000,000 characters |

Exceeding a safety maximum raises `PAYLOAD_LIMIT_EXCEEDED` before processing or
result delivery continues.

Ordinary messages and tool results can still receive plain-text privacy
protection when they are not JSON. Malformed JSON is rejected only at a
boundary that specifically requires structured JSON.

When output protection is enabled, reasoning text explicitly supported by the
library receives the same privacy policy.

## Direct `core` Module Usage

This section applies only when calling `PrivacyService` methods in the `core`
module directly rather than using the normal Spring AI starter integration.
Applications that use the starters do not need to manage the sessions or value
structures described below.

When using `PrivacyService` directly, open a `PrivacySession` first and pass its
`handle` to methods such as `analyzeAndTokenize()`.

```java
try (PrivacySession session = privacyService.openSession()) {
    PiiTokenizationResult result = privacyService.analyzeAndTokenize(
            session.handle(), sourceText);
    String protectedText = result.tokenizedText();
    List<ResolvedPiiSpan> spans = result.analysis().spans();
}
```

When an authorized boundary needs original values, use the `detokenize()`
overload that scopes disclosure to specific entity types. A missing, unknown, or
already closed session `handle` cannot be used.

`tokenizeValueTree()` and `detokenizeValueTree()` operate on JSON-compatible
`Map` and `List` structures. Supported values are `null`, booleans, strings,
and numeric values of type `Byte`, `Short`, `Integer`, `Long`, `BigInteger`,
`BigDecimal`, or finite `Float` and `Double` values. `Map` keys must be strings.

These methods do not modify the input objects. They return new transformed
`Map` and `List` containers. Unsupported values, non-string `Map` keys, or
reference cycles raise `TRANSFORMATION_CONFLICT`; size-limit violations raise
`PAYLOAD_LIMIT_EXCEEDED`.

Plain Java objects and Jackson `JsonNode` values cannot be passed directly to
these two methods. Convert them to a supported `Map`/`List` structure first.

## Test Support

The test module provides utilities that record model requests and tool inputs,
along with assertions for checking privacy behavior. The application supplies
the model, tools, and test values used in the example.

```gradle
dependencies {
    testImplementation "io.github.ultramancode:spring-ai-privacy-guardrails-test:0.2.0"
}
```

```java
try (PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService)) {
    ChatModel model = probe.wrapModel(delegateModel);
    ToolCallback tool = probe.wrapTool(customerLookup, toolCallbackFactory);

    // Execute the code under test that uses model and tool.

    assertThatPrivacy(probe)
            .modelRequestsDoNotContainRawValues("Alice", "alice@example.com")
            .modelRequestsContainOpaqueToken("PERSON")
            .toolInputsContain("customerLookup", "Alice")
            .hasNoActivePrivacySessions();
}
```

`PrivacyTestProbe` records the wrapped model requests and tool inputs so they can
be inspected by later assertions. `"Alice"`, `"alice@example.com"`,
`customerLookup`, and `delegateModel` are examples; real tests should use the
application's own test data, model, and tools.

The example shows representative assertions. The full test utilities and
assertions are available through each class's Javadoc and IDE completion.

`PrivacyTestProbe` may capture raw test values, so use it only in test
environments. The try-with-resources pattern above clears captured records when
the probe closes. Use `clear()` to reset only the recorded values while the
probe remains open.

## Privacy-Safe Runtime Observation

Applications can register an optional Spring bean of type
`PrivacyEnforcementObserver` to observe privacy enforcement at starter-managed
boundaries:

```java
@Bean
PrivacyEnforcementObserver privacyEnforcementObserver() {
    return event -> privacyMetrics.record(event.boundary(), event.outcome());
}
```

The observer receives an event each time a request passes through a supported
privacy boundary. `boundary()` identifies where the event occurred, and
`outcome()` describes the result at that boundary. A single request may therefore
produce multiple events. If a request does not pass through a boundary, no event
is delivered for that boundary. A boundary failure does not produce an empty
outcome.

`PrivacyEnforcementEvent` intentionally contains only `boundary()` and
`outcome()`. It never includes raw PII, opaque tokens, entity types, payloads,
tool names, request identifiers, or correlation data.

| Boundary | When the event is delivered | Outcome and meaning |
| --- | --- | --- |
| `MODEL` | After protection of the request sent to the model completes | `PROTECTED` — model-request protection completed |
| `TOOL_INPUT` | After tool-input handling completes | `DISCLOSED` — at least one original PII value was restored under policy<br>`PROTECTED` — no original PII value was restored for delivery to the tool |
| `TOOL_RESULT` | After protection of the tool result completes | `PROTECTED` — tool-result protection completed |
| `APPLICATION_OUTPUT` | When output protection is applied to the final response | `PROTECTED` — output protection completed and the response can be returned<br>`BLOCKED` — the `BLOCK` policy detected PII and raises a block exception instead of returning the response |

`PROTECTED` means that protection at the boundary completed successfully. It
does not indicate whether PII was present or whether content changed. For
`TOOL_INPUT`, `PROTECTED` specifically means that no original PII value was
restored for delivery to the tool.

Streaming application output reports one event after protection of the complete
buffered response finishes. Observer callbacks may run concurrently across
requests. For streaming output, Reactor may invoke the callback on the thread
processing the response stream rather than the thread that first handled the
request; observers must not assume those threads are the same.

Short operations such as logging and metrics updates can run directly. If
network or database I/O is needed, enqueue the work and return promptly.
Non-fatal observer failures are ignored and cannot change privacy enforcement.

The Spring Boot starter automatically connects the registered observer bean.
When constructing `PrivacyModelBoundaryAdvisor`, `PrivacyToolCallbackFactory`,
or `PrivacyOutputAdvisor` directly without the starter's auto-configuration,
pass the observer as a constructor argument. Existing constructors that do not
accept an observer do not deliver observation events.

## Stored Data and Diagnostics

This library protects PII in memory and retrieved documents when they are sent
to the model, but it does not search for and rewrite data that was already
stored. PII stored in `ChatMemory`, vector stores, databases, logs, and traces
must be protected separately by the application.

Response metadata other than reasoning text explicitly supported by the
library, as well as non-text media, is not protected automatically.

`PiiAnalyzerFailureObserver` receives sanitized failure information such as the
provider ID, failure code, processing phase, and attempt count instead of PII or
detailed exception content. Detailed diagnostics from analyzers or tools
authorized to receive originals may contain PII, so manage them in
application-controlled secure logs or monitoring systems.

### Privacy Error Handling

`PrivacyGuardrailException` represents an error that occurred during privacy
processing owned by this library. Use `code()` to identify the error type and
`phase()` to identify the privacy-processing phase where it occurred.

`PrivacyFailureCode` is used only for errors produced by this library.
Exceptions raised by the model provider or by the application's tool itself are
propagated with their original exception type.

The available error types and their detailed meaning are documented in the
`PrivacyFailureCode` Javadoc.

| `phase()` | Meaning |
| --- | --- |
| `ANALYSIS` | PII detection and analysis |
| `TOKENIZATION` | Replace PII with opaque tokens |
| `REDACTION` | Replace PII with redaction markers |
| `DETOKENIZATION` | Restore authorized original values |
| `SESSION` | Privacy-session processing |
| `OUTPUT_POLICY` | Apply the final output policy |
| `TOOL_INPUT` | Inspect tool input and disclose authorized originals |
| `TOOL_EXECUTION` | Validate the tool-execution boundary |
| `TOOL_OUTPUT` | Protect tool execution results |

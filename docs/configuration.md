# Configuration and Usage

[English](configuration.md) | [한국어](ko/configuration.md)

This document is the complete application-facing reference for Spring AI
Privacy Guardrails. The base starter provides the core and Spring AI boundary;
provider starters add one analyzer without creating another privacy policy.

## Artifacts

> **Pre-release:** The `0.1.0` coordinates in this section are planned for the
> first release and do not yet resolve from Maven Central. Until then, clone
> this repository and use the checked-in samples or build from source.

Choose one primary entry point:

| Entry point | Dependency | Use |
| --- | --- | --- |
| Presidio starter | `io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.1.0` | Recommended general-PII profile; includes the base starter, HTTP adapter, and conditional health indicator. |
| Base starter | `io.github.ultramancode:spring-ai-privacy-guardrails-spring-boot-starter:0.1.0` | Regex or custom analyzers; includes no provider adapter. |
| OpenNLP starter | `io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.1.0` | Advanced JVM-only profile for applications that already own compatible NER models. |

All starters default to disabled. Enable global privacy and the selected analyzer
explicitly. For Presidio, set `analyzer-url` when it is not at
`http://localhost:5002`:

```yaml
spring:
  ai:
    privacy:
      enabled: true
      presidio:
        enabled: true
```

Analyzers may be combined. Presidio already includes the base regex
configuration; a Presidio + OpenNLP ensemble declares both provider starters,
not the base starter. Every selected analyzer receives source text, so configure
ensembles deliberately.

## Property Reference

| Property | Default | Meaning |
| --- | --- | --- |
| `spring.ai.privacy.enabled` | `false` | Opt in to privacy infrastructure and the selectable fixed request boundary. |
| `analysis.language` | `en` | Case-insensitive ASCII language code, canonicalized to lowercase and passed to analyzers. |
| `analysis.included-entity-types` | empty | Detection allowlist; it never registers trusted types. |
| `analysis.minimum-score` | `0.0` | Global confidence floor. |
| `analysis.mode` | `UNION` | Evidence selection strategy. |
| `analysis.primary-provider` | unset | Case-insensitive provider ID required by primary modes and `REQUIRE_PRIMARY`. |
| `analysis.supplemental-providers` | empty | Provider IDs that augment a primary provider. |
| `analysis.failure-policy` | `REQUIRE_ALL` | Provider availability contract. |
| `analysis.provider-minimum-scores` | empty | Per-provider-ID floors; effective value is the greater of global and provider. |
| `analysis.entity-aliases` | empty | Explicit analyzer entity-label to canonical-type mappings. |
| `analysis.type-conflict-fallback` | `PII` | Type used for unresolved overlap conflicts. |
| `output.enabled` | `false` | Register output protection. |
| `output.action` | `TOKENIZE` | `TOKENIZE`, `REDACT`, or typed-exception `BLOCK`. |
| `output.block-exception-message` | `Response blocked by privacy guardrail.` | Safe `BLOCK` exception message. |
| `response-inspection.max-stream-frames` | `1024` | Maximum frames inspected per streaming response. |
| `response-inspection.max-characters` | `1000000` | Maximum text and tool-argument characters inspected per call or streaming response. |
| `response-inspection.max-media-bytes` | `16777216` | Maximum known media bytes inspected per call or streaming response. |
| `response-inspection.stream-idle-timeout` | `60s` | Maximum interval without a streaming response frame. |
| `tools.disclosures` | empty | Exact tool names mapped to entity types they may receive as originals. |
| `regex.enabled` | `false` | Enable application-supplied regex rules. |
| `regex.rules[].entity-type` | required per rule | Exact canonical entity type emitted by the rule. |
| `regex.rules[].pattern` | required per rule | Java regular expression evaluated against source text. |
| `regex.rules[].score` | `0.85` | Confidence assigned to every match. |
| `regex.rules[].capture-group` | `0` | Capturing group whose range becomes the detected span. |
| `presidio.enabled` | `false` | Enable the Presidio provider. |
| `presidio.analyzer-url` | `http://localhost:5002` | Base analyzer URI. |
| `presidio.timeout` | `5s` | Timeout for each complete HTTP attempt, including response-body completion, and for each health check. |
| `presidio.max-retries` | `1` | Retries after the initial attempt. |
| `presidio.retry-backoff` | `300ms` | Delay between attempts. |
| `presidio.max-response-bytes` | `8388608` | Maximum Presidio response body retained for validation. |
| `presidio.headers` | empty | Additional exact HTTP request headers; `Content-Type` is library-owned. |
| `opennlp.enabled` | `false` | Enable user-supplied local OpenNLP models. |
| `opennlp.tokenizer-model` | unset | Optional tokenizer-model resource; absence selects `SimpleTokenizer`. |
| `opennlp.entity-models` | empty | Exact canonical entity types mapped to required name-finder model resources. |

Regex `rules`, Presidio `headers`, and OpenNLP `entity-models` default to
empty. Spring Boot configuration metadata provides IDE completion.
`analysis.language` accepts a 1-to-64-character ASCII identifier composed of
alphanumeric segments separated by single hyphens or underscores. ASCII letter case is insignificant and the core
passes the lowercase canonical form to every analyzer. Whitespace, punctuation
outside that grammar, and empty or repeated separators are rejected rather than
trimmed or repaired.
Adding a starter dependency alone leaves every privacy auto-configuration
inactive and does not require an analyzer. After the global switch is enabled,
startup fails if no analyzer is configured rather than exposing an inactive
privacy boundary.
The privacy layer does not impose generation, tool-call cardinality, registered
tool, or cumulative agent-loop execution budgets. Configure call-count, loop,
cost, concurrency, and side-effect budgets in the application or orchestration
framework. The remaining output limits bound only the stream content this
library must inspect or retain to enforce its privacy boundary.

### Configuration typo diagnostics

The base starter logs a warning for likely misspellings in the fixed property
surface owned by this library. This diagnostic auto-configuration is independent
of `spring.ai.privacy.enabled`, so it can also report a likely misspelling of the
global switch when the privacy auto-configuration remains inactive. Warnings do
not fail application startup. The diagnostic remains eager when the host enables
Spring Boot's global lazy initialization and recognizes relaxed operating-system
environment variable names for dashed properties. If a host-provided property
source cannot safely enumerate its property names, diagnostics skip only that
source and continue without logging the source or its exception.

Diagnostics are deliberately narrow. They cover high-confidence typos of the
global switch and fixed fields below `output`, `response-inspection`, `analysis`,
`regex`, and `tools`. They do not inspect provider or application extension
subtrees. Dynamic keys below `analysis.provider-minimum-scores`,
`analysis.entity-aliases`, and `tools.disclosures` are accepted without warning;
provider-owned maps such as Presidio `headers` and OpenNLP `entity-models` are
also outside the base diagnostic scope. Warning messages contain only the fixed
property path and a canonical suggestion, never a configured value, credential,
or dynamic map key.

## Detection and Resolution

Analyzers return source ranges as evidence. Core applies aliases, allowlists,
thresholds, provider selection, and overlap resolution; source text remains the
only source of truth for substrings. Regex, Presidio, custom `PiiAnalyzer`
beans, and OpenNLP may be combined.

`UNION` runs every configured analyzer and combines equal evidence. A unique
span covering an overlap keeps its type, partial overlaps are unioned to avoid
substring leakage, and unresolved type conflicts become `PII` by default.
`REQUIRE_ALL` fails closed with sanitized diagnostics if any provider fails.
`REQUIRE_PRIMARY` requires primary success but tolerates other failures.
`ALLOW_PARTIAL` keeps any successful evidence but can reduce coverage and still
fails when every provider fails.

With the Presidio starter, the following complete provider registration uses
Presidio as primary and an application-specific Regex analyzer as a
supplemental provider:

```yaml
spring:
  ai:
    privacy:
      enabled: true
      analysis:
        mode: primary-with-fallback
        primary-provider: presidio
        supplemental-providers: [regex]
        failure-policy: allow-partial
        entity-aliases:
          US_SSN: NATIONAL_ID
      presidio:
        enabled: true
      regex:
        enabled: true
        rules:
          - entity-type: EMPLOYEE_ID
            pattern: "\\bEMP-\\d{4}\\b"
            score: 0.90
```

`PRIMARY` runs the primary and supplemental providers and rejects configured
analyzers outside that set. `PRIMARY_WITH_FALLBACK` requires `ALLOW_PARTIAL`, a
primary, and at least one configured non-primary analyzer. Supplemental
providers always run with the primary; fallback-only providers run only after
the primary fails. Threshold entries do not register providers. In the example,
Regex therefore protects `EMPLOYEE_ID` normally and becomes the remaining
coverage during a Presidio outage.

Provider IDs are 1-to-128-character ASCII identifiers made of alphanumeric
segments separated by single hyphens or underscores. Core canonicalizes case to
uppercase; invalid separators, punctuation, whitespace, and duplicates fail.

Entity labels are 1-to-128-character uppercase ASCII identifiers made of
alphanumeric segments separated by single underscores. Lowercase, whitespace,
hyphens, punctuation, and empty segments fail rather than being repaired.
Invalid configuration or analyzer output fails its owning contract.

The default registry recognizes these exact canonical types: `PII`, `PERSON`,
`ORGANIZATION`, `LOCATION`, `EMAIL_ADDRESS`, `PHONE_NUMBER`, `NATIONAL_ID`,
`CREDIT_CARD`, `DATE_TIME`, `IP_ADDRESS`, `URL`, `IBAN_CODE`, `CRYPTO`, `NRP`,
and `MEDICAL_LICENSE`. It contains no implicit provider aliases. Provider-,
model-, country-, and application-specific labels such as Presidio's `US_SSN`
must be mapped deliberately when they should share one application policy type:

```yaml
spring:
  ai:
    privacy:
      analysis:
        entity-aliases:
          US_SSN: NATIONAL_ID
          KR_RRN: NATIONAL_ID
```

Well-formed unknown labels become `PII`. If the detection allowlist excludes
`PII`, that result is filtered; map it to an allowed canonical type when needed.
`PiiAnalyzer.trustedEntityTypes()` may declare provider-local trusted types for
a local analyzer. The detection allowlist never grants trust, and one analyzer
cannot grant trust to another provider's label. Registry aliases and explicitly
trusted canonical types remain global.

Custom analyzers are shared singleton collaborators and must expose a unique
provider ID, be thread-safe and reentrant, enforce finite deadlines for blocking
work, cooperate with interruption, and return at most
`PiiAnalyzer.MAX_RESULT_SPANS` (100,000) spans. They remain responsible for
bounding allocations made before returning.

## Regex Provider

Regex fits project-specific identifiers and deterministic local flows, not
complete PII detection. Patterns are trusted configuration executed by Java's
backtracking engine: do not accept them from users, and avoid nested or
ambiguous quantifiers. The span bound cannot interrupt ongoing backtracking.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      regex:
        enabled: true
        rules:
          - entity-type: CUSTOMER_ID
            pattern: "\\bCUST-\\d{6}\\b"
            score: 0.90
            capture-group: 0
```

Each rule requires `entity-type` and `pattern`; `score` defaults to `0.85` and
`capture-group` to `0`. Rule validation and Java pattern-compilation failures
retain their original application-owned exception details. Do not place secrets
in patterns, and protect startup diagnostics accordingly.

## Presidio Provider

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

`analyzer-url` must be a base HTTP(S) URI without user-info, query, or fragment.
Credentials belong in headers and secret management, not URLs or source files.
URI syntax failures retain their original application-owned exception details.
Configured header names must use exact HTTP field-name syntax without surrounding
whitespace. Header-name comparison remains case-insensitive as required by HTTP;
header values are preserved rather than trimmed.
The adapter applies `timeout` through response-body completion and requests
cancellation of the pending client-side HTTP attempt when that deadline expires.
That cancellation request does not guarantee the remote service has already
stopped processing. The adapter retries transport failures, timeouts, HTTP
408/429, and 5xx. Other 4xx responses fail immediately.
`max-response-bytes` must be positive and
defaults to 8 MiB. The same configured byte budget bounds both HTTP collection
and parser document length; increasing it raises the maximum memory exposed to
one analyzer response. Oversized or structurally unsafe responses fail with a
privacy-safe analyzer response error.

When Spring Boot Health is present, the provider contributes a lazy
`presidioHealthIndicator`. It reports only status and a stable failure category,
never URL, headers, response body, or transport exception.

The local Docker profile is in [samples/presidio](https://github.com/ultramancode/spring-ai-privacy-guardrails/tree/main/samples/presidio).

## Optional JVM-Only OpenNLP Provider

This profile is intended for applications that already operate compatible
OpenNLP NER models and require analysis in the same JVM without a Python
sidecar or remote analyzer request. Models are not bundled because their
language, license, training data, and tokenizer compatibility are application
decisions:

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

When `tokenizer-model` is absent, OpenNLP `SimpleTokenizer` is used. An explicitly
blank resource location is invalid rather than equivalent to absence. Entity
types are validated before their model resources are opened. Model-resource and
loader failures retain their original application-owned cause instead of being
replaced by the privacy library. Do not embed credentials in resource locations,
and protect startup diagnostics accordingly. The tokenizer must match the one
used to train the NER models. Detection quality is entirely model-dependent and
must be calibrated by the application; this is an optional deployment profile,
not the recommended general-PII default.

The [runnable sample guide](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/samples/spring-ai-demo/README.md#optional-jvm-only-opennlp-adapter)
provides model download, checksum, profile startup, and opt-in live-test steps.

## ChatClient Boundary

Apply the starter's `PrivacyChatClientConfigurer` to every builder that needs
the complete privacy boundary:

```java
@Bean
ChatClient chatClient(
        ChatClient.Builder builder,
        PrivacyChatClientConfigurer privacyConfigurer
) {
    return privacyConfigurer.configure(builder).build();
}
```

This installs the lifecycle, input, tool-context, tool-call validation, and
model boundaries, plus output protection when enabled. Other builders remain
unchanged: there is no global auto-apply switch. Applying the configurer twice
to one builder is invalid, and the managed advisors are not independently
replaceable Spring beans.

Derived builders retain the privacy bundle:

```java
ChatClient protectedClient = privacyConfigurer.configure(builder).build();
ChatClient derivedClient = protectedClient.mutate().build(); // already protected
```

Do not configure a builder created by `ChatClient.Builder.clone()` or
`ChatClient.mutate()` again.

Unrelated advisors and custom numeric orders are allowed. For direct
composition, use consistent response-inspection limits, place option replacement
before the tool-context boundary, tool-call validation immediately inside the
tool interpreter, model-bound content producers before the model boundary, and
response post-processors inside the lifecycle boundary. The application owns
later mutations; the starter-managed order remains recommended.

Tool names, descriptions, and JSON schemas are PII-checked before model
invocation. Provider call IDs and type labels remain opaque control data.

The guarded boundary supports the exact standard Spring AI `UserMessage`,
`SystemMessage`, `AssistantMessage`, and `ToolResponseMessage` classes, plus the
official `DeepSeekAssistantMessage` with its reasoning and prefix fields.
Other provider-specific subclasses and application-defined `Message`
implementations fail closed because Spring AI's base message contract does not
provide a safe, complete way to copy unknown fields.

## Tool Disclosure

Create participating callbacks only through `PrivacyToolCallbackFactory`:

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
List<ToolCallback> protectedTools = toolCallbackFactory.wrapAll(
        List.of(customerLookup, knowledgeSearch));

ChatClient chatClient = privacyConfigurer.configure(ChatClient.builder(chatModel)
        .defaultTools(protectedTools.toArray(ToolCallback[]::new)))
        .build();
```

For MCP or another dynamic `ToolCallbackProvider`, wrap the provider rather
than a one-time callback list. Use `wrapProvider(source)` for one source or
combine several through `wrapProviders(...)`:

```java
ToolCallbackProvider protectedTools = toolCallbackFactory.wrapProviders(
        mcpTools,
        localToolProvider
);

return privacyConfigurer.configure(builder)
        .defaultTools(protectedTools)
        .build();
```

All callbacks in one protected client must be original inputs to the same
managed factory; duplicate names, raw callbacks, already-wrapped values, and
invalid provider snapshots fail safely. Application-owned provider and accessor
exceptions propagate unchanged, so protect host diagnostics and logs.

Disclosure remains default-deny. Only exact, case-sensitive tool names and
canonical entity types in `tools.disclosures` receive originals. If a dynamic
provider prefixes a name, configure the final `ToolDefinition.name()`. There is
no wildcard, and generic `PII` must also be listed explicitly.

Register wrapped callbacks only with a `ChatClient` configured by the same
privacy starter. Use original callbacks for an unprotected client. Missing or
inactive request context fails before delegate execution. Custom
`ToolCallingManager` and `ToolCallbackResolver` execution is outside the
supported boundary; register callbacks or wrapped providers explicitly through
Spring AI tool options.

An omitted tool receives tokens only, and empty per-tool disclosure lists are
invalid.

The wrapper requires nonblank tool input to be valid JSON and protects JSON
strings, detected numeric scalars, and map keys before selective disclosure. It
removes internal session objects from `ToolContext` before delegate execution
and retokenizes every result as either recursively decoded valid JSON or
arbitrary plain text.
Authorized delegate failures propagate unchanged, preserving the
host application's exception types, causes, retry, fallback, and diagnostics.
Because a disclosed tool may include original PII in its exception, the host
must protect exception handling and logging. The library does not catch and
replace application-owned delegate failures. Failures produced by the privacy
wrapper itself remain safe, typed guardrail failures.

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

`TOKENIZE` preserves request identity, `REDACT` emits typed irreversible labels,
and `BLOCK` throws `PrivacyOutputBlockedException`. Output protection applies
to normal model responses, tool-call arguments, and `returnDirect` results.

### `returnDirect` Tool Flow

`returnDirect` controls whether Spring AI performs another model invocation
after a tool call. It does not enable or disable this library's privacy
boundary. The wrapper copies the delegate tool's `returnDirect` metadata
unchanged.

| `returnDirect` | Result path | Use when |
| --- | --- | --- |
| `false` | Retokenize the ordinary tool result, return it to the model, and allow interpretation, another tool call, or final-answer composition. | The model or agent must continue processing. |
| `true` | Retokenize the tool result. When every callback selected in that response is also direct, Spring AI returns it as a final `returnDirect` generation and the enabled output policy applies at the application boundary. | The tool result is already display-ready or otherwise intended as the final response. |

A client may register both kinds. Spring AI chooses the direct path only when
every callback selected in one response is direct. Otherwise all protected
results return to the model. Mixed registration itself is allowed. With output
protection disabled, fail-safe tokenization remains in a final direct result;
when enabled, the configured output action is applied at the application
boundary.

### Final Model-Output Inspection

`output.enabled` is a separate choice that controls inspection of the final
model-generated response. It does not disable input, model-bound, or tool-result
protection.

| `output.enabled` | Final model-output inspection | Delivery behavior |
| --- | --- | --- |
| `false` | No | Preserve incremental model text; input, model-bound, ordinary tool-result, and fail-safe `returnDirect` tokenization remain active. |
| `true` | Yes | Buffer each complete logical response, apply `TOKENIZE`, `REDACT`, or `BLOCK`, then replay protected frames. |

The Spring AI stream API remains usable when output protection is enabled, but
protected text is not delivered token by token. If live incremental output is
required, leave output protection disabled and treat final model-output privacy
as an application responsibility.

The `response-inspection.*` limits are independent of `output.enabled`: they
protect intermediate model frames when tools are registered. Enabled output
protection also applies its character and media budgets to final non-streaming
responses. With no registered tools and output protection disabled, ordinary
model-response limits remain application policy.

Non-stream privacy processing has these provider-independent hard maxima:

| Scope | Maximum |
| --- | ---: |
| One text or structured payload | 1,000,000 characters |
| Cumulative direct value-tree string, map-key, and numeric content | 1,000,000 characters |
| Cumulative canonical analyzer input | 1,000,000 characters |
| One decoded JSON string/property name or direct value-tree string/map key | 250,000 characters |
| One JSON numeric lexeme or direct value-tree numeric representation | 1,000 characters |
| One exponent-expanded numeric value | 4,096 characters |
| JSON or direct value-tree nodes, including property/map keys | 100,000 |
| JSON or direct value-tree nesting levels | 128 |
| Analyzer spans per complete analysis or direct value-tree call | 100,000 |
| Changed transformation output | 8,000,000 characters |

Character limits use Java `String` UTF-16 code units, not bytes or model
tokens. A JSON scalar and a plain-text payload are not split, so every analyzer
must accept inputs up to the applicable maximum or the application must enforce
a smaller limit. A violation raises `PAYLOAD_LIMIT_EXCEEDED` before delegate
execution or result propagation.

Malformed JSON fails closed only where the boundary requires structured JSON.
Arbitrary tool results and ordinary messages use plain-text protection when they
are not valid JSON; a literal `\uXXXX` sequence in such a channel is plain text.

Enabled streaming protection buffers each complete logical choice before
replay. Supported `reasoningContent` and `thinking` text receives the same
policy, while Google and Anthropic thought-marked content remains separate from
the normal answer. Opaque signatures and unknown typed metadata are preserved.
Unsupported assistant subclasses fail closed when their extra fields cannot be
copied safely.

## Direct Core Usage

Direct callers use the same explicit session model:

```java
try (PrivacySession session = privacyService.openSession()) {
    PiiTokenizationResult result = privacyService.analyzeAndTokenize(
            session.handle(), sourceText);
    String protectedText = result.tokenizedText();
    List<ResolvedPiiSpan> spans = result.analysis().spans();
}
```

Use the entity-scoped `detokenize` overload when an authorized boundary needs
originals. APIs requiring mappings reject missing, unknown, or closed handles.

`tokenizeValueTree` and `detokenizeValueTree` operate on JSON-compatible map/list
trees. Supported scalars are `null`, booleans, strings, and finite values of
type `Byte`, `Short`, `Integer`, `Long`, `BigInteger`, `BigDecimal`, `Float`, or
`Double`. Map keys must be strings.

The methods validate and copy the complete input before transformation and
return new map and list containers. Unsupported values, non-string map keys,
and reference cycles raise `TRANSFORMATION_CONFLICT`; hard-limit violations
raise `PAYLOAD_LIMIT_EXCEEDED`. Convert POJOs and serializer-specific tree types
to the supported map/list form before calling these methods.

## Test Support

```gradle
dependencies {
    testImplementation "io.github.ultramancode:spring-ai-privacy-guardrails-test:0.1.0"
}
```

```java
try (PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService)) {
    ChatModel model = probe.wrapModel(delegateModel);
    ToolCallback tool = probe.wrapTool(customerLookup, toolCallbackFactory);

    assertThatPrivacy(probe)
            .modelRequestsDoNotContainRawValues("Alice", "alice@example.com")
            .modelRequestsContainOpaqueToken("PERSON")
            .toolInputsContain("customerLookup", "Alice")
            .hasNoActivePrivacySessions();
}
```

The test-scoped probe intentionally retains captured text until `clear()` or
`close()`.

## Persistence and Diagnostic Boundaries

Advisors protect the copies of memory and retrieved documents sent to a model.
They do not rewrite already stored `ChatMemory`, vector-store data, databases,
logs, traces, response metadata, unknown provider/application metadata, or
non-text media. Those remain application responsibilities.

`PiiAnalyzerFailureObserver` receives sanitized provider, code, phase, and attempt
count only. Detailed analyzer and authorized-tool diagnostics must be captured
inside a protected application-controlled sink.

### Typed Privacy Failures

`PrivacyGuardrailException` represents failures owned by the privacy library.
Its `code()` is a stable, low-cardinality category and its `phase()` identifies
the privacy operation that created it. Model-provider exceptions and
application-owned tool delegate exceptions propagate unchanged, so they do not
receive a `PrivacyFailureCode`.

`PrivacyFailureCode` is the authoritative list and documents each category in
its Javadoc. The phase is one of `ANALYSIS`, `TOKENIZATION`, `REDACTION`,
`DETOKENIZATION`, `SESSION`, `OUTPUT_POLICY`, `TOOL_INPUT`, `TOOL_EXECUTION`, or
`TOOL_OUTPUT`. Treat a code as a classification, not a universal retry
recommendation. The host application owns retry, fallback, and incident
handling according to its provider contract and side-effect model.

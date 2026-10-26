---
description: >-
  Configure PII detection, tokenization, per-tool disclosure, output protection
  and processing limits.
---

# Privacy Protection

**English** | [한국어](ko/privacy.md)

Privacy protection replaces detected PII with request-scoped opaque tokens before
sending it to a model. Tools receive only the original values allowed by policy,
and their results are protected before reaching the model or application.

Choose analyzers for your data, then select `privacy()` in the client policy.
See [Configuration](configuration.md) for dependencies, client creation and feature selection.

Standard Spring AI messages and `DeepSeekAssistantMessage` are supported. Other
provider-specific or custom messages are rejected to prevent PII in unknown fields
from passing through unprotected.

[Getting started](getting-started.md) · [Rules and presets](#local-rules-and-presets) · [Tool disclosure](#per-tool-original-disclosure) · [Output protection](#output-policy-and-streaming) · [Troubleshooting](troubleshooting.md)

## Choose an Analyzer

The following analyzers provide detection results to the same protection flow.
You can also [combine analyzers](#detection-and-resolution).

| Analyzer | Detection |
| --- | --- |
| [Presidio](#presidio-analyzer) | PII detection through a Presidio service |
| [OpenNLP](#opennlp-analyzer-jvm-only-optional) | Named entity recognition with models in the application JVM |
| [Local rules](#local-rules-and-presets) | Built-in rules or application-defined patterns and validation logic |

To connect another detection model or service, implement `PiiAnalyzer` and register it
as a [custom analyzer](#custom-analyzers).

## Configuration Properties

Unless a full path is shown, all properties in the following table are
configured under `spring.ai.privacy`.

| Property | Default | Meaning |
| --- | --- | --- |
| `analysis.language` | `en` | Case-insensitive ASCII language code, canonicalized to lowercase before it is passed to analyzers. |
| `analysis.included-entity-types` | empty | Detection allowlist. This does not register trusted types. |
| `analysis.minimum-score` | `0.0` | Minimum confidence for accepting a detection, applied to all analyzers. |
| `analysis.mode` | `UNION` | How analyzers run and their results are combined. See [Detection and Resolution](#detection-and-resolution). |
| `analysis.primary-provider` | unset | Case-insensitive ID of the primary analyzer used by `PRIMARY` and `PRIMARY_WITH_FALLBACK` modes and the `REQUIRE_PRIMARY` failure policy. |
| `analysis.supplemental-providers` | empty | IDs of supplemental analyzers that run with the primary analyzer to extend detection coverage. |
| `analysis.failure-policy` | `REQUIRE_ALL` | Failure policy for analyzer availability. |
| `analysis.provider-minimum-scores` | empty | Confidence floor by provider ID. The effective floor is the greater of the global and provider-specific values. |
| `analysis.entity-aliases` | empty | Explicit mappings from analyzer entity labels to canonical types. |
| `analysis.type-conflict-fallback` | `PII` | Type used when an overlap type conflict cannot be resolved. |
| `rules.enabled` | `false` | Enables automatic configuration of a local RULES analyzer. |
| `rules.presets` | `[]` | Selected preset names. COMMON is not implicit. |
| `rules.include` | `[]` | Additional registered rule IDs to execute. |
| `rules.exclude` | `[]` | Registered rule IDs to exclude. |
| `processing.max-text-characters` | `1000000` | Maximum length of one text or JSON document. Batch analysis uses the combined length of all texts. |
| `processing.max-output-characters` | `8000000` | Maximum output length per transformation, including unchanged text. |
| `processing.max-value-tree-characters` | `1000000` | Maximum combined length of string values, map keys, and numeric representations in one value tree or JSON document. Direct value-tree tokenization also bounds combined `BigDecimal` analysis text by this value. |
| `processing.max-value-tree-nodes` | `100000` | Maximum node count in a value tree or JSON document, including containers, scalar values, and map keys. |
| `processing.max-depth` | `128` | Maximum nesting depth in a value tree or JSON document. |
| `processing.max-analysis-segments` | `100000` | Maximum number of texts in one batch analysis. |
| `processing.max-result-spans` | `100000` | Maximum raw spans accepted from analyzers or callers in one analysis. Newly produced analyzer spans are summed across session batches. Reused results are not counted again. |
| `presidio.enabled` | `false` | Enables the Presidio analyzer. |
| `presidio.analyzer-url` | `http://localhost:5002` | Base analyzer URI. |
| `presidio.timeout` | `5s` | Timeout for each HTTP attempt, including response-body completion, and for each health check. |
| `presidio.max-retries` | `1` | Number of retries after the initial attempt. |
| `presidio.retry-backoff` | `300ms` | Delay between attempts. |
| `presidio.max-response-bytes` | `8388608` | Maximum Presidio response-body size in bytes. |
| `presidio.max-response-depth` | `64` | Maximum JSON nesting depth in a Presidio response. |
| `presidio.headers` | empty | Additional HTTP headers sent with Presidio requests. `Content-Type` is managed by the library and cannot be configured here. |
| `opennlp.enabled` | `false` | Enables user-supplied local OpenNLP models. |
| `opennlp.tokenizer-model` | unset | Optional tokenizer-model resource. If omitted, `SimpleTokenizer` is used. |
| `opennlp.entity-models` | empty | Canonical entity types mapped to required name-finder model resources. |

Rule selection lists (`rules.presets`, `rules.include`, and `rules.exclude`), Presidio
`headers`, and OpenNLP `entity-models` are empty by default. Spring Boot configuration
metadata provides IDE completion.

`analysis.language` accepts a 1-to-64-character ASCII identifier made of
alphanumeric segments separated by single hyphens or underscores. Letter case
is ignored, and `core` passes the lowercase canonical form to every analyzer.
Whitespace, punctuation outside that grammar, and empty or repeated separators
are rejected rather than trimmed or repaired.

The starter provides `PrivacyService` from configured `PiiAnalyzer` beans.
For manual client integration with a `PrivacyService`, it also provides
`PrivacyChatClientConfigurer` and `PrivacyToolCallbackFactory`.

### Configuration Typo Diagnostics

The base Spring Boot starter warns when it encounters an unknown property name
inside the fixed `spring.ai.privacy` configuration defined by this library.
Warnings do not prevent application startup.

For example, the following misspelling records a warning suggesting `rules.enabled`:

```yaml
spring:
  ai:
    privacy:
      rules:
        enabledd: true
```

Diagnostics check property names within `processing`, `analysis`, and `rules`.
Unknown top-level sections, analyzer-specific settings, dynamic map keys and list
values are outside this check.

Diagnostic messages never include configuration values, credentials, or dynamic
map keys.

## Detection and Resolution

Analyzers return the positions, types, and confidence scores of detected PII.
PII categories such as `PERSON` and `EMAIL_ADDRESS` are called entity types in
configuration and APIs. The library applies entity aliases, detection allowlists,
confidence floors, analyzer selection, and rules for overlapping ranges to these
results. Positions always refer to the original source text. Local rules,
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
analyzers that succeeded, which may reduce protection coverage. The request
still fails when every analyzer fails.

The following configuration runs Presidio alongside the built-in COMMON and KR rules
and merges their results in the default `UNION` mode.

```yaml
spring:
  ai:
    privacy:
      analysis:
        entity-aliases:
          US_SSN: NATIONAL_ID
      presidio:
        enabled: true
      rules:
        enabled: true
        presets: [COMMON, KR]
```

`PRIMARY` mode runs only the primary analyzer and supplemental analyzers and
treats any configured analyzer outside that set as a configuration error.
`PRIMARY_WITH_FALLBACK` requires the `ALLOW_PARTIAL` failure policy, a primary
analyzer, and at least one configured non-primary analyzer. Supplemental
analyzers always run with the primary analyzer. Fallback analyzers run only
after the primary analyzer fails.

Adding a provider ID to `analysis.provider-minimum-scores` does not register or
enable an analyzer. It only sets the confidence floor applied to detection
results from an analyzer that is already registered. To use an analyzer,
configure `presidio.enabled` or `rules.enabled` as shown above, or register an
analyzer bean yourself.

Provider IDs identify analyzers, for example `RULES`, `PRESIDIO`, and `OPENNLP`.
They are 1-to-128-character ASCII identifiers made of alphanumeric
segments separated by single hyphens (`-`) or underscores (`_`). The `core`
module treats provider IDs case-insensitively and canonicalizes them to
uppercase. Unsupported punctuation or whitespace and invalid or repeated
separators are rejected. Provider IDs must be unique within each `PrivacyService`.

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

### Structured JSON

Ordinary messages and tool results can still receive plain-text privacy
protection when they are not JSON. Malformed JSON is rejected only at a
boundary that specifically requires structured JSON.

For structured JSON, the library preserves property names and analyzes string
and numeric values. It skips string values that are empty or contain only
whitespace. Detected text positions are relative to the value's text.

For example, `{"name":"Alice","city":"Seoul"}` produces two analysis targets:
`Alice` and `Seoul`. External request counts and processing
costs depend on the selected analyzer and the amount of text.

### Custom Analyzers

Implement `PiiAnalyzer.analyze(text, options, limits)` to return spans for one
text.

A custom analyzer may be shared across requests, so it must be thread-safe and
reentrant. Each analyzer must provide a unique provider ID. Apply finite
deadlines to blocking work and cooperate with thread interruption.

`PiiAnalyzer.analyzeSegments(texts, options, limits)` calls `analyze(...)` for
each text by default. Override it to use an external service's batch API.
Return results separated by text in input order, with start and end positions
relative to each text.

Respect the supplied `limits` when collecting results. For batch analysis, the
span limit applies to the entire batch.

## Local Rules and Presets

Define local detection rules in Java and select them through configuration. The core module
includes COMMON and KR presets. Rules run only when selected by a preset or rule ID.

```yaml
spring:
  ai:
    privacy:
      rules:
        enabled: true
        presets: [COMMON, KR]
        include: [employee-id]
        exclude: [credit-card]
```

Register application rules as individual Spring beans:

```java
@Bean
PiiRule employeeIdRule() {
    PatternPiiRule.PatternDefinition pattern = PatternPiiRule.PatternDefinition.builder()
            .expression("(?<![A-Za-z0-9_])EMP-[0-9]{4}(?![A-Za-z0-9_])")
            .score(0.90)
            .validator(candidate -> !candidate.equals("EMP-0000"))
            .build();
    return PatternPiiRule.builder()
            .id("employee-id")
            .entityType("EMPLOYEE_ID")
            .addPattern(pattern)
            .build();
}
```

`PatternPiiRule.builder()` requires an `id`, an `entityType`, and at least one `pattern`.
Use `PiiEntityTypes` constants for library-defined labels, for example
`.entityType(PiiEntityTypes.CREDIT_CARD)`, or a string such as `"EMPLOYEE_ID"` for an application type.

`PatternPiiRule.PatternDefinition` combines a Java regular expression with a score, capture
group, and optional `Predicate<String>`. Its builder requires an expression and a score.
The capture group defaults to 0, which selects the entire match.

A predicate receives the captured candidate and returns `true` to accept it or `false`
to discard it. It can parse the candidate or apply checksum and business checks.
Without a predicate, every match is accepted.

Use `.addPattern(...)` to add one definition or `.addPatterns(...)` to add a collection.
Both methods retain previously added patterns. Patterns are evaluated in the order they
are added, each with its own score, capture group, and predicate.

For direct detection, implement `PiiRule.id()`, `entityTypes()`, and
`analyze(text, options, limits)`. Return `PiiSpan` values with positions in the original text:
UTF-16 offsets, inclusive start, and exclusive end. Return an empty list for no matches.

Preset selection is independent of analysis language.
Use `PiiAnalyzer` for independent remote services or model providers.

### Preset Selection

`PiiRulePreset` defines a named, ordered list of registered rule IDs. A rule may belong
to multiple presets or none. Use `include` to select rules directly. Registering a rule
or preset does not activate it.

`builtIns()` and `builtInsWithScores(...)` register the built-in rules and the COMMON
and KR presets defined in `BuiltInPiiRules.presets()`.

Use `addRule(...)` to register one application rule or `addRules(...)` to register a collection.
Both methods retain previously registered rules. Collections are copied in iteration order.

The catalog expands presets in order, appends `include`, keeps the first occurrence of each
rule ID, then applies `exclude`. COMMON must be selected explicitly.

Rule IDs use 1–64 lowercase ASCII letters or digits separated by single hyphens.
Preset names start with an ASCII letter and contain up to 64 ASCII letters, digits,
hyphens, or underscores. Both are case-sensitive.

Built-in rule IDs are available as `BuiltInPiiRuleIds` constants for Java configuration.
Use `BuiltInPiiPresetNames` constants for built-in preset names and strings for
application-defined names, such as `"COMPANY"`.

Duplicate definitions, unknown references, and an empty active selection are configuration
errors.

Automatic rule configuration is disabled by default. When `rules.enabled=true`,
Boot creates a `RuleBasedPiiAnalyzer` from the configured selection.

Declaring a `RuleBasedPiiAnalyzer` bean replaces automatic rule selection, even when
`rules.enabled=true`. Use `RuleBasedPiiAnalyzer` as the bean method's return type so Boot can
detect it. That bean owns rule registration and selection, including any application rules
and presets. A manually declared analyzer also works when `rules.enabled=false`.
Other analyzer providers can still run alongside it.

To define an application preset, register a `PiiRulePreset` bean. Its name must differ
from built-in presets and other registered presets:

```java
@Bean
PiiRulePreset companyPreset() {
    return new PiiRulePreset("COMPANY", List.of("employee-id", BuiltInPiiRuleIds.EMAIL));
}
```

Select it with `presets: [COMPANY]`. In pure Java, pass the rule created by
`employeeIdRule()` above to `PiiRuleCatalog`:

```java
PiiRule employeeRule = employeeIdRule();
PiiRuleCatalog catalog = PiiRuleCatalog.builder().builtIns()
        .addRule(employeeRule)
        .addPreset(new PiiRulePreset("COMPANY", List.of("employee-id", BuiltInPiiRuleIds.EMAIL)))
        .build();
RuleBasedPiiAnalyzer analyzer = catalog.analyzerBuilder()
        .presets("COMPANY", BuiltInPiiPresetNames.KR)
        .exclude(BuiltInPiiRuleIds.KR_RRN)
        .build();
List<String> ruleIds = analyzer.ruleIds();
List<String> presetNames = analyzer.presetNames();
```

`analyzerBuilder()` starts a builder for a `RuleBasedPiiAnalyzer`. `presets`, `include`,
and `exclude` accept strings or collections and default to empty. Each call copies its
values and replaces that part of the selection.

### Execution and Limits

Selected rules execute as one `RULES` provider and can run alongside Presidio and OpenNLP.
Excluding a local rule affects only that rule. A rule exception or invalid result fails the
entire RULES call and follows the configured provider failure policy. Accepted results must
use declared entity types and valid, nonempty ranges within the source text.

Result limits apply across rules and providers. If another span would exceed
`limits.maxResultSpans()`, a rule must throw `PrivacyGuardrailException` with code
`PAYLOAD_LIMIT_EXCEEDED` and phase `ANALYSIS`. Processing limit failures abort the operation
regardless of provider failure policy.

Rules and predicates must be thread-safe, respect interruption, and remain stable for
the analyzer's lifetime. Choose Java regular
expressions carefully: a single match can backtrack for a long time before interruption is checked.

### Built-in Rules

| Preset | Rule ID | Supported scope |
| --- | --- | --- |
| COMMON | email | ASCII email addresses with dotted DNS domains. The part before `@` supports special characters such as apostrophes and exclamation marks. Addresses with a double-quoted part before `@`, internationalized addresses, and domain literals are unsupported. |
| COMMON | credit-card | 13–17 ASCII digits starting with 1, 3, 4, 50–55, or 6, with optional spaces or hyphens between digit groups. Excludes compact 13-digit numbers starting with 1. Checks Luhn. Does not verify issuance or account status. |
| KR | kr-mobile-phone | South Korean mobile phone numbers: 010 plus eight digits, compact or consistently space/hyphen separated. Supports +82 10 forms. Other 01X prefixes, landlines, and extensions are unsupported. |
| KR | kr-rrn | South Korean resident registration numbers: YYMMDD, a 1–4 century/sex digit, and six digits, with an optional hyphen. Checks calendar validity for 1900–2099. Does not verify identity or issuance. |

Built-in matches have a default score of 0.85. These are heuristic scores, not measured
probabilities. Evaluate detection quality on data representative of your application.
The RRN rule validates the birth date without a checksum requirement,
because the revised issuance scheme uses randomized trailing digits
([Ministry of the Interior and Safety](https://mois.go.kr/plan2020/download/2020plan.pdf)).

Pass a map of built-in rule IDs to scores to `PiiRuleCatalog.Builder.builtInsWithScores(...)` to
set a fixed score for each specified rule. For example, register a Spring bean:

```java
@Bean
RuleBasedPiiAnalyzer localPiiAnalyzer() {
    PiiRuleCatalog catalog = PiiRuleCatalog.builder()
            .builtInsWithScores(Map.of(
                    BuiltInPiiRuleIds.EMAIL, 0.95,
                    BuiltInPiiRuleIds.KR_RRN, 0.70))
            .build();
    return catalog.analyzerBuilder()
            .presets(BuiltInPiiPresetNames.COMMON, BuiltInPiiPresetNames.KR)
            .build();
}
```

Unspecified built-in rules keep their defaults. Each score must be finite and between 0.0
and 1.0, inclusive. Unknown built-in IDs and invalid scores are rejected during registration.
Overrides preserve candidate validation and do not affect application rules or their
individual pattern scores. `analysis.minimum-score` and `analysis.provider-minimum-scores`
control which results are accepted after detection.

## Presidio Analyzer

Presidio sends the source text to the configured Presidio server for analysis.
Add its starter alongside the client starter.

Gradle:

```gradle
implementation "io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.4.0"
```

Maven:

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-presidio-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

Enable the analyzer and configure its server connection:

```yaml
spring:
  ai:
    privacy:
      analysis:
        language: en
      presidio:
        enabled: true
        analyzer-url: https://presidio.internal
        timeout: 5s
        max-retries: 1
        retry-backoff: 300ms
        max-response-bytes: 8388608
        max-response-depth: 64
        headers:
          X-API-Key: ${PRESIDIO_API_KEY}
```

Set `analyzer-url` to the Presidio server's HTTP(S) address. The default is
`http://localhost:5002`. Change it if your server runs at a different address.
When credentials are required, keep them out of the URL and use `headers`
together with the application's secret-management facilities.

Use Presidio Analyzer 2.2.361 or later to analyze structured JSON. Texts are
sent in batches to reduce network requests. Large inputs may require multiple
requests. Each text is analyzed independently.

`timeout` applies to each HTTP request through complete Presidio response-body
receipt. Transport failures, `timeout` expiration, HTTP 408/429 responses, and
5xx responses are retried according to `max-retries`. Other 4xx responses fail
immediately.

`max-response-depth` applies to Presidio responses. Use `processing.max-depth`
for application value trees and JSON documents. A Presidio response that exceeds
the size or depth limit causes an `ANALYZER_RESPONSE_INVALID` failure, handled
according to the configured provider failure policy.

When Spring Boot health support is available, the Presidio service can also be
included in application health checks.

The local Docker setup is in
[samples/presidio](https://github.com/ultramancode/spring-ai-privacy-guardrails/tree/v0.4.0/samples/presidio).

## OpenNLP Analyzer (JVM-Only, Optional)

Use compatible OpenNLP NER models to analyze PII in the application JVM.
Add its starter alongside the client starter and supply model files for the target language and entities.

Gradle:

```gradle
implementation "io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.4.0"
```

Maven:

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-opennlp-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

Enable the analyzer and configure the model resources:

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
          ORGANIZATION: classpath:/models/en-ner-organization.bin
```

When `tokenizer-model` is not configured, OpenNLP `SimpleTokenizer` is used.
Configure `entity-models` with the NER model file for each entity type. Detection
quality may change when the NER model and tokenization strategy are not
compatible, so validate the configuration against representative application
data.

The
[runnable sample guide](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/v0.4.0/samples/spring-ai-demo/README.md#optional-jvm-only-opennlp-adapter)
shows how to prepare models, configure the application, and run a live
integration test.

## Per-Tool Original Disclosure

Privacy-protected tools receive detected PII as opaque tokens by default.
These replacement strings do not directly reveal the original values. If a tool
needs original values, specify the entity types it may receive in
`ToolDisclosurePolicy`.

```java
ToolDisclosurePolicy toolDisclosurePolicy = ToolDisclosurePolicy.allowEntityTypesByToolName(
        Map.of("sendEmail", List.of("EMAIL_ADDRESS")));
GuardrailsPolicy.Privacy privacySettings = GuardrailsPolicy.Privacy.builder()
        .toolDisclosurePolicy(toolDisclosurePolicy)
        .build();
GuardrailsPolicy policy = GuardrailsPolicy.builder()
        .privacy(privacySettings)
        .build();
```

With the configuration above, only the `sendEmail` tool receives the
original value of `EMAIL_ADDRESS`. Entity types not listed for disclosure and
tools not registered under `ToolDisclosurePolicy` continue to receive protected
values.

The factory protects tools automatically, so register the original callbacks or
providers. MCP providers supply their current tool list for each request.

```java
ChatClient chatClient = guardrailsChatClientFactory.builder(chatModel, policy)
        .defaultTools(sendEmailToolCallback, knowledgeSearchToolCallback, mcpToolCallbackProvider)
        .build();
```

Tool names in `ToolDisclosurePolicy` are case-sensitive and must match the final
`ToolDefinition.name()`. If a dynamic provider adds a prefix, configure the
resulting final name. Wildcards are not supported, and every entity type that
requires original disclosure, including `PII`, must be listed explicitly. If a
tool requires no original values, do not register it in `ToolDisclosurePolicy`.

Original disclosure is checked against the final entity type after detection
results are merged. For example, if `PERSON` and `ORGANIZATION` detections are
merged into a single `PII` span, allowing both `PERSON` and `ORGANIZATION` for
the tool does not restore the original text. Explicitly allowing `PII` restores
the original text of the entire merged span.

Before a tool call, the library inspects the input and restores original values
only for entity types permitted for that tool. It inspects the tool result again
and protects PII before the result is passed to the model.

A tool that is authorized to receive originals may see real PII, so make sure
the tool implementation does not expose PII in exception messages or logs.

When the optional Spring Security integration is enabled, tool authorization
runs before this disclosure policy. Authorization decides whether the current
principal may discover or execute the tool. `ToolDisclosurePolicy` still decides
which original PII values that authorized tool may receive, based on their
entity types. See
[Spring Security Tool Authorization](security.md#guarantees).

This feature covers Spring AI's standard `ToolCallback` and
`ToolCallbackProvider` registration paths. Custom tool-execution paths are
outside the automatic privacy boundary and require separate protection. When
using a custom `ToolCallingManager` with the optional Security integration,
configure the boundary explicitly as described in
[Custom ToolCallingManager](security.md#custom-toolcallingmanager).

<a id="custom-tool-disclosure-policy"></a>

### Implement a Separate Disclosure Policy

Create `GuardrailsToolDisclosurePolicy.java` in your application to implement custom disclosure logic.
This policy allows original `EMAIL_ADDRESS` values only for `sendEmail`:

```java
public final class GuardrailsToolDisclosurePolicy implements ToolDisclosurePolicy {
    private static final AllowedEntityTypes EMAIL_ADDRESS_TYPES = AllowedEntityTypes.of(Set.of("EMAIL_ADDRESS"));

    @Override
    public AllowedEntityTypes getAllowedEntityTypes(ToolDefinition toolDefinition) {
        if (toolDefinition != null && "sendEmail".equals(toolDefinition.name())) {
            return EMAIL_ADDRESS_TYPES;
        }
        return AllowedEntityTypes.none();
    }
}
```

Register the implementation in an application `@Configuration` class:

```java
@Bean
ToolDisclosurePolicy toolDisclosurePolicy() {
    return new GuardrailsToolDisclosurePolicy();
}
```

Inject this bean into the client configuration and pass it to `.toolDisclosurePolicy(toolDisclosurePolicy)`.
You can inject application services into the policy's constructor when its logic grows.
Keep `allowEntityTypesByToolName(...)` for a fixed tool-to-entity mapping.

## Output Policy and Streaming

Final output protection is disabled by default. Enable it in the client policy:

```java
GuardrailsPolicy policy = GuardrailsPolicy.builder()
        .privacy(GuardrailsPolicy.Privacy.builder()
                .outputProtection().tokenize()
                .build())
        .build();
ChatClient chatClient = guardrailsChatClientFactory.builder(chatModel, policy)
        .build();
```

To customize the block message, use `block(message)`:

```java
GuardrailsPolicy.Privacy privacySettings = GuardrailsPolicy.Privacy.builder()
        .outputProtection().block("Response contains personal information.")
        .build();
```

Selecting an action replaces the output protection settings. `mutate()` preserves
existing settings. Use `outputProtection().disable()` to remove them. The policy
applies to PII returned from the model or tools to the application.

- `tokenize()` consistently replaces the same PII value with the same opaque token
  within one request.
- `redact()` replaces PII with a typed marker that cannot be restored to the
  original value.
- `block()` blocks output that contains PII and throws `PrivacyOutputBlockedException`
  with the default block message. Use `block(message)` to supply a custom message.

To reuse a `GuardrailsPolicy.OutputProtection` settings object, pass it directly
to the privacy builder's `outputProtection(settings)` method.

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

Tool-result protection replaces detected PII with opaque tokens. When a
`returnDirect` result is returned without final output protection, those tokens
are passed through unchanged. With final output protection, the selected
`TOKENIZE`, `REDACT` or `BLOCK` action is applied to the final result.

### Final Model Output Protection

The output protection settings control whether the output policy is applied to the final
model-generated response. Disabling it does not disable privacy protection for
inputs, model-call boundaries, or tool results.

| Output protection | PII analysis of final model output | Delivery behavior |
| --- | --- | --- |
| Not configured | No | The final model output is not inspected separately. Streaming calls may deliver model-generated text incrementally. Other privacy boundaries remain active, and PII in `returnDirect` results is still replaced with opaque tokens. |
| Configured | Yes | The complete response is inspected, then `TOKENIZE`, `REDACT`, or `BLOCK` is applied before delivery. |

Content inspection has its own `inspection.finalOutputInspectionEnabled(...)` policy setting.
See [final output inspection](inspection.md#optional-final-output-inspection) for rules and guard models.

The Spring AI streaming API remains usable when output protection is enabled,
but the library must first buffer the complete response for privacy inspection.
Model-generated text therefore cannot be delivered in real time as it is
generated. If real-time streaming is required, leave final output protection disabled
and handle privacy protection for final model output in the application.

### Reasoning Text Protection

When output protection is enabled, the same protection policy applies to both
the response body and the reasoning text listed below.

**Protected fields**

- Text returned by `DeepSeekAssistantMessage.getReasoningContent()`.
- String values in the top-level `reasoningContent` and `thinking` fields of
  message (`AssistantMessage`) and generation (`Generation`) metadata.

Metadata fields with other names and nested values are not inspected
automatically.

## Input and Response Limits

Set `privacy.responseLimits(...)` in the client policy with `PrivacyResponseInspectionLimits`.
The defaults allow 1,024 stream frames, 1,000,000 text characters, 16,777,216 media
bytes and 60 seconds between frames. These limits apply to intermediate tool-call
responses even when final output protection is disabled. With output protection,
text and media limits also apply to the final response. The media limit measures
data size. It does not detect PII in image or audio content.

`processing.*` sets limits on privacy analysis and transformation. These limits
apply even when final output protection is disabled. All values must be positive integers.

String lengths are measured in UTF-16 code units, as with Java `String.length()`.
JSON output length includes JSON syntax and escape sequences. Value-tree output
length is the combined length of string values and map keys in the result.

Texts extracted from JSON are analyzed in batches of up to
`processing.max-analysis-segments`.

Exceeding a processing limit stops the operation with `PAYLOAD_LIMIT_EXCEEDED`,
regardless of `ALLOW_PARTIAL` or fallback provider policies.

## Direct `core` Module Usage

This section applies only when calling `PrivacyService` methods in the `core`
module directly rather than using the normal Spring AI starter integration.
Applications that use the starters do not need to manage the sessions or value
structures described below.

When using `PrivacyService` directly, open a `PrivacySession` first.
`session.handle()` identifies the session to use for PII analysis, tokenization,
and restoration. This example replaces PII in the source text with opaque tokens
and then restores only email addresses (`EMAIL_ADDRESS`), whose disclosure the
application has allowed, within the same session.

```java
try (PrivacySession session = privacyService.openSession()) {
    PiiTokenizationResult result = privacyService.analyzeAndTokenize(
            session.handle(), sourceText);
    String protectedText = result.tokenizedText();

    String disclosedText = privacyService.detokenize(
            session.handle(), protectedText, Set.of("EMAIL_ADDRESS"));
}
```

Only the types passed as the third argument to `detokenize(...)` are restored.
Opaque tokens for other types remain protected. Choose these types according to the
application's disclosure policy. Once the try block ends and the session closes,
its opaque tokens can no longer be restored. Passing an unknown or closed session
`handle` raises an error.

`tokenizeValueTree()` and `detokenizeValueTree()` operate on JSON-compatible
`Map` and `List` structures. Supported values are `null`, booleans, strings,
and numeric values of type `Byte`, `Short`, `Integer`, `Long`, `BigInteger`,
`BigDecimal`, or finite `Float` and `Double` values. `Map` keys must be strings.

These methods do not modify the input objects. They return new transformed
`Map` and `List` containers. Unsupported values, non-string `Map` keys, or
reference cycles raise `TRANSFORMATION_CONFLICT`. Size-limit violations raise
`PAYLOAD_LIMIT_EXCEEDED`.

Plain Java objects and Jackson `JsonNode` values cannot be passed directly to
these two methods. Convert them to a supported `Map`/`List` structure first.

## Test Support

`spring-ai-privacy-guardrails-test` provides utilities for automated tests, such
as JUnit tests. Use it to check whether original PII reached the model, whether
a tool received permitted original values, and whether privacy sessions were
cleaned up after the request.

```gradle
dependencies {
    testImplementation "io.github.ultramancode:spring-ai-privacy-guardrails-test:0.4.0"
}
```

In tests, use the module's `PrivacyTestProbe` to record the values passed to the
model and tools. After executing a request, check those records with
`PrivacyTestAssertions.assertThatPrivacy(...)`.

The following JUnit test method assumes an analyzer configured to detect names
(`PERSON`) and email addresses (`EMAIL_ADDRESS`). Inject `PrivacyService` and
`GuardrailsChatClientFactory guardrailsChatClientFactory` into the test.
The example configures disclosure of `PERSON` to `customerLookup` and records
the values that reach the tool after privacy processing.

The example uses the following values and objects:

| Value or variable | What to prepare in the test |
| --- | --- |
| `testModel` | A `ChatModel` prepared to request `customerLookup` in its first response and return a final answer after tool execution. This can be a test implementation or a mock. |
| `customerLookup` | The original `ToolCallback` whose input you want to inspect. Create a test tool or use the application's tool callback you want to verify. |
| `"Alice"`, `"alice@example.com"` | Example name and email values used to check PII detection and delivery. Use matching values in the test input and assertions. |

For this test, use the `PERSON` opaque token from the model input in the tool-call
arguments produced by `testModel`. This lets the test verify that the opaque token is
restored to `"Alice"` before it reaches the tool.

```java
@Test
void protectsModelInputAndDisclosesAllowedToolInput() {
    GuardrailsPolicy policy = GuardrailsPolicy.builder()
            .privacy(GuardrailsPolicy.Privacy.builder()
                    .toolDisclosurePolicy(
                            ToolDisclosurePolicy.allowEntityTypesByToolName(
                                    Map.of("customerLookup", List.of("PERSON"))))
                    .build())
            .build();

    try (PrivacyTestProbe privacyProbe = PrivacyTestProbe.create(privacyService)) {
        ChatClient chatClient = guardrailsChatClientFactory.builder(privacyProbe.wrapModel(testModel), policy)
                .defaultTools(privacyProbe.wrapTool(customerLookup))
                .build();

        chatClient.prompt()
                .user("Find Alice (alice@example.com).")
                .call()
                .content();

        assertThatPrivacy(privacyProbe)
                .modelRequestsDoNotContainRawValues("Alice", "alice@example.com")
                .modelRequestsContainOpaqueToken("PERSON")
                .toolInputsContain("customerLookup", "Alice")
                .hasNoActivePrivacySessions();
    }
}
```

`wrapModel(...)` records the requests the model actually receives.
`wrapTool(...)` records the input the tool actually receives and the output it returns.
Privacy protection follows the policy passed to `GuardrailsChatClientFactory`.

A test `ChatModel` can verify privacy behavior without connecting to an external LLM.

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

`privacyMetrics.record(...)` represents the application's metrics code. Replace
it with the appropriate call to your monitoring system.

The library delivers an event to the registered observer when privacy
processing completes. `boundary()` identifies where the event occurred, and
`outcome()` describes the result at that boundary. A single request may therefore
produce multiple events. If a request does not pass through a boundary, no event
is delivered for that boundary. Output-policy blocking produces a `BLOCKED`
event. Events for other processing failures are not provided.

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
request. Observers must not assume those threads are the same.

Short operations such as logging and metrics updates can run directly. If
network or database I/O is needed, enqueue the work and return promptly.
Non-fatal observer failures are ignored and cannot change privacy enforcement.

The ChatClient Spring Boot starter connects a `PrivacyEnforcementObserver` bean
to the factory. Without Spring Boot, pass the observer through
`GuardrailsChatClientFactory.builder().privacyObserver(privacyObserver)`.
The factory connects it to model-request and tool protection, as well as final
output protection when enabled.

When assembling privacy protection directly, configure the observer on
`PrivacyChatClientConfigurer` and `PrivacyToolCallbackFactory`. If creating a
`PrivacyOutputAdvisor` directly, pass the observer to its constructor too.

## Stored Data and Diagnostics

This library protects PII in memory and retrieved documents when they are sent
to the model, but it does not search for and rewrite data that was already
stored. PII stored in `ChatMemory`, vector stores, databases, logs, and traces
must be protected separately by the application.

Response metadata outside the fields listed in
[Reasoning Text Protection](#reasoning-text-protection), as well as non-text
media, is not protected automatically.

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
| `TOKENIZATION` | PII tokenization |
| `TOKEN_SIMPLIFICATION` | Remove the random part from session-issued PII tokens |
| `REDACTION` | Replace PII with redaction markers |
| `DETOKENIZATION` | Restore authorized original values |
| `SESSION` | Privacy-session processing |
| `OUTPUT_POLICY` | Apply the final output policy |
| `TOOL_INPUT` | Inspect tool input and disclose authorized originals |
| `TOOL_EXECUTION` | Validate the tool-execution boundary |
| `TOOL_OUTPUT` | Protect tool execution results |

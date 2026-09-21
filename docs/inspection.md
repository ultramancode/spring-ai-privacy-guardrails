# Content inspection

**English** | [한국어](ko/inspection.md)

Content inspection checks text before each model call using rules, a local ONNX
model, or an OpenAI-compatible endpoint. It works independently or alongside
Privacy protection and tool authorization.

## Modules

All artifact names start with `spring-ai-privacy-guardrails-`.

| Suffix | Responsibility |
| --- | --- |
| `spring-ai-boundary` | Client-scoped model request boundary with fixed phase order |
| `inspection-core` | Pure Java requests, results, policies and ordered execution |
| `inspection-rules` | Literal and linear-time RE2 rules |
| `inspection-onnx` | Shared local ONNX runtime with Prompt Guard 2 and ProtectAI DeBERTa v2 adapters |
| `inspection-openai-compatible` | Dedicated HTTP transport and internal model protocols |
| `inspection-spring-ai` | Explicit, client-scoped inspection integration |
| `inspection-spring-boot-starter` | Opt-in common configuration and optional Privacy integration |

Select backend artifacts, register their `ContentInspector` beans, and apply the
configurer to the ChatClient instances you want to inspect. The starter provides
common configuration without selecting a backend or modifying other clients.

## Minimal use

The following types are in
`io.github.ultramancode.springai.privacy.inspection.core`,
`.rules`, and `.springai`.

```java
var rules = new RuleBasedContentInspector(List.of(
    InspectionRule.literal("override-example", "ignore previous instructions")));
var service = new InspectionService(List.of(rules));
var inspection = new InspectionChatClientConfigurer(service);
ChatClient client = inspection.configure(ChatClient.builder(model)).build();
```

The rule above illustrates API wiring. Supply rules for your application's
inspection policy.

For Boot, register a `ContentInspector` bean and enable:

```yaml
spring:
  ai:
    inspection:
      enabled: true
      failure-policy: BLOCK
      timeout: 10s
      max-segments: 64
      max-characters: 131072
      max-chunks: 256
```

Inject `InspectionChatClientConfigurer` and apply it to the selected builder.
Enabling the feature without a provider fails startup. A custom
`InspectionPolicy`, `InspectionService`, or configurer bean can replace the default.
An observer can be supplied to the configurer constructor; it receives only an
`InspectionReport`, not request/response bodies. Observer failures do not change decisions.

## Privacy and tool authorization

Execution order is final Privacy protection (when used), existing tool-definition
authorization (when used), inspection, then the business model. All supported tool
loop continuations are inspected again, for both call and stream.

Managed paths execute all three phases inside one `ModelRequestBoundaryAdvisor`.
Feature phases have no individual advisor order and do not depend on registration
order. The common advisor's `MAX_VALUE - 3` order determines its position relative
to other Spring AI advisors. Its internal phases use ordinary method calls in a
fixed sequence, so adding an internal phase does not require another advisor order.
To combine Privacy and inspection, use the configurer from
`io.github.ultramancode.springai.privacy.boundary`:

```java
ChatClient client = ModelRequestBoundaryConfigurer.compose(
        privacyConfigurer.forToolCallingAdvisorOrder(ToolCallingAdvisor.DEFAULT_ORDER),
        inspection)
    .configure(ChatClient.builder(model))
    .build();
```

The Boot starter recognizes actual final Privacy-boundary provenance. For manual
integration, construct the configurer with this trusted representation resolver:

```java
request -> PrivacyChatClientConfigurer.isModelContentProtected(request)
    ? ContentSegment.Representation.PRIVACY_PROTECTED
    : ContentSegment.Representation.AS_RECEIVED
```

For authorization factories, contribute inspection to the same boundary:

```java
var client = privacySecurityFactory
    .builderWithBoundary(model, inspection)
    .build();

// Custom tool loop, including ToolSearchToolCallingAdvisor.Builder:
var customClient = privacySecurityFactory
    .builder(model, toolAdvisorBuilder, inspection)
    .build();
```

`ToolAuthorizationChatClientFactory` offers the same composition without Privacy.
Factory construction is available with and without Boot. Compose
features together: applying managed configurers separately creates duplicate boundaries
and is rejected. Once configured, the processing stages cannot be changed.
Copying a builder with `Builder.clone()` or `client.mutate()` keeps each client's
configuration independent. Tool orders must still fit between their lifecycle and
the common model boundary.

With inspection enabled, downstream application advisors, including request-added
advisors, are rejected by default. Explicitly allow them for a selected client when needed:

```java
ChatClient client = privacySecurityFactory
    .builderWithBoundary(model, inspection.allowAdvisorsAfterBoundary(true))
    .build();
```

Allowing this logs the downstream advisor names and orders. Their later changes are
outside the final-inspection guarantee. This is separate from `FAIL_OPEN`: it never
overrides inspection blocks, authorization or disclosure denials, missing/duplicate
required stages, or an unsafe tool layout. Privacy-only clients do not acquire a new
final-inspection restriction. Validation covers configured ChatClient paths, not
direct model calls or custom chain implementations.

## Representations and disclosure

- `RAW`: original text supplied explicitly at a source boundary.
- `AS_RECEIVED`: text as currently available; no claim that it is original or protected.
- `PRIVACY_PROTECTED`: the configured privacy transformation ran on this content.

Local inspectors can process any supplied representation. The Spring integration
inspects the actual available model-bound text; it does not recover already
tokenized originals. Applications can call the core API at earlier source hooks.

Remote inspectors require protected content by default, including when called
directly rather than through `InspectionService`. Raw/as-received transmission
requires `allowRawContent=true`. The service preflights every required provider
before invoking any provider. Failure-open cannot override disclosure denial.
Custom inspectors also require protected content by default. Local implementations
can override `requiresProtectedContent()` to accept other representations.

Known roles are retained. Unknown source provenance stays UNKNOWN: a user-role
message can contain RAG text, and a system-role message is not inherently trusted.

## Backends

### Rules

`InspectionRule.literal(id, text)` matches a literal string.
`InspectionRule.regex(id, category, expression)` uses RE2/J, not Java's
backtracking regex engine. Configure 1–256 uniquely identified rules with bounded
expressions. Lookaround/backreferences are not supported. Results contain rule IDs,
not matching text. Rule sets are explicitly supplied by the application.

### ONNX model adapters

`OnnxContentInspector` uses a shared runtime; `OnnxInspectionModel` owns model-specific
tokenization, windows and output interpretation. Two supplied adapters are available:

| Adapter | Output meaning | Default window / threshold |
| --- | --- | --- |
| `PromptGuard2Model` | BENIGN=0, MALICIOUS=1; binary softmax | 512 / 0.5 |
| `ProtectAiDebertaV2Model` | SAFE=0, INJECTION=1; binary softmax | 512 / 0.5 |

```java
OnnxContentInspector local = new OnnxContentInspector(
        OnnxInspectionConfig.defaults(Path.of("model.onnx")),
        new PromptGuard2Model(Path.of("tokenizer.json")));
// ProtectAI export: new ProtectAiDebertaV2Model(Path.of("onnx/tokenizer.json"))
```
Supply the tokenizer.json matching the export. For ProtectAI, use the artifacts in
its `onnx/` directory together. Both adapters validate `input_ids`, `attention_mask`,
optional `token_type_ids` and FLOAT `logits[1][2]`. The runtime creates INT32/INT64
inputs according to graph metadata and assumes neither 512 tokens nor binary softmax.

Windows overlap by 64 tokens by default and cover the entire input. Any window meeting
the threshold produces evidence; exceeding the window budget is failure. Earlier
findings survive later-window failure, and only fully inspected segments are reported
as covered. Scores from different models are never averaged.

Implement `OnnxInspectionModel` for another token-based classifier. The current input
contract is batch-one, rank-two INT32/INT64 tensors, not arbitrary ONNX graphs or
generative models. Adapters interpret outputs, including multi-label sigmoid.
DistilBERT, long-input ModernBERT and Agent Guard multi-label profiles exist only in
tests and are not additional supported products.

The inspector owns the adapter and session. Do not share an adapter between instances;
close the inspector when finished. CPU inference and tokenization are serialized,
lock acquisition and inference observe the common deadline, and cancellation/timeout
requests native termination. Adapter encoding and decoding share that serialized scope.

No weights are bundled or automatically downloaded. Provision authorized artifacts and
review the [Prompt Guard terms](https://huggingface.co/meta-llama/Llama-Prompt-Guard-2-86M)
and [ProtectAI model card](https://huggingface.co/protectai/deberta-v3-base-prompt-injection-v2).
Offline deployments must also provision the DJL native tokenizer. Windows requires a
compatible [Visual C++ runtime](https://onnxruntime.ai/docs/install/).
See the [verification guide](../scripts/inspection/README.md) for real-model Python/Java
comparisons. These check execution contracts, not detection accuracy by language.

### OpenAI-compatible HTTP

```java
var remote = OpenAiCompatibleContentInspector.kanana(
    OpenAiCompatibleInspectionConfig.kanana(
        URI.create("http://127.0.0.1:8000/v1/chat/completions")));
```

The URI is the **full endpoint**, not just a base URL. This adapter uses a dedicated
JDK HTTP client, never the protected Spring AI ChatClient. It has finite connection,
request and shared inspection deadlines, bounded response collection, cancellation,
no automatic redirects, and no retries.

Protocol selection is explicit:

- `kanana(config)`: one user message, one generated classification token,
  model chat-template options, exact `<SAFE>`, `<UNSAFE-A1>`, `<UNSAFE-A2>`.
  These become no finding, PROMPT_INJECTION, PROMPT_LEAKING respectively.
  The protocol accepts a length finish only for this one-token contract.
- `jsonGuard(config)`: an explicit prompted-classifier contract for instruction
  models. It sends a fixed system instruction and requires exactly
  `{"verdict":"SAFE"}` or `{"verdict":"UNSAFE"}`. Extra fields, duplicate keys,
  trailing objects, prose, tool calls, refusals and truncated JSON are rejected.

Each protocol defines the model's input format and accepted output. Select it
explicitly to match the model. The Kanana protocol submits individual segments
as user utterances.
For model-specific input conventions, see its
[model card](https://huggingface.co/kakaocorp/kanana-safeguard-prompt-2.1b).
vLLM is one compatible runtime; the adapter does not depend on it. For server
deployment and security settings, follow your runtime's documentation.

## Decisions, coverage and scope

A result separately records completion status, covered segment IDs, findings and
a payload-free failure code. Missing/unknown coverage, malformed model results,
limits, HTTP failures and timeouts are not successful checks.

All configured inspectors are required and run in order. Confirmed BLOCK
short-circuits. A positive finding is preserved even if later chunks fail.
The default policy blocks findings; operational failures block by default.
Explicit FAIL_OPEN retains a FAILED outcome and `allowedAfterFailure=true`.
Cancellation, disclosure denial, unsupported content and configuration errors are
not bypassed by FAIL_OPEN. Existing Privacy/authorization failures remain independent.

The managed scope is standard system/user/assistant text and tool-response text,
before each business-model call. Custom message subtypes and media fail explicitly.
Spring AI's structured-output/format-instruction context also fails explicitly:
its terminal model advisor can append text after the inspection boundary. Use an
explicit text prompt instead; `call().entity(...)` is not supported in this first integration.
Each text segment is inspected independently. Output moderation, tool arguments,
schemas, metadata, `returnDirect` results, rewriting, re-asking, and multimodal
content are outside this integration's scope.

Default records/exceptions redact input text, endpoint credentials and raw model
responses. Do not configure diagnostic IDs with customer data. Application-owned
logging, Spring AI payload observations and model-server logging remain separate
disclosure responsibilities.

## Verification

Regular tests cover the common policy, Spring AI integration, bounded HTTP handling,
and native ONNX execution. Optional live tests cover Prompt Guard ONNX inference
and OpenAI-compatible model calls.

See the [inspection test guide](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/scripts/inspection/README.md)
for test commands, model artifacts, recorded results, and runtime setup.

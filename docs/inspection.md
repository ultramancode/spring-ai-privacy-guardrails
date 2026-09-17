# Content inspection

**English** | [한국어](ko/inspection.md)

Content inspection checks text before each model call using rules, a local ONNX
model, or an OpenAI-compatible endpoint. It works independently or alongside
Privacy protection and tool authorization.

## Modules

All artifact names start with `spring-ai-privacy-guardrails-`.

| Suffix | Responsibility |
| --- | --- |
| `inspection-core` | Pure Java requests, results, policies and ordered execution |
| `inspection-rules` | Literal and linear-time RE2 rules |
| `inspection-onnx` | Local ONNX inspection with a Prompt Guard 2 adapter |
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

The defaults reserve distinct orders: Privacy `MAX_VALUE - 3`, authorization
`MAX_VALUE - 2`, inspection `MAX_VALUE - 1`, model `MAX_VALUE`. These orders
preserve the boundaries across tool-loop continuations. To combine with Privacy:

```java
var builder = inspection.configure(ChatClient.builder(model));
privacyConfigurer.forToolCallingAdvisorOrder(ToolCallingAdvisor.DEFAULT_ORDER)
    .apply(builder);
var client = builder.build();
```

The Boot starter recognizes actual final Privacy-boundary provenance. For manual
integration, construct the configurer with this trusted representation resolver:

```java
request -> PrivacyModelBoundaryAdvisor.isModelContentProtected(request)
    ? ContentSegment.Representation.PRIVACY_PROTECTED
    : ContentSegment.Representation.AS_RECEIVED
```

For existing authorization factories, use the terminal-boundary hook:

```java
var client = privacySecurityFactory
    .builderWithTerminalBoundary(model, inspection)
    .build();

// Custom tool loop, including ToolSearchToolCallingAdvisor.Builder:
var customClient = privacySecurityFactory
    .builder(model, toolAdvisorBuilder, inspection)
    .build();
```

`ToolAuthorizationChatClientFactory` offers the same hook without requiring Privacy.
Existing factory overloads remain available. The Privacy and authorization default
orders move from their 0.3.0 values; review custom advisors using hard-coded terminal
orders when upgrading. Custom tool orders must still fit the reserved boundaries.
The configurer validates the chain, including request-added advisors, so only
Spring AI's model-call advisor runs after inspection. Place custom and observability
advisors earlier to wrap the complete call. These checks apply to the configured
ChatClient path, not direct model calls or custom chain implementations.

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

### ONNX: Prompt Guard 2

The ONNX module provides `PromptGuardContentInspector` for Prompt Guard 2
binary-classifier exports. The requirements below describe this adapter's model
contract, not a general ONNX format.

```java
var local = new PromptGuardContentInspector(
    PromptGuardConfig.defaults(Path.of("model.onnx"), Path.of("tokenizer.json")));
```

This adapter expects INT64
`input_ids`, `attention_mask`, optional `token_type_ids`; FLOAT `logits[1][2]`
with BENIGN=0 and MALICIOUS=1. The matching tokenizer is mandatory. It uses
overlapping 512-token windows (64-token default overlap), a per-provider threshold
(default 0.5), and no silent truncation. Any qualifying window is evidence; scores
from different providers are never averaged. Exceeding the window budget is failure.

Model and tokenizer paths are configurable. For a model with different tokenization,
tensor inputs, or output-label semantics, supply a corresponding `ContentInspector`
implementation rather than replacing only the model file.

Close the adapter when the application stops. CPU inference and tokenizer access
are serialized per instance to bound native work; lock acquisition observes the
shared deadline. A watchdog requests native inference termination on timeout or
interruption. Custom synchronous providers must honor the request limits and
cooperate with cancellation.

Supply authorized model/tokenizer artifacts yourself. No model weights are bundled
or downloaded by the adapter. DJL's native tokenizer library must also be provisioned
or cached for offline deployment. Follow the
[Prompt Guard model terms](https://huggingface.co/meta-llama/Llama-Prompt-Guard-2-86M).

On Windows, use a current JDK and compatible
[Visual C++ runtime](https://onnxruntime.ai/docs/install/).

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

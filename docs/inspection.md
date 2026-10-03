---
description: >-
  Inspect model input and final output with rules, a local ONNX classifier or an HTTP guard model.
---

# Content inspection

**English** | [한국어](ko/inspection.md)

Content inspection checks model input against your rules or a guard model and blocks
requests that violate the configured policy. It runs before each model call, including
tool-loop continuations. You can also enable inspection of final responses.

## Explicit client configuration

For a rules-based setup, add these modules to an application that provides a Spring AI
`ChatModel`:

- `spring-ai-privacy-guardrails-inspection-spring-boot-starter`
- `spring-ai-privacy-guardrails-inspection-rules`

Enable inspection in `application.yml`:

```yaml
spring:
  ai:
    inspection:
      enabled: true
```

Add a rule and configure the client in your Spring configuration:

```java
@Bean
ContentInspector applicationRules() {
    return new RuleBasedContentInspector("application-rules", List.of(
        InspectionRule.literal("blocked-phrase", InspectionFinding.Category.PROMPT_INJECTION,
            "ignore previous instructions")));
}

@Bean
ChatClient inspectedChatClient(ChatModel chatModel, InspectionChatClientConfigurer inspectionConfigurer) {
    return inspectionConfigurer.configure(ChatClient.builder(chatModel)).build();
}
```

The example blocks input containing `ignore previous instructions` by throwing
`InspectionBlockedException` before the model runs. Only clients configured this way
are inspected. Enabling inspection requires at least one `ContentInspector` bean.

Literal rules are case-sensitive. Use `InspectionRule.regex(...)` for RE2/J patterns.
Choose rules that fit your application. This example alone is not a general
prompt-injection detector.

### Combine with privacy or tool authorization

Compose the starter-provided configurers and apply the composition once:

```java
ChatClient client = ModelRequestBoundaryConfigurer.compose(
        privacyConfigurer, inspectionConfigurer)
    .configure(ChatClient.builder(chatModel))
    .build();
```

The execution order is privacy processing, tool-definition authorization, then
content inspection. For privacy with authorization, use
`privacySecurityFactory.builderWithBoundary(chatModel, inspectionConfigurer).build()`.
For authorization alone, use `ToolAuthorizationChatClientFactory.builderWithBoundary`.
See [tool authorization](security.md) for setup.

## Optional model-output inspection

Output inspection is disabled by default. Enable it on the configured client with:

```yaml
spring:
  ai:
    inspection:
      enabled: true
      output:
        enabled: true
```

It checks the final assistant text delivered to the application, including
`returnDirect` tool results. `ALLOW` returns the response and `BLOCK` raises
`InspectionBlockedException`. It does not rewrite text or prevent tools from
executing before the final response is inspected.

When privacy output protection is also enabled, content inspection runs after it.
An HTTP inspector requiring privacy-processed content accepts output only when
privacy processing completed for that text. Input protection alone is not enough.

Streaming responses are buffered until inspection finishes. Only the final model
round or tool result is delivered, so enabling output inspection adds latency and
omits intermediate tool-loop text. Failed or cancelled stream assembly releases no
buffered content, even with `FAIL_OPEN`. Assembly limits are listed
[below](#scope-and-limits).

Boot uses the same inspectors and policy for input and output. For different output
rules, attach an advisor backed by a separate service:

```java
InspectionChatClientConfigurer configurer = inspectionConfigurer
    .withOutputInspection(new InspectionOutputAdvisor(outputService));
```

Use the returned configurer when building the client. You can also register
`InspectionOutputAdvisor` directly for output-only inspection.

## HTTP guard models and privacy

Add `spring-ai-privacy-guardrails-inspection-openai-compatible` and register an
inspector for your deployed guard model:

```java
@Bean
ContentInspector httpGuard() {
    OpenAiCompatibleInspectionConfig config = new OpenAiCompatibleInspectionConfig(
        URI.create("http://127.0.0.1:8000/v1/chat/completions"),
        "kakaocorp/kanana-safeguard-prompt-2.1b",
        null,
        Duration.ofSeconds(10),
        16_384,
        true);
    return new OpenAiCompatibleContentInspector("primary-guard", config, new KananaPromptProtocol());
}
```

Set `endpoint` to the full chat-completions URL. The third constructor argument is
the API key, or `null` to omit authentication. The next two arguments set the per-request
timeout and maximum response size in bytes.

Choose a protocol matching the deployed model:

| Protocol | Expected response |
| --- | --- |
| `KananaPromptProtocol` | Kanana Safeguard-Prompt labels: `<SAFE>`, `<UNSAFE-A1>` or `<UNSAFE-A2>` |
| `JsonVerdictProtocol` | Exactly `{"verdict":"SAFE"}` or `{"verdict":"UNSAFE"}` |

See the [Kanana model card](https://huggingface.co/kakaocorp/kanana-safeguard-prompt-2.1b/blob/main/README.md)
for its serving requirements. An OpenAI-compatible endpoint must also support the
selected protocol. Implement `GuardModelProtocol` for another response format.

The final configuration argument, `requirePrivacyProcessedContent`, controls whether
text may be sent to the guard model. With `true`, every segment must have completed
privacy processing. With `false`, content can be sent without privacy protection.
This setting checks processing status and does not perform PII detection itself.

| Privacy status | Meaning |
| --- | --- |
| `PROCESSED` | Configured privacy processing completed for this text |
| `UNPROCESSED` | The caller knows privacy processing was not applied |
| `UNKNOWN` | Completion has not been established |

The starter resolves input status automatically when privacy integration is available.
For direct Java configuration, the default resolver returns `UNKNOWN`. Supply a
`PrivacyProcessingStatusResolver` to the four-argument configurer constructor and
use `PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(request)` to map
`true` to `PROCESSED` and `false` to `UNKNOWN`.

The HTTP inspector sends one request per text segment using a separate HTTP client.
Each request is bounded by both the configured timeout and the remaining inspection
deadline. `PROCESSED` reflects the configured privacy policy, not a guarantee that
every piece of personal data was detected.

## Local ONNX classifiers

Add `spring-ai-privacy-guardrails-inspection-onnx` to run a classifier locally on the
CPU through DJL ONNX Engine. DJL loads and runs the model with ONNX Runtime as
its backend. Supply matching model and tokenizer files and register the inspector:

```java
@Bean(destroyMethod = "close")
OnnxContentInspector localGuard() {
    Path directory = Path.of("/opt/models/prompt-guard-2");
    return new OnnxContentInspector("local-guard",
        OnnxInspectionConfig.defaults(directory.resolve("model.onnx")),
        OnnxClassificationConfig.promptGuard2(
            directory.resolve("tokenizer.json"),
            directory.resolve("tokenizer_config.json"),
            0.9));
}
```

The `promptGuard2` helper maps the model's malicious class to `PROMPT_ATTACK`.
The threshold is illustrative. Calibrate it against representative benign and attack
inputs, including the languages your application supports. Consult the
[Prompt Guard 2 model card](https://huggingface.co/meta-llama/Llama-Prompt-Guard-2-86M)
when choosing a model.

Provision `model.onnx`, `tokenizer.json`, `tokenizer_config.json` and any external
graph data before startup. The inspector does not download model files. Offline
deployments may also need to provision DJL's native tokenizer library. Close the
inspector when it is no longer needed, as the bean declaration above does.

### Model requirements

| Component | Supported form |
| --- | --- |
| Inputs | `input_ids`, optional `attention_mask` and `token_type_ids`, shaped `[1, maxTokens]` |
| Token type | INT64 by default, or explicitly configured INT32 for all token inputs |
| Output | FLOAT `logits`, rank two, batch 1 and exactly `classCount` logits |

For another sequence classifier, use `OnnxClassificationConfig` to specify token-window
length, overlap, class count, activation and labels. Each
`Label(index, category, code, threshold)` maps an output to a finding.
Labels may share an output index with distinct codes. Thresholds apply independently,
so a score meeting multiple thresholds produces a finding for each matching label.
Use `SOFTMAX` for mutually exclusive classes and `SIGMOID` for independent labels.
For an INT32 export, pass `OnnxClassificationConfig.TokenInputType.INT32` as the final
constructor argument. Input names come from DJL. Input types and graph dimensions
are not independently validated at startup. The backend reports incompatible inputs
during inference. Missing or incompatible logits and nonfinite scores produce
`MODEL_ERROR`. Model-loading failures produce `CONFIGURATION`.

Use a complete Hugging Face fast-tokenizer export. Tokenization follows the saved
`tokenizer.json` graph. Explicit padding and truncation direction settings in
`tokenizer_config.json` take precedence. The padding token ID is resolved from the
tokenizer vocabulary, including added tokens. The configured window length includes
special tokens and padding, and overlap must be smaller than the remaining content
capacity.

Long text is inspected in overlapping windows. The default limit is 256 windows
across the entire request. Each mapped label retains its highest qualifying score
per segment. A segment is complete only after all its windows finish.

Concurrent calls to the same inspector run serially. Waiting, tokenization and inference
all consume the request deadline. Deadline and interruption checks run before and
after synchronous tokenization and inference. An in-progress native call is allowed
to return, so the deadline is not a hard return-time bound. Late results do not mark
a segment complete. Choose input limits appropriate to your resources.
Local ONNX and rule inspectors do not require privacy-processed content.

## Results, policies and failures

`InspectionService` runs inspectors in Spring bean order (`@Order`), or list order
when constructed directly. The default policy blocks any finding and stops subsequent
inspectors. Provide an `InspectionPolicy` bean to customize this decision.
Detection thresholds belong to each inspector. Scores from different models are
not directly comparable.

Reports identify the inspector and text segment, with findings carrying a category
and code rather than inspected text. Categories are `PROMPT_ATTACK`, `PROMPT_INJECTION`,
`PROMPT_LEAKING` and `POLICY_VIOLATION`. Keep custom IDs and codes free of user content.

The default failure policy is `FAIL_CLOSED`. Set
`spring.ai.inspection.failure-policy=FAIL_OPEN` only to allow eligible operational
failures. It never overrides a content-policy block.

| Failure | Behavior |
| --- | --- |
| `TIMEOUT`, `TRANSPORT_ERROR`, `HTTP_ERROR`, `MODEL_ERROR`, `INVALID_RESPONSE`, `INCOMPLETE` | `FAIL_CLOSED` blocks. `FAIL_OPEN` can allow if the content policy allows any retained findings |
| `CANCELLED`, `LIMIT_EXCEEDED`, `DISCLOSURE_DENIED`, `CONFIGURATION`, `UNSUPPORTED_CONTENT`, `INVALID_RESULT` | Throws `InspectionException` regardless of the failure policy |

`InspectionReport` contains outcomes in execution order.
`allowedAfterFailure()` identifies a request allowed after an operational failure.
Service exceptions carry the report collected so far when available.

For a custom inspector, implement `ContentInspector` with a unique, stable ID.
Report `COMPLETED` only when every segment has been inspected. On failure, retain
completed segment IDs and any partial findings. Implementations must respect the
request's limits, deadline and interruption. See the interface JavaDoc for the full contract.

## Observability

Register an `InspectionObserver` bean to receive reports and failures:

```java
@Bean
InspectionObserver inspectionObserver() {
    return new InspectionObserver() {
        @Override
        public void onInspection(InspectionReport report) {
            auditDecision(report);
        }

        @Override
        public void onFailure(InspectionException failure) {
            auditFailure(failure.failure(), failure.report());
        }
    };
}
```

The audit methods are application hooks. `onInspection` receives allow and block
decisions, while `onFailure` receives hard inspection failures. Callbacks may run
concurrently and should return promptly. Observer runtime exceptions do not change
enforcement. Reports contain diagnostic identifiers and findings, not prompt or
response text.

## Scope and limits

Input inspection covers message bodies, tool results, tool-call arguments and
supported reasoning text, including DeepSeek `reasoningContent` and string-valued
assistant metadata named `reasoningContent` or `thinking`. Media and unsupported
message subclasses are rejected.

Each input body is passed to inspectors as one complete segment. JSON keys, values,
structure and escape sequences are preserved. Rules match this text without JSON
unescaping. When combined with privacy protection, inspectors receive the
privacy-transformed body, whose JSON keys remain unchanged.

Output inspection covers final assistant bodies. Provider-specific reasoning fields,
tool-call arguments and metadata are excluded. Text-bearing thought frames marked
`isThought=true` or `thinking=true` are inspected separately from answer frames.
For JSON output, string and numeric values are inspected separately. Keys, booleans
and null are excluded. Invalid JSON is inspected as plain text. Inspection does not
modify the input or output body.

Tool definitions, MCP schemas, structured-output configuration and other metadata are
outside inspection scope. Input inspection does not check newly generated tool arguments
before execution. Use [tool authorization](security.md) for execution permissions.

The following properties use the `spring.ai.inspection` prefix:

| Setting | Default | Scope |
| --- | --- | --- |
| `max-segments` | 64 | Extracted text segments, including empty bodies |
| `max-characters` | 131072 | Combined source text length in UTF-16 units, including JSON syntax |
| `max-findings` | 10000 | Findings across all inspectors |
| `timeout` | 10s | Shared inspector and policy deadline for one inspection |
| `output.max-frames` | 4096 | Frames across a streamed tool loop, including empty frames |
| `output.stream-timeout` | 60s | Total stream assembly time, including model calls and tool execution |

Each model call receives new inspection limits. Output inspection has its own budget.
These settings are independent of `spring.ai.privacy.processing` and
`spring.ai.privacy.response-inspection`. Direct API callers supply `InspectionLimits`.
A deadline cannot forcibly stop arbitrary custom code.

Inspection supplements privacy protection and tool authorization. Validate detection
quality with your application's data. A completed inspection does not guarantee safe
content or detection of every attack.

## Modules and responsibilities

| Module suffix (`spring-ai-privacy-guardrails-…`) | Purpose |
| --- | --- |
| `inspection-core` | Spring-independent inspection contracts, policies and execution |
| `inspection-rules` | Literal and RE2/J rule matching |
| `inspection-onnx` | Local ONNX sequence classifiers |
| `inspection-openai-compatible` | HTTP guard models with explicit protocols |
| `inspection-spring-ai` | Client-scoped input and output inspection |
| `inspection-spring-boot-starter` | Spring Boot configuration and bean wiring |

## Verification

`./gradlew check` runs rule tests, local HTTP and ONNX fixtures, and Spring AI
integration tests. These verify execution and enforcement, not model detection quality.
See [Evaluation](evaluation.md) for repository verification guidance.

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
Choose matching phrases and patterns that fit your application.

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

Output inspection runs after model calls and tool execution, checking the final
assistant text before delivery to the application, including `returnDirect` tool
results. `ALLOW` returns the response and `BLOCK` raises `InspectionBlockedException`.

When privacy output protection is also enabled, content inspection runs after it.
Enable privacy output protection when using an HTTP inspector that requires
privacy-processed output.

Streaming responses are buffered and delivered after inspection. The application
receives the final model round or tool result. Intermediate tool-loop text is omitted.
If stream assembly fails or is cancelled, buffered content is discarded, including
with `FAIL_OPEN`. Assembly limits are listed [below](#scope-and-limits).

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
This setting checks completion of the processing applied by privacy protection.

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

The HTTP inspector sends one request per text segment.
Each request is bounded by both the configured timeout and the remaining inspection
deadline.

## Local ONNX classifiers

Add `spring-ai-privacy-guardrails-inspection-onnx` to run a classifier locally on the
CPU through ONNX Runtime. DJL provides tokenization. Supply matching model and
tokenizer files and register the inspector:

This example configures a model with outputs ordered as benign (0), injection (1)
and leaking (2). Set `Label.index` to match your model's actual output order.

```java
@Bean(destroyMethod = "close")
OnnxContentInspector localGuard() {
    Path directory = Path.of("/opt/models/local-classifier");
    OnnxClassificationConfig onnxClassificationConfig = OnnxClassificationConfig.builder()
        .tokenizer(directory.resolve("tokenizer.json"))
        .tokenizerConfig(directory.resolve("tokenizer_config.json"))
        .maxTokens(256)
        .overlapTokens(32)
        .logitCount(3)
        .activation(OnnxClassificationConfig.Activation.SOFTMAX)
        .labels(List.of(
            new OnnxClassificationConfig.Label(1,
                InspectionFinding.Category.PROMPT_INJECTION, "INJECTION", 0.9),
            new OnnxClassificationConfig.Label(2,
                InspectionFinding.Category.PROMPT_LEAKING, "LEAKING", 0.9)))
        .tokenInputType(OnnxClassificationConfig.TokenInputType.INT64)
        .build();
    return new OnnxContentInspector("local-guard",
        OnnxInspectionConfig.defaults(directory.resolve("model.onnx")),
        onnxClassificationConfig);
}
```

The 32-token overlap and `0.9` threshold are example application settings.
Calibrate thresholds with representative benign and attack inputs in your application's
languages. Recalibrate after changing window size, overlap or model quantization.

The builder requires both tokenizer files, `maxTokens`, `logitCount`, `activation`
and `labels`. It defaults the overlap to zero, token input type to INT64 and
model output name to `logits`.

Provision `model.onnx`, `tokenizer.json`, `tokenizer_config.json` and any external
graph data before startup. Offline deployments may also need to provision DJL's
native tokenizer library. Close the inspector when it is no longer needed, as the
bean declaration above does.

### Model requirements

| Component | Supported form |
| --- | --- |
| Inputs | `input_ids`, optional `attention_mask` and `token_type_ids`, shaped `[1, maxTokens]` |
| Token type | INT64 by default, or explicitly configured INT32 for all token inputs |
| Output | FLOAT raw logits shaped `[1, logitCount]`. The output name defaults to `logits` |

If the export uses another output name, set `.modelOutputName("classification_output")`
on the builder.

Initialization checks input names, types and fixed dimensions, along with the selected
output. Model-loading failures and incompatible metadata raise `InspectionException`
with `CONFIGURATION` before inspection. Dynamic output shapes are checked during
inference. Unexpected output shapes and nonfinite logits produce `MODEL_ERROR`.

Models with different input names, preprocessing or output structure require a matching
export or a separate `ContentInspector` implementation.

### Classification settings

Use the model documentation and exported graph to set output names, indices, types
and scoring conventions. Choose finding mappings, thresholds and overlap for your
application. Supply these values through `OnnxClassificationConfig`.

| Setting | How to set it |
| --- | --- |
| `tokenizer`, `tokenizerConfig` | Supply local tokenizer files matching the model. |
| `modelOutputName` | Use the exported name of the raw logits output. Defaults to `logits`. |
| `logitCount` | Use N from the output shape `[1, N]`, including outputs without a finding mapping. |
| `activation` | Match the model's documented conversion from logits to scores. |
| `Label.index` | Use the position of the selected output, starting from zero. |
| `tokenInputType` | Match the integer type of every token input in the export. |
| `maxTokens` | Choose a supported window length, including special tokens and padding. Fixed-length graphs require their exact length. |
| `overlapTokens` | Choose the number of content tokens repeated between windows. It must be smaller than the window's content capacity. |
| `Label.category`, `Label.code` | Assign a finding category and diagnostic code to each detected condition. |
| `Label.threshold` | Set the minimum score after activation that emits a finding, including equality. |

`labels` maps selected outputs to findings. In the example, the benign output is
unmapped. Labels may share an output index with distinct codes and independent
thresholds. Each qualifying mapping produces a finding.

### Choosing `activation` and thresholds

`OnnxClassificationConfig.activation` specifies how the inspector converts raw model
outputs (logits) to scores between zero and one. The inspector performs the conversion
and compares the scores with `Label.threshold`. Select the value documented for your model:

| `activation` | When to use it |
| --- | --- |
| `SOFTMAX` | A model that chooses one of mutually exclusive categories, such as benign, injection or leaking. Requires at least two logits. |
| `SIGMOID` | A model that evaluates labels independently, or a binary classifier that returns one logit. |

A single-logit binary classifier uses `logitCount=1`. Model outputs must be raw logits
before activation. An export that already returns probabilities requires a raw-logit
export or a separate inspector.

For a numeric example, suppose one window returns `logits = [[3.0, 7.0, -3.0]]`
in the benign, injection, leaking order used above. `[1, 3]` means one window with
three raw scores.

| Output index | Meaning | Raw logit | Score after SOFTMAX |
| --- | --- | --- | --- |
| 0 | Benign | 3.0 | 0.017985 |
| 1 | Injection | 7.0 | 0.981970 |
| 2 | Leaking | -3.0 | 0.000045 |

With the example's `0.9` thresholds, only output 1 qualifies. The inspector returns:

```java
new InspectionFinding("s0", InspectionFinding.Category.PROMPT_INJECTION,
    "INJECTION", 0.9819700105182744);
```

The default policy blocks this finding. With an injection threshold of `0.99`,
neither mapped output qualifies, so the inspector returns no findings and the default
policy allows the completed inspection. Thresholds compare scores after activation,
using `score >= threshold`. The inspector returns the qualifying findings with
their scores.

### Tokenizer files

Supply `tokenizer.json` in the Tokenizers JSON format supported by DJL's Hugging Face
Tokenizers implementation. The graph must preserve the model's normalization,
pre-tokenization, vocabulary, added tokens and special-token processing.
See [DJL's tokenizer loading](https://github.com/deepjavalibrary/djl/blob/v0.38.0/extensions/tokenizers/README.md#from-huggingface-pipeline).

Padding and truncation directions in `tokenizer_config.json` take precedence over
the graph's settings. The padding token must exist in the vocabulary or added tokens.
Other tokenizer formats require a matching JSON export or a separate `ContentInspector`.

### Execution limits

Set CPU threads (`intraOpThreads`) and the request's window limit (`maxWindows`) in
`OnnxInspectionConfig`. Defaults are two threads and 256 windows. Set the shared
inspection deadline in `InspectionLimits`, which defaults to 10 seconds.
Choose these resource limits for your model and expected input lengths.

Long text is inspected in overlapping windows. The window limit applies across the
entire request. Each mapped label retains its highest qualifying score
per segment. A segment is complete only after all its windows finish.

Concurrent calls to the same inspector run serially. Waiting, tokenization and inference
all consume the request deadline. Deadline and interruption checks run before and
after synchronous tokenization and inference. A native call that exceeds the deadline
is handled as `TIMEOUT` after it returns, leaving the segment incomplete.
Local ONNX and rule inspectors accept text regardless of its privacy-processing status.

## Results, policies and failures

The starter orders inspector beans by `@Order`. When constructing `InspectionService`
directly, use the list order to control execution. The default policy blocks any
finding regardless of its score and stops subsequent inspectors.
Provide an `InspectionPolicy` bean to customize this decision.
Detection thresholds belong to each inspector. Scores from different models are
not directly comparable.

`InspectionFinding` contains a segment ID, `category`, `code` and optional `score`.
It does not contain the inspected text. Scores range from zero to one when present.
ONNX findings use scores after activation. Rule, Kanana and JSON-verdict findings
use `null`.

| Category | Meaning |
| --- | --- |
| `PROMPT_ATTACK` | A prompt attack whose specific type was not determined |
| `PROMPT_INJECTION` | An attempt to override intended instructions |
| `PROMPT_LEAKING` | An attempt to extract hidden prompts or instructions |
| `POLICY_VIOLATION` | An application content violation outside the prompt-attack categories |

Inspector IDs, segment IDs and finding codes must contain 1–128 ASCII letters,
digits, underscores, dots or hyphens and start with a letter or digit.
Use stable diagnostic identifiers without inspected text.

The default failure policy is `FAIL_CLOSED`. Set
`spring.ai.inspection.failure-policy=FAIL_OPEN` only to allow eligible operational
failures. Content-policy blocks apply with either failure policy.

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
enforcement.

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

## Modules and responsibilities

| Module suffix (`spring-ai-privacy-guardrails-…`) | Purpose |
| --- | --- |
| `inspection-core` | Spring-independent inspection contracts, policies and execution |
| `inspection-rules` | Literal and RE2/J rule matching |
| `inspection-onnx` | Local ONNX sequence classifiers |
| `inspection-openai-compatible` | HTTP guard models with explicit protocols |
| `inspection-spring-ai` | Client-scoped input and output inspection |
| `inspection-spring-boot-starter` | Spring Boot configuration and bean wiring |

## Evaluation

Evaluate detection quality with benign and attack inputs representative of your
application's languages and use cases.

See [Evaluation](evaluation.md) for project tests and evaluation tools.

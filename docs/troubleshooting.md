---
description: >-
  Diagnose PII detection, rule selection, tool disclosure, authorization, streaming and inspection setup.
---

# Troubleshooting

**English** | [한국어](ko/troubleshooting.md)

Start with the observed symptom. Reproduce it with synthetic input and the same client policy.
Use the [Inspector](sample.md) to compare with a known local configuration. Keep raw values and
opaque-token mappings out of production logs.

<a id="raw-input"></a>

## Originals Remain in Model Input

1. Check whether the selected analyzer detects the value. For local rules, check `rules.enabled`,
   `presets`, `include`, `exclude` and the [supported formats](privacy.md#built-in-rules). Also check
   entity allowlists and score thresholds. If detection is missing, correct the rule selection or
   choose a suitable analyzer.
2. Confirm the call uses the client returned by `guardrailsChatClientFactory.builder(chatModel, policy)`
   with `privacy()` selected. Another `ChatClient` or a direct `ChatModel` call bypasses that policy.
3. Repeat the request with a [model-input probe](privacy.md#test-support). The detected original
   should be absent from the prompt received by the model. The generated answer alone is not proof.

Memory and RAG stores can still hold originals. Protection applies to the copy sent to the model.
See [stored data](privacy.md#stored-data-and-diagnostics) for application responsibilities.

<a id="rules-not-running"></a>

## Registered Rules Do Not Run

1. With Boot's automatic rule configuration, enable `spring.ai.privacy.rules.enabled` and select
   registered IDs through `presets` or `include`. Registering a `PiiRule` or `PiiRulePreset` bean does
   not activate it. `COMMON` is not implicit, and the analysis language does not select `KR`.
2. Check `exclude`, which is applied after presets and included IDs. If you declare a
   `RuleBasedPiiAnalyzer` bean yourself, its catalog and selection replace automatic selection.
   Configure that bean instead of expecting the properties to modify it.
3. Inspect the analyzer's `ruleIds()` and `presetNames()`, then retry a synthetic matching value.
   See [rule selection](privacy.md#preset-selection). `addRule` and `addRules` register rules, while
   the analyzer builder's `presets`, `include` and `exclude` select them.

<a id="rule-startup"></a>

## Startup Fails After Rule Configuration

1. Read the configuration error and check case-sensitive preset names and rule IDs.
   Unknown IDs, duplicate definitions and an empty active selection are errors. `exclude` also
   requires registered IDs. Do not define a custom rule or preset with a built-in ID or name.
2. If only `rules.enabled=true` is set, select at least one rule or preset. Check whether exclusions
   removed every selected rule. Correct the specific invalid selection instead of disabling protection.
3. Restart the app and verify `ruleIds()` plus a matching test input. Check property spelling with
   [configuration diagnostics](privacy.md#configuration-typo-diagnostics).

<a id="tool-tokens"></a>

## A Tool Receives a Token Instead of an Original

1. Confirm that original disclosure is intended. Tokens are the default for protected tools.
2. Match the policy's tool name to the final `ToolDefinition.name()`, including case and an MCP
   provider's prefix. Check the canonical entity type and ensure its detection rule is active.
   `CUSTOMER_ID` needs an application rule, as shown in [customer lookup](getting-started.md#local-and-mcp-tools).
3. Repeat the synthetic tool call and inspect the tool input and its protected result. Only the
   permitted types should be restored. See [disclosure rules](privacy.md#per-tool-original-disclosure).

<a id="tool-access"></a>

## A Tool Is Missing or Execution Is Denied

1. If the tool is absent from the model's list, check the authorization decision at the definition
   phase. Ordinary filtering does not throw a denial exception.
2. For an execution denial, inspect the exception's `code` and `phase`, the registered tool name and
   the authentication available when the request begins. Correct the relevant permission or
   authentication propagation. See [denial handling](security.md#handling-denials) and
   [asynchronous authentication](security.md#blocking-reactive-and-asynchronous-context).
3. Compare authorized and unauthorized requests. The model should see only permitted tools, and
   unauthorized tools should never run. The [Security demo](sample.md#security) makes both cases reproducible.

<a id="streaming"></a>

## Streaming Arrives Only at the End

1. Check privacy output protection and `finalOutputInspectionEnabled`. These features buffer the
   complete response so checks can find content split across chunks. This delay is expected.
2. If immediate delivery is required, review which output checks your application needs before
   changing the policy. Disabling them also removes those checks. Configure response limits and
   timeouts when retaining buffered output protection.
3. Retry with the chosen policy. Check both the delivery timing and the content that is released.
   See [output protection](privacy.md#output-policy-and-streaming) and [inspection limits](inspection.md#scope-and-limits).

<a id="korean-detection"></a>

## Korean Examples Work but Other Korean PII Is Missed

1. Check the actual entity and format. `KR` covers documented mobile-phone and resident-registration-number
   formats. Employee and customer IDs in the demo use additional application rules.
2. For names or addresses, configure a suitable recognizer/model and language. The sample Presidio
   profile uses English NLP and does not provide Korean NER. UI language does not change the analyzer's coverage.
3. Test representative synthetic positive and negative examples. See [analyzer choices](getting-started.md#choose-an-analyzer)
   and [built-in scope](privacy.md#built-in-rules).

<a id="inspection-not-running"></a>

## An Inspector Is Registered but Does Not Run

1. Check that the client policy selects `inspection()` and that the request uses that client.
   Final output inspection must be enabled separately. Confirm the selected direction has an
   `InspectionService`, either from inspector beans or supplied explicitly.
2. Check whether an earlier privacy or authorization failure prevented inspection, or an inspector's
   prerequisites failed. Inspect the reported phase and failure code. See [results and failures](inspection.md#results-policies-and-failures).
3. With the literal-rule example, `ignore previous instructions` should be blocked before any model
   call, while the allowed input should reach it. Compare the [Inspection demo](sample.md#inspection).

<a id="onnx"></a>

## ONNX Initialization or Execution Fails

1. For `CONFIGURATION`, check the local model and matching tokenizer files, external weight files if
   needed, input names/types/shapes, and output class settings. Resolve paths against the application's
   working directory. See [model requirements](inspection.md#model-requirements).
2. For `MODEL_ERROR`, check the output shape and numeric values against the configured `logitCount`
   and activation. A six-class toxicity model cannot use the example's two-class settings unchanged.
3. Run the [pinned test-model setup](inspection.md#reproduce-onnx-tests) to compare with a known model.
   Passing those tests verifies that fixture, not every custom model or language.

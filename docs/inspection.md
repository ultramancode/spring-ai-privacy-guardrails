---
description: >-
  Inspect model-bound text with rules or an HTTP guard model.
  Combine content decisions with privacy processing and tool authorization.
---

# Content inspection

**English** | [한국어](ko/inspection.md)

Content inspection is an opt-in feature in the 0.4.0 development line. It evaluates text before each business model call, including tool-loop continuations. It does not replace privacy processing or tool authorization, and a completed inspection is not a guarantee that content is safe.

## Modules and responsibilities

| Module suffix (`spring-ai-privacy-guardrails-…`) | Responsibility |
| --- | --- |
| `inspection-core` | Spring-independent request, result, policy and execution contracts |
| `inspection-rules` | Literal and RE2/J rule matching |
| `inspection-openai-compatible` | Dedicated HTTP calls to a guard model using an explicitly selected output protocol |
| `inspection-spring-ai` | Extraction of supported text at `ModelRequestBoundary` and client-scoped enforcement |
| `inspection-spring-boot-starter` | Opt-in configuration and bean wiring; no model backend is selected automatically |

Inspectors produce findings and report completion. `InspectionService` validates their results, applies the content policy, and handles operational failures. The Spring AI integration enforces the decision before the business model runs. The shared boundary orders privacy processing, tool-definition authorization, then inspection, regardless of configurer registration order.

## Explicit client configuration

Add the inspection starter and the desired inspector module. Define at least one `ContentInspector` bean and enable inspection:

```yaml
spring:
  ai:
    inspection:
      enabled: true
      max-segments: 64
      max-characters: 131072
      timeout: 10s
      failure-policy: FAIL_CLOSED
```

These are the defaults except for `enabled`, which defaults to `false`. Enabling without an inspector fails startup. Inspectors execute in Spring bean order (`@Order`), or list order when constructing `InspectionService` directly. A block stops subsequent inspectors.

Apply the provided `InspectionChatClientConfigurer` to a selected client. With privacy enabled, compose both configurers:

```java
ChatClient client = ModelRequestBoundaryConfigurer.compose(
        privacyConfigurer, inspectionConfigurer)
    .configure(ChatClient.builder(chatModel))
    .build();
```

For privacy plus authorization, use `privacySecurityFactory.builderWithBoundary(chatModel, inspectionConfigurer).build()`. For authorization without privacy, use the corresponding `ToolAuthorizationChatClientFactory.builderWithBoundary` method. See [tool authorization](security.md) for managed tool registration. Inspection does not modify a shared `ChatModel` or globally configure every client.

## HTTP guard models and privacy

The HTTP module uses its own non-streaming HTTP client, outside the business `ChatClient` advisor chain. Select a protocol that matches the deployed guard model; an OpenAI-compatible transport alone does not establish compatible guard output.

```java
ContentInspector inspector = OpenAiCompatibleContentInspector.kanana(
    "primary-guard",
    OpenAiCompatibleInspectionConfig.kanana(
        URI.create("http://127.0.0.1:8000/v1/chat/completions")));
```

`kanana` parses the supported Kanana prompt-guard labels. `jsonGuard` requires a strict `SAFE`/`UNSAFE` JSON verdict. The endpoint is the complete chat-completions URL. API keys, request timeout and response-byte limits are explicit fields of `OpenAiCompatibleInspectionConfig`. Redirects are disabled. Each text segment produces one HTTP request, bounded by the common segment count and shared deadline.

Each `ContentSegment` carries a `PrivacyProcessingStatus` for its text:

| Status | Meaning |
| --- | --- |
| `UNKNOWN` | Whether privacy processing completed is unknown |
| `UNPROCESSED` | The caller knows privacy processing has not been applied |
| `PROCESSED` | Configured privacy processing completed; this does not certify that every piece of personal data was detected |

HTTP inspectors require every segment to be `PROCESSED` by default. Sending `UNKNOWN` or `UNPROCESSED` content requires the explicit `allowUnprocessedContent` setting. The service checks processing requirements before any inspector runs, and HTTP also checks when invoked directly.

The starter derives the status from the privacy integration when available. Without Boot, supply a `PrivacyProcessingStatusResolver` based on `PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(request)` in the four-argument inspection configurer constructor: map `true` to `PROCESSED` and `false` to `UNKNOWN`. A missing marker does not establish that processing never occurred. The default resolver, `PrivacyProcessingStatusResolver.unknown()`, returns `UNKNOWN`. A custom resolver is trusted application code and supplies the status for all text extracted from the current request; do not label content as processed unless the configured processing completed for that text.

## Results, policies and failures

An `inspectorId` identifies a configured instance, not a model family or protocol. IDs must be unique within a service. Named HTTP factories allow two instances of the same implementation to have different endpoints or application policy.

`InspectionPolicy.evaluate(inspectorId, findings)` receives immutable findings, including valid evidence collected before an operational failure. It returns `ALLOW` or `BLOCK`. The default blocks any finding. Scores are local to the inspector; model-specific detection thresholds belong to the provider. Do not average scores across unrelated models.

`InspectionResult` separates completion, `completedSegmentIds`, findings and a failure code. A segment ID belongs in `completedSegmentIds` only after its inspection finishes; this does not mean the segment is safe. A finding may come from a partially inspected segment. `COMPLETED` requires full coverage of every request segment. An inspector that knows its work is incomplete must report `FAILED`, retaining partial evidence. Returning null, unknown segment IDs, or a false claim of completion violates the SPI contract and produces `INVALID_RESULT`.

| Failure | Service behavior |
| --- | --- |
| `TIMEOUT`, `TRANSPORT_ERROR`, `HTTP_ERROR`, `MODEL_ERROR`, `INVALID_RESPONSE`, `INCOMPLETE` | `FAIL_CLOSED` blocks; explicit `FAIL_OPEN` can allow only if the content policy also allows the retained findings |
| `CANCELLED`, `LIMIT_EXCEEDED`, `DISCLOSURE_DENIED`, `CONFIGURATION`, `UNSUPPORTED_CONTENT`, `INVALID_RESULT` | Throws `InspectionException`; neither a policy nor `FAIL_OPEN` can allow the request |

Results are validated before findings reach the policy. Invalid associations are discarded; an already declared hard failure is preserved. A timeout never turns a provider result into completed work. Cancellation preserves the thread interrupt flag. Findings collected before a provider failure remain available when the provider can return a partial result.

`InspectionReport` includes normalized outcomes in execution order. Inspectors omitted due to a prior block have no outcome. `allowedAfterFailure()` distinguishes explicit fail-open from fully completed inspection. Hard exceptions from the service carry the accumulated report through `report()`; failures during input extraction or request construction have no aggregate report. At the Spring AI boundary, a report with `BLOCK` raises `InspectionBlockedException` and prevents the business model call.

## Observability

Register an `InspectionObserver` bean to observe both reports and hard failures through Boot. Without Boot, pass it to the inspection configurer. A lambda is sufficient for reports; implement `onFailure` as well to observe hard failures:

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

The audit methods above are application hooks. A normal content block is delivered to `onInspection`; a hard inspection failure is delivered to `onFailure`. Callbacks run inline and may run concurrently or on a streaming worker, so they should return promptly. Observer runtime exceptions do not change enforcement; fatal JVM errors are not suppressed. Reports contain diagnostic identifiers and findings rather than prompt or response text. Keep custom IDs and finding codes free of user content.

## Scope and limits

`ContentSegment` is a logical text unit identified independently for findings and inspection completion. The Spring AI adapter creates one segment per supported system, user or assistant message text, and one per response body inside a tool response message. It preserves order without grouping by role: three user messages yield three segments, while a tool response message containing two responses yields two segments. A role describes the carrying message; it does not establish original source or trustworthiness. The built-in inspectors evaluate these text units independently rather than interpreting a whole conversation.

Model output, tool-call arguments and tool definitions are outside this text-inspection scope. Media, custom message types and structured-output request modes are rejected as unsupported rather than reported as inspected.

Common limits are the number of segments, total text length in Java UTF-16 code units, and one shared inspection deadline. They are per model-bound request, so tool-loop continuations receive a new budget. Custom synchronous inspectors must cooperate with the deadline and interruption; the interface cannot forcibly terminate arbitrary application code.

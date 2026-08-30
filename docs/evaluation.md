---
hide:
  - footer
---

# Evaluation and Benchmarks

**English** | [한국어](ko/evaluation.md)

This repository includes a regression test for the demo analyzer,
privacy-boundary tests, and JMH benchmarks. The regression test checks detection
results, the boundary tests check policy enforcement, and the JMH benchmarks
measure local processing time. These results are not interchangeable and do
not guarantee accuracy or latency in a production environment.

## Demo Analyzer Regression Test

The demo's Regex analyzer uses the same configuration as the runnable sample and
is tested against a synthetic dataset. The test checks that the expected entity
types and raw values are detected, those raw values are absent from the
tokenized output, and request sessions are cleaned up.

To run only the regression test with the default demo configuration:

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:test --tests io.github.ultramancode.springai.privacy.sample.DemoRegexEvaluationTest
```

This test is intended to detect changes in the demo rules. These results do not
represent general PII-detection accuracy or detection performance across
languages and domains. Validate analyzers intended for production separately
with data representative of the target environment.

## Privacy Boundary Tests

Boundary tests do not measure detection accuracy. They verify configured policy
enforcement at the model, tool, output, and request-lifecycle boundaries. Run
all repository checks with:

```bash
./gradlew --no-daemon clean check
```

By default, the test suite uses test models and local components. Tests that use
live remote models or analyzer services are opt-in.

### Privacy Boundary Verification Matrix

The matrix records privacy boundaries verified by reproducible automated tests.

| Boundary | Verified behavior | Test |
| --- | --- | --- |
| Direct prompt → model | Raw detected PII is replaced with an opaque request-scoped token before the model call. | [`PrivacyChatClientIntegrationTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyChatClientIntegrationTest.java) |
| Spring AI chat memory → model copy | Stored memory can retain application-owned raw text while the copy sent to the model is tokenized. | [`PrivacyChatMemoryIntegrationTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyChatMemoryIntegrationTest.java) |
| Spring AI VectorStore RAG → model | When retrieval returns a document containing detected PII, the raw value is replaced with an opaque token before the model call. | [`PrivacyVectorStoreRagIntegrationTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyVectorStoreRagIntegrationTest.java) |
| Allowed tool input value disclosure | A scoped tool receives originals only for explicitly allowed entity types. | [`PrivacyToolCallbackWrapperTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyToolCallbackWrapperTest.java) |
| Denied tool input value disclosure | Raw values of disallowed inputs remain protected. | [`PrivacyToolCallbackWrapperTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyToolCallbackWrapperTest.java) |
| Tool result → model | Detected PII in tool results is re-tokenized before returning to the model. | [`PrivacySequentialToolIntegrationTest`](../spring-ai-privacy-guardrails-test/src/test/java/io/github/ultramancode/springai/privacy/test/PrivacySequentialToolIntegrationTest.java) |
| MCP Streamable HTTP tool round trip | The local MCP round trip restores only allowed input values, keeps denied values protected, and re-protects results before they return to the model. | [`McpToolLoopIntegrationTest`](../samples/spring-ai-demo/src/test/java/io/github/ultramancode/springai/privacy/sample/McpToolLoopIntegrationTest.java) |
| Spring Security tool definition and execution authorization | Denied definitions are hidden, exposed tools are re-authorized before execution, the full batch is checked before any callback starts, and original PII is restored only after authorization. | [`SpringSecurityToolBoundaryIntegrationTest`](../spring-ai-privacy-guardrails-spring-security/src/test/java/io/github/ultramancode/springai/privacy/security/SpringSecurityToolBoundaryIntegrationTest.java) |
| Tool Search and callback mutation | Only authorized definitions are indexed, selected business callbacks must belong to the captured set, resolver fallback cannot activate hidden tools, and unsupported callback additions or replacements fail closed. | [`SpringSecurityToolBoundaryIntegrationTest`](../spring-ai-privacy-guardrails-spring-security/src/test/java/io/github/ultramancode/springai/privacy/security/SpringSecurityToolBoundaryIntegrationTest.java) |
| Blocking, reactive, and asynchronous SecurityContext | `Authentication` is captured from blocking and Reactor security contexts at request entry, reactive context takes precedence for streaming, missing context is denied, propagated executors work, and sessions close on completion or cancellation. | [`SpringSecurityToolBoundaryIntegrationTest`](../spring-ai-privacy-guardrails-spring-security/src/test/java/io/github/ultramancode/springai/privacy/security/SpringSecurityToolBoundaryIntegrationTest.java) |
| Request lifecycle on completion and error | Sessions are closed after normal completion and downstream failure. | [`PrivacyLifecycleAdvisorTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyLifecycleAdvisorTest.java) |
| Logical streaming response protection | Output frames are buffered as one logical response so PII split across frames is protected before delivery to the subscriber. | [`PrivacyOutputAdvisorStreamTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyOutputAdvisorStreamTest.java) |
| Streaming cancellation after partial response buffering | Cancellation emits no raw PII, cancels upstream processing, closes the privacy session, and invalidates its mapping. | [`PrivacyLifecycleAdvisorTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyLifecycleAdvisorTest.java) |

## JMH Benchmarks

The repository's JMH benchmarks measure execution time for key local processing
paths, including Regex analysis, request-scoped tokenization, tool-boundary
processing, and detokenization. In the same environment, the results can be
used to compare scaling behavior and version-to-version performance changes.

Run the full benchmark suite with:

```bash
./gradlew :spring-ai-privacy-guardrails-benchmarks:jmh
```

Results are written to
`spring-ai-privacy-guardrails-benchmarks/build/reports/jmh/results.json`. Use the
same JVM and execution environment when comparing results.

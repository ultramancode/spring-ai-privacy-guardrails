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
protected output, and request sessions are cleaned up. Detected PII is replaced
with **opaque tokens**, replacement strings that do not directly reveal the
original values.

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
| Direct prompt → model | Raw detected PII is replaced with a request-scoped opaque token before the model call. | [`PrivacyChatClientIntegrationTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyChatClientIntegrationTest.java) |
| Spring AI chat memory → model copy | Stored memory can retain application-owned raw text while detected PII in the copy sent to the model is replaced with opaque tokens. | [`PrivacyChatMemoryIntegrationTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyChatMemoryIntegrationTest.java) |
| Spring AI VectorStore RAG → model | When retrieval returns a document containing detected PII, the raw value is replaced with an opaque token before the model call. | [`PrivacyVectorStoreRagIntegrationTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyVectorStoreRagIntegrationTest.java) |
| Allowed tool input value disclosure | A scoped tool receives originals only for explicitly allowed entity types. | [`PrivacyToolCallbackWrapperTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyToolCallbackWrapperTest.java) |
| Denied tool input value disclosure | Raw values of disallowed inputs remain protected. | [`PrivacyToolCallbackWrapperTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyToolCallbackWrapperTest.java) |
| Tool result → model | Detected PII in tool results is replaced with opaque tokens before returning to the model. | [`PrivacySequentialToolIntegrationTest`](../spring-ai-privacy-guardrails-test/src/test/java/io/github/ultramancode/springai/privacy/test/PrivacySequentialToolIntegrationTest.java) |
| MCP Streamable HTTP tool round trip | The local MCP round trip restores only allowed input values, keeps denied values protected, and re-protects results before they return to the model. | [`McpToolLoopIntegrationTest`](../samples/spring-ai-demo/src/test/java/io/github/ultramancode/springai/privacy/sample/scenario/McpToolLoopIntegrationTest.java) |
| Spring Security tool discovery and execution authorization | Only allowed tools are shown to the model, and every tool requested in one response is authorized before any starts. Permission is checked again immediately before each tool runs. When privacy protection is also used, original PII is restored only after authorization. | [`SpringSecurityToolBoundaryIntegrationTest`](../spring-ai-privacy-guardrails-spring-security/src/test/java/io/github/ultramancode/springai/privacy/security/SpringSecurityToolBoundaryIntegrationTest.java), [`ToolAuthorizationStandaloneIntegrationTest`](../spring-ai-privacy-guardrails-spring-security/src/test/java/io/github/ultramancode/springai/privacy/security/ToolAuthorizationStandaloneIntegrationTest.java) |
| Tool Search and tool changes | Only allowed tools are registered for search, and selected tools are checked against those registered at the start of the request. Requests for denied tools by name and unsupported tool additions or replacements during the request are rejected before execution. | [`SpringSecurityToolSearchIntegrationTest`](../spring-ai-privacy-guardrails-spring-security/src/test/java/io/github/ultramancode/springai/privacy/security/SpringSecurityToolSearchIntegrationTest.java), [`SpringSecurityToolMutationIntegrationTest`](../spring-ai-privacy-guardrails-spring-security/src/test/java/io/github/ultramancode/springai/privacy/security/SpringSecurityToolMutationIntegrationTest.java) |
| Tool authorization across call types | Tool permissions are checked using the user's authentication obtained at the start of the request. Tools do not execute without authentication. See [Authentication Handling for Tool Authorization](#authentication-handling-for-tool-authorization) for detailed checks. | [`SpringSecurityContextPropagationIntegrationTest`](../spring-ai-privacy-guardrails-spring-security/src/test/java/io/github/ultramancode/springai/privacy/security/SpringSecurityContextPropagationIntegrationTest.java) |
| Request lifecycle on completion and error | Sessions are closed after normal completion and downstream failure. | [`PrivacyLifecycleAdvisorTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyLifecycleAdvisorTest.java) |
| Logical streaming response protection | Output frames are buffered as one logical response so PII split across frames is protected before delivery to the subscriber. | [`PrivacyOutputAdvisorStreamTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyOutputAdvisorStreamTest.java) |
| Streaming cancellation after partial response buffering | Cancellation emits no raw PII, cancels upstream processing, closes the privacy session, and invalidates its mapping. | [`PrivacyLifecycleAdvisorTest`](../spring-ai-privacy-guardrails-spring-ai/src/test/java/io/github/ultramancode/springai/privacy/springai/PrivacyLifecycleAdvisorTest.java) |

### Spring Security Test Configurations

Tool authorization and Tool Search are tested in both configurations:

- Tool authorization alone.
- Tool authorization combined with privacy protection.

### Authentication Handling for Tool Authorization

Tests verify that tool authorization uses the authentication provided by the
application under the following conditions:

- **Streaming:** Tool permissions are checked using the user registered in
  Reactor's security context. When the calling thread also has authentication,
  the Reactor identity takes precedence. The calling thread's authentication is
  used only when no Reactor security context is present.
- **Missing authentication:** A blocking request with registered tools and no
  authentication is rejected without executing tools. For streaming, an
  explicitly empty Reactor security context causes rejection even if the
  calling thread has authentication.
- **Asynchronous calls:** When a `ChatClient` invocation starts on another
  thread, configuring authentication propagation allows tools to execute with
  that user's permissions. Without propagation, a request lacking
  authentication is rejected without executing tools.
- **Request cleanup:** No tool-authorization state for the request remains
  after completion, rejection due to missing authentication, or streaming
  cancellation.

For authentication propagation setup and applicable conditions, see
[Blocking, Reactive, and Asynchronous Context](security.md#blocking-reactive-and-asynchronous-context).

## JMH Benchmarks

The repository's JMH benchmarks measure execution time for key local processing
paths, including Regex analysis, request-scoped PII tokenization, tool-boundary
processing, and detokenization. In the same environment, the results can be
used to compare scaling behavior and version-to-version performance changes.

Run the full benchmark suite with:

```bash
./gradlew :spring-ai-privacy-guardrails-benchmarks:jmh
```

Results are written to
`spring-ai-privacy-guardrails-benchmarks/build/reports/jmh/results.json`. Use the
same JVM and execution environment when comparing results.

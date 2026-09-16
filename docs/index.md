# Spring AI Privacy Guardrails

**English** | [한국어](ko/index.md)

![Spring AI Privacy Guardrails execution boundary](images/hero.svg)

Keep detected PII out of the model. Reveal only what each trusted tool needs.
Protect every tool result before returning it to the model or application.

Detection answers **what text is sensitive**. Spring AI Privacy Guardrails
tokenizes detected PII before sending it to a model. Each detected value is
replaced with an **opaque token**, a string that does not directly reveal the
original value. It also limits which original values each tool may receive.

The optional [Spring Security integration](security.md) controls which tools
are shown to the model and checks authorization before tool execution. It can
be used independently of PII protection.

Featured on the Spring Blog:
[This Week in Spring — August 18, 2026](https://spring.io/blog/2026/08/18/this-week-in-spring-august-18-2026/).

## See It in Action

The Privacy Boundary Inspector lets you compare the values received by the
model and tools in Local Tool, RAG, and MCP scenarios. Security compares tool
access for users with different roles.

![Privacy Boundary Inspector comparing model and tool inputs in Local Tool, RAG, MCP, and Security scenarios](images/privacy-boundary-inspector-demo.gif)

See the [Sample / Demo Guide](sample.md) for the complete Inspector workflow.

## Reference

| Guide | Covers |
| --- | --- |
| [Getting Started](getting-started.md) | Starter selection, basic setup, and model, tool, MCP, and output protection. |
| [Sample / Demo Guide](sample.md) | Inspector scenarios, expected results, endpoints, and language selection. |
| [Configuration](configuration.md) | Starters, analyzers, output policy, tool disclosure, and processing limits. |
| [Spring Security Tool Authorization](security.md) | Optional principal-aware tool discovery and execution checks, Tool Search, and asynchronous context. |
| [Architecture](architecture.md) | Module boundaries, request sessions, evidence resolution, and execution lifecycle. |
| [Threat model](threat-model.md) | Protected assets, trust boundaries, controls, limitations, and separately managed areas. |
| [Evaluation](evaluation.md) | Boundary tests, the deterministic analyzer baseline, and repository benchmarks. |

## Run the sample

The default sample uses a local `ChatModel` and requires no external model API key.

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:run
```

Open `http://127.0.0.1:8080` to check how PII is protected in Local Tool, RAG,
and MCP scenarios, and compare role-based tool access in Security.

See the [Sample / Demo Guide](sample.md) for the Inspector workflow and the
[full sample application guide](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/samples/spring-ai-demo/README.md)
for Presidio and OpenNLP profiles and integration examples.

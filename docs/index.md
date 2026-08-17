# Spring AI Privacy Guardrails

![Spring AI Privacy Guardrails execution boundary](images/hero.svg)

Keep detected PII out of the model. Reveal only what each trusted tool needs.
Protect every tool result before it leaves the tool boundary.

Detection answers **what text is sensitive**. Spring AI Privacy Guardrails
enforces **where the original value may travel** across model, tool, output,
and request-lifecycle boundaries.

## See It in Action

The Privacy Boundary Inspector shows Local Tool, RAG, and MCP runtime evidence
returned by the sample backend.

![Privacy Boundary Inspector showing Local Tool, RAG, and MCP runtime evidence](images/privacy-boundary-inspector-demo.gif)

See the [Sample / Demo Guide](sample.md) for the complete Inspector workflow.

## Reference

| Guide | Covers |
| --- | --- |
| [Getting Started](getting-started.md) | Starter selection, basic setup, and model, tool, MCP, and output protection. |
| [Sample / Demo Guide](sample.md) | Inspector scenarios, runtime endpoints, locale behavior, and evidence boundaries. |
| [Configuration](configuration.md) | Starters, analyzers, output policy, tool disclosure, and processing limits. |
| [Architecture](architecture.md) | Module boundaries, request sessions, evidence resolution, and execution lifecycle. |
| [Threat model](threat-model.md) | Protected assets, trust boundaries, controls, limitations, and separately managed areas. |
| [Evaluation](evaluation.md) | Boundary tests, the deterministic analyzer baseline, and repository benchmarks. |

## Run the sample

The sample uses a deterministic local `ChatModel` and requires no cloud
credentials.

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:run
```

Open `http://127.0.0.1:8080` to inspect Local Tool, RAG, and MCP runtime
evidence at the model and tool boundaries.

See the [Sample / Demo Guide](sample.md) for the Inspector workflow and the
[full sample application guide](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/samples/spring-ai-demo/README.md)
for optional profiles and integration examples.

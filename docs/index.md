# Spring AI Privacy Guardrails

![Spring AI Privacy Guardrails execution boundary](images/hero.svg)

Keep detected PII out of the model. Reveal only what each trusted tool needs.
Protect every tool result before it leaves the tool boundary.

Detection answers **what text is sensitive**. Spring AI Privacy Guardrails
enforces **where the original value may travel** across model, tool, output,
and request-lifecycle boundaries.

## Reference

| Guide | Covers |
| --- | --- |
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

Open `http://127.0.0.1:8080` to inspect the tokenized model input, scoped tool
disclosure, tool-result retokenization, and request cleanup.

See the [sample application](https://github.com/ultramancode/spring-ai-privacy-guardrails/tree/main/samples/spring-ai-demo)
for runnable profiles and integration examples.

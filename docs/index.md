---
description: >-
  Tokenize model-bound PII, disclose originals per tool, and select tool authorization and content inspection.
---

# Spring AI Privacy Guardrails

**English** | [한국어](ko/index.md)

**Send detected PII to the model as tokens. Reveal only the originals each tool needs, then protect its result again.**

| What you need | Start here |
| --- | --- |
| PII tokenization and per-tool original disclosure | [Privacy setup](getting-started.md#privacy-protection) |
| Tool visibility and execution based on the user's permissions | [Tool authorization setup](getting-started.md#tool-authorization) |
| Request and response checks using rules, ONNX or an HTTP model | [Content inspection setup](getting-started.md#content-inspection) |

Use these features independently or together.

[![Privacy flow with optional tool authorization and content inspection](images/hero.svg)](images/hero.svg)

[View the full diagram](images/hero.svg) · [Architecture](architecture.md) · [Troubleshooting](troubleshooting.md)

**Featured in:** [Spring Blog](https://spring.io/blog/2026/08/18/this-week-in-spring-august-18-2026/) · [Awesome Spring AI](https://github.com/spring-ai-community/awesome-spring-ai#extensions-and-forks)

## Reference

| Guide | Covers |
| --- | --- |
| [Getting Started](getting-started.md) | Common setup and first steps for privacy protection, tool authorization and content inspection. |
| [Configuration](configuration.md) | Dependencies, client creation, feature selection and policy reuse. |
| [Sample Guide](sample.md) | Inspector scenarios, expected results, endpoints, and language selection. |
| [Privacy Protection](privacy.md) | Analyzers, tokenization, tool disclosure, output protection and processing limits. |
| [Spring Security Tool Authorization](security.md) | Optional principal-aware tool discovery and execution checks, Tool Search, and asynchronous context. |
| [Content Inspection](inspection.md) | Input and optional output inspection with rules, local ONNX text classification models or HTTP guard models. |
| [Architecture](architecture.md) | Module boundaries, request sessions, evidence resolution, and execution lifecycle. |
| [Threat model](threat-model.md) | Protected assets, trust boundaries, controls, limitations, and separately managed areas. |
| [Evaluation](evaluation.md) | Boundary tests, the deterministic analyzer baseline, and repository benchmarks. |

## Sample

The sample application uses fixed examples to illustrate Local Tool, RAG, MCP, Security and Inspection.
See the [Sample Guide](sample.md) for scenarios and run instructions.

[![Privacy Boundary Inspector with Local Tool, RAG, MCP, Security and Inspection](images/privacy-boundary-inspector-demo.gif)](images/privacy-boundary-inspector-demo.gif)

---
description: >-
  Tokenize model-bound PII, disclose originals per tool, and select tool authorization and content inspection.
---

# Spring AI Privacy Guardrails

**English** | [한국어](ko/index.md)

**Send detected PII to the model as tokens. Reveal only the originals each tool needs, then protect its result again.**

**[Run the demo without an API key](sample.md#run-the-inspector)** · **[Add to an existing Spring AI app](getting-started.md)**

| What you need | Start here |
| --- | --- |
| PII tokenization and per-tool original disclosure | [Quick start with built-in presets](getting-started.md) |
| Tool visibility and execution based on the user's permissions | [Spring Security setup](security.md#add-the-spring-boot-starter) |
| Request and response checks using rules, ONNX or an HTTP model | [Content inspection setup](inspection.md#set-up-input-inspection) |

Use these features independently or together. Analyzers identify sensitive values.
The library applies tokenization and policies on supported Spring AI execution paths.

## See a Customer Lookup

In the Inspector's Local Tool scenario, the model receives tokens, the permitted tool
receives only the original customer ID, and the tool result is protected again.
Follow the [setup and success checks](sample.md#run-the-inspector).

[![Privacy Boundary Inspector with Local Tool, RAG, MCP, Security and Inspection](images/privacy-boundary-inspector-demo.gif)](images/privacy-boundary-inspector-demo.gif)

[![Privacy enforcement between models and tools](images/hero.svg)](images/hero.svg)

[View the full diagram](images/hero.svg) · [Architecture](architecture.md) · [Troubleshooting](troubleshooting.md)

**Featured in:** [Spring Blog](https://spring.io/blog/2026/08/18/this-week-in-spring-august-18-2026/) · [Awesome Spring AI](https://github.com/spring-ai-community/awesome-spring-ai#extensions-and-forks)

## Reference

| Guide | Covers |
| --- | --- |
| [Getting Started](getting-started.md) | Starter selection, basic setup, and model, tool, MCP, and output protection. |
| [Configuration](configuration.md) | Dependencies, client creation, feature selection and policy reuse. |
| [Sample / Demo Guide](sample.md) | Inspector scenarios, expected results, endpoints, and language selection. |
| [Privacy Protection](privacy.md) | Analyzers, tokenization, tool disclosure, output protection and processing limits. |
| [Spring Security Tool Authorization](security.md) | Optional principal-aware tool discovery and execution checks, Tool Search, and asynchronous context. |
| [Content Inspection](inspection.md) | Input and optional output inspection with rules, local ONNX text classification models or HTTP guard models. |
| [Architecture](architecture.md) | Module boundaries, request sessions, evidence resolution, and execution lifecycle. |
| [Threat model](threat-model.md) | Protected assets, trust boundaries, controls, limitations, and separately managed areas. |
| [Evaluation](evaluation.md) | Boundary tests, the deterministic analyzer baseline, and repository benchmarks. |

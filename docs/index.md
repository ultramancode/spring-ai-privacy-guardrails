---
description: >-
  Tokenize model-bound PII, disclose originals per tool, and select tool authorization and content inspection.
---

# Spring AI Privacy Guardrails

**English** | [한국어](ko/index.md)

**Send tokens to the model. Give each tool only the originals its policy allows.**

<br>

<p align="center">
  <a href="https://www.youtube.com/watch?v=gu5pPaA-NQc&amp;t=5s"><img src="images/demo-video-preview.png" alt="Watch the English demo on YouTube" width="480"></a>
</p>

<p align="center">
  <strong>Demo videos (YouTube):</strong>
  <a href="https://www.youtube.com/watch?v=gu5pPaA-NQc&amp;t=5s">English</a> ·
  <a href="https://www.youtube.com/watch?v=AqlubcLOtSc">한국어</a>
</p>

## Featured in

- [Spring Blog — This Week in Spring](https://spring.io/blog/2026/08/18/this-week-in-spring-august-18-2026/)
- [Awesome Spring AI](https://github.com/spring-ai-community/awesome-spring-ai#extensions-and-forks)

## Select the Protection You Need

| What you need | Start here |
| --- | --- |
| PII tokenization and per-tool original disclosure | [Privacy setup](getting-started.md#privacy-protection) |
| Tool visibility and execution based on the user's permissions | [Tool authorization setup](getting-started.md#tool-authorization) |
| Request and response checks using rules, ONNX or an HTTP model | [Content inspection setup](getting-started.md#content-inspection) |

Use these features independently or together.

## See It in Action

A customer asks for a delivery update with a customer ID and an email address.
The model calls the lookup tool with tokens. Only the customer ID is restored for the tool,
and PII in its result is protected again before it returns to the model.

[![A customer request passes through model-input protection, selective customer-ID disclosure and result protection before a delivery answer](images/customer-lookup.svg)](images/customer-lookup.svg)

This example uses a custom detection rule for customer IDs
and a per-tool disclosure policy for the lookup tool.

<br>

**Execution trace**

Two model calls surround one customer lookup, and the final response passes output inspection.
The application records model/tool calls and guardrail decisions together.

[![Recorded trace with two model calls, one customer lookup and a passing output inspection](images/runtime-trace.svg)](images/runtime-trace.svg)

## Reference

| Guide | Covers |
| --- | --- |
| [Getting Started](getting-started.md) | Common setup and first steps for privacy protection, tool authorization and content inspection. |
| [Configuration](configuration.md) | Dependencies, client creation, feature selection and policy reuse. |
| [Sample Guide](sample.md) | Inspector scenarios, expected results, endpoints, and language selection. |
| [Privacy Protection](privacy.md) | Analyzers, tokenization, tool disclosure, output protection and processing limits. |
| [Spring Security Tool Authorization](security.md) | Optional principal-aware tool discovery and execution checks, Tool Search, and asynchronous context. |
| [Content Inspection](inspection.md) | Input and optional output inspection with rules, local ONNX text classification models or HTTP guard models. |
| [Runtime Observation](observability.md) | Recorded normal and blocked requests and Observer usage. |
| [Architecture](architecture.md) | Module boundaries, request sessions, evidence resolution, and execution lifecycle. |
| [Threat model](threat-model.md) | Protected assets, trust boundaries, controls, limitations, and separately managed areas. |
| [Evaluation](evaluation.md) | Boundary tests, the deterministic analyzer baseline, and repository benchmarks. |
| [Troubleshooting](troubleshooting.md) | PII detection, rule selection, tool disclosure, authorization, streaming and inspection setup issues. |

## Sample

The sample application uses fixed examples to illustrate Local Tool, RAG, MCP, Security and Inspection.
See the [Sample Guide](sample.md) for scenarios and run instructions.

[![Privacy Boundary Inspector with Local Tool, RAG, MCP, Security and Inspection](images/privacy-boundary-inspector-demo.gif)](images/privacy-boundary-inspector-demo.gif)

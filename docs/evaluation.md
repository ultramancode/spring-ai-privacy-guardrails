# Reproducible Evaluation and Benchmarks

[English](evaluation.md) | [한국어](ko/evaluation.md)

The project separates two kinds of evidence:

- analyzer evaluation measures whether a configured detector returns the
  expected source spans;
- boundary tests verify that raw values do not cross model, tool, output, or
  session-lifecycle boundaries without authorization.

## Demo Analyzer Evaluation

The runnable demo includes a versioned synthetic corpus evaluated through the same
auto-configured `PrivacyService` and regex rules used by the sample application.
Expected spans are stored with the cases, so metrics are calculated on every run.

The corpus exercises declared demo entity types, repeated values, multiple
entities, structured-looking text, punctuation boundaries, malformed identifiers,
and negative cases. General-language detection is outside the regex sample's scope.

Run the sample evaluation with:

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:test
```

The evaluation also checks that expected raw values are absent from protected
results and that request sessions are closed after each case.

## Interpretation Limits

The synthetic corpus is a deterministic regression baseline for the documented
demo configuration. It is not evidence of general PII-detection accuracy and is
not representative of every language, domain, format, or adversarial input.

Remote detector quality depends on its recognizers, language pipeline, thresholds,
and deployment. An in-process adapter depends on the application-supplied model.
Calibrate every provider against representative, legally usable application data.

The Spring AI integration suite separately verifies enforcement at model, tool,
output, and request-lifecycle boundaries.

## Repository Benchmarks

The repository-only benchmark module measures costs owned by this project. Its
workloads cover analysis, request-scoped tokenization, tool-boundary protection,
and growing request mappings. It is not a published user-facing artifact.

Use the smoke profile to confirm that all benchmarks compile and execute:

```bash
./gradlew :spring-ai-privacy-guardrails-benchmarks:jmhSmoke
```

Run the reproducible measurement profile with:

```bash
./gradlew :spring-ai-privacy-guardrails-benchmarks:jmh
```

## Reporting and Comparison

Benchmark results describe only the exact workload, commit, JVM, and machine
used. They are not production latency targets or detector-accuracy claims.
When publishing a result, record the JDK, operating system, CPU, repository
commit, command, and any changed benchmark parameters.

Profile network detectors and application-model inference separately from core
overhead; comparisons require their deployment and model details.

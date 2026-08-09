# Evaluation and Benchmarks

[English](evaluation.md) | [한국어](ko/evaluation.md)

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

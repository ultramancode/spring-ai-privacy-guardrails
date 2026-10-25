# Contributing

Thanks for your interest in Spring AI Privacy Guardrails.

The project is pre-1.0. Its contribution flow is:

1. Open an issue for a bug, feature, or design discussion.
2. Keep pull requests focused and small.
3. Include tests for behavior changes.
4. Keep public APIs provider-neutral unless the module is explicitly provider-specific.
5. Keep core diagnostics privacy-safe and low-cardinality; broad dashboards,
   tracing backends, safety platforms, and agent runtime remain separate scope.

## Development

```bash
./gradlew clean check
```

Java 17 is the baseline, and CI also tests Java 21 and 25. New behavior must include
focused tests; changes to Spring AI boundaries should include an integration
test where practical. `check` runs the test suite and verifies the repository's
module rules. Update the declared module policy when a change intentionally
alters that graph.

ONNX tests download and cache a public model on first run.
See the [ONNX test setup](scripts/inspection/README.md) for cache and reference-data instructions.

To reproduce another compatibility lane locally, install that JDK and override
the build/test toolchain, for example with `-PtestJavaVersion=21`.

Name JUnit test methods in `lowerCamelCase` without underscores.

English documentation is canonical. Korean translation updates are welcome but
not required when changing English documentation. Maintainers synchronize
localized documentation separately and verify it before a release with
`./gradlew verifyDocTranslations`.

For a local snapshot consumed by another build, run
`./gradlew publishToMavenLocal` and add `mavenLocal()` only in that local
consumer.

## Built-in PII Rules

Built-in PII rules live in
`spring-ai-privacy-guardrails-core/src/main/java/io/github/ultramancode/springai/privacy/core/rules`.
Keep each rule's detection logic, validation, and metadata in its own package-private
class implementing `PiiRule`. Reuse `PatternPiiRule` for regular-expression matching.
Declare its ID in `BuiltInPiiRuleIds` and add an instance to `BuiltInPiiRules.rules()`.
To include the rule in a built-in preset, add its ID constant to the corresponding
definition in `BuiltInPiiRules.presets()`. A rule does not need to belong to a preset.
Declare built-in preset names in `BuiltInPiiPresetNames`.

Place rule-specific tests in the matching test package. Keep catalog registration
and preset checks separate from individual detection cases. To run these rule tests:

```bash
./gradlew :spring-ai-privacy-guardrails-core:test --tests 'io.github.ultramancode.springai.privacy.core.rules.*Test'
```

## Developer Certificate of Origin

All commits must include a `Signed-off-by` trailer at the end of the commit
message to indicate that the contributor agrees to the
[Developer Certificate of Origin](https://developercertificate.org/).

Use the following command to add the trailer automatically:

```bash
git commit -s
```

## Commit Messages

Use Conventional Commits for commit messages:

- `feat:` for user-facing features.
- `fix:` for bug fixes.
- `docs:` for documentation-only changes.
- `test:` for test-only changes.
- `refactor:` for code changes that do not change behavior.
- `build:` for build, dependency, or publishing changes.
- `chore:` for repository maintenance.

Keep the subject concise and imperative, for example:

```text
fix: preserve tool result privacy mappings
docs: clarify privacy guardrails positioning
```

## License

By contributing, you agree that your contributions will be licensed under the Apache License, Version 2.0.

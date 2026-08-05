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

Java 21 is the baseline, and CI also tests Java 25. New behavior must include
focused tests; changes to Spring AI boundaries should include an integration
test where practical. `check` runs the test suite and verifies the repository's
module rules. Update the declared module policy when a change intentionally
alters that graph.

Name JUnit test methods in `lowerCamelCase` without underscores.

For a local snapshot consumed by another build, run
`./gradlew publishToMavenLocal` and add `mavenLocal()` only in that local
consumer.

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

# Changelog

## [0.2.1]

This maintenance release refreshes upstream compatibility baselines and CI
coverage without changing public APIs or privacy-enforcement behavior.

### Changed

- Updated the default Spring AI baseline to 2.0.1 and Spring Boot to 4.1.1.
- Updated Presidio Analyzer to 2.2.364 and Apache OpenNLP to 2.5.11.

### Documentation

- Improved navigation across the English and Korean documentation, including a
  Korean documentation home.
- Documented the project's mention in the official Spring Blog post
  [This Week in Spring — August 18, 2026](https://spring.io/blog/2026/08/18/this-week-in-spring-august-18-2026/).

### Compatibility

- Retained Java 17 CI verification for Spring AI 2.0.0 alongside the default
  2.0.1 baseline.
- Verified the live Presidio integration on Java 17.
- No breaking changes.

## [0.2.0]

This release lowers the Java baseline to 17 and adds privacy-safe runtime
observation for supported Spring AI privacy boundaries.

### Added

- Added `PrivacyEnforcementObserver` for model, tool-input, tool-result, and
  application-output boundaries with privacy-safe `PROTECTED`, `DISCLOSED`,
  and `BLOCKED` outcomes.

### Changed

- Lowered the minimum Java baseline from 21 to 17 while retaining compatibility
  verification on Java 17, 21, and 25.

### Documentation

- Added Getting Started guides, demo videos, and screenshots.

### Compatibility

- Existing applications do not need to configure a `PrivacyEnforcementObserver`;
  privacy enforcement behavior remains unchanged when no observer is registered.
- No breaking changes.

## [0.1.1]

This release adds runnable privacy-boundary demos and expands integration-test coverage.

### Added

- Added runnable RAG and Streamable HTTP MCP demos that show privacy boundaries at runtime.
- Added an English and Korean Privacy Boundary Inspector covering Local Tool, RAG, and MCP scenarios.
- Expanded integration-test coverage for PII protection in VectorStore RAG flows and cleanup after stream cancellation.
- Added English and Korean sample guides and refreshed Inspector demo GIFs.

### Compatibility

- Public APIs and runtime behavior of the published library modules remain unchanged.
- No breaking changes.

## [0.1.0]

Initial public release.

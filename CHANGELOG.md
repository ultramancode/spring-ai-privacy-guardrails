# Changelog

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

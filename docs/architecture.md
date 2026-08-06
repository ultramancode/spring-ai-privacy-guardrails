# Architecture

[English](architecture.md) | [한국어](ko/architecture.md)

## Responsibility Boundary

Spring AI Privacy Guardrails owns privacy enforcement at the application,
model, and tool execution boundaries. Detection remains pluggable.

```text
source text -> analyzer evidence -> core policy -> protected text
                                             -> model/tool/output boundaries
```

The caller-owned source text is the source of truth for offsets. Analyzers
report evidence; core validates, canonicalizes, filters, and resolves it before
any privacy transformation. A request-scoped session owns token identity and
the token-to-original mapping.

The library protects only the values that pass through a supported boundary.
It does not discover data hidden in opaque application objects, replace an
application's authorization policy, or rewrite data that was persisted before
the boundary was reached.

## Build Module Boundary

This repository is a Gradle multi-module library. The build graph, rather than
runtime package conventions, enforces the main dependency boundaries:

```text
core <- analyzer adapters
core + Spring AI adapter <- base Boot starter
provider adapter + base Boot starter <- provider Boot starter
Spring AI adapter <- test support
core + Spring AI adapter <- benchmarks and samples
```

`core` has no Spring dependency. Analyzer adapters depend on core policy but do
not depend on Spring AI. The Spring AI adapter connects core to framework
execution without owning provider configuration. Boot starters assemble those
pieces for explicitly configured applications.

Test support, benchmarks, and samples are repository consumers. They do not
define production policy, and the benchmark module is not published.

## Published API Boundary

Each published artifact uses one shallow public package. Gradle modules separate
core policy, analyzer providers, Spring AI integration, Boot configuration, and
test support. Package-private collaborators may change without becoming a
compatibility promise.

Public types fall into four roles:

| Role | Public surface |
| --- | --- |
| Consumer API | `PrivacyService`, sessions, policies, results, and value types |
| Extension SPI | `PiiAnalyzer`, safe failure observation, and tool disclosure policy |
| Framework integration | Spring AI advisors, callback factory, configurer, and Boot auto-configuration |
| Test support | Probes, snapshots, and assertions outside production artifacts |

Every published JAR declares a stable `Automatic-Module-Name`. This gives JPMS
consumers a deterministic name without claiming that a `module-info.java`
descriptor or JPMS export graph exists.

## Analyzer and Resolution Boundary

Every `PiiAnalyzer` has a provider identity and returns source ranges as
`PiiSpan` values. Core attributes that evidence to its provider before applying
application policy. Analyzer scores are provider-local evidence and are not
assumed to be calibrated across providers.

Entity labels are untrusted analyzer output. Core admits only valid labels and
maps unknown but valid labels to a generic protected type unless the application
explicitly establishes a trusted mapping.

Caller-supplied spans bypass provider selection, but not range validation,
canonicalization, filtering, or overlap resolution. Final resolved spans retain
offsets and provenance rather than copying source substrings.

Configured analyzer instances may be shared across concurrent requests. Custom
implementations must therefore be thread-safe and reentrant, use finite
deadlines for blocking work, and cooperate with interruption.

Provider selection, failure policy, thresholds, trust rules, limits, and overlap
semantics are configuration contracts documented in
[Detection and Resolution](configuration.md#detection-and-resolution).

## Request and Session Boundary

`PrivacySession` is the context model for both direct core use and Spring AI
integration. Each request receives a new opaque in-process handle and token
namespace. Only the handle crosses framework context; raw mappings are never
serialized into model or tool payloads.

The registry supports tool execution on another platform or virtual thread.
There is no process-global or `ThreadLocal` fallback. A missing, unknown, or
closed session fails before privacy-owned disclosure or transformation.

The session mapping is removed on normal completion, failure, and stream
cancellation. Removal releases library references; it is not secure erasure of
immutable strings or copies retained by application and provider code.

Direct core callers open and close the same session explicitly. Spring AI
integration owns that lifecycle around the protected client execution.

## Spring AI Execution Boundary

The Spring AI integration establishes one request lifecycle around the supported
model and tool flow:

```text
application input
  -> protected model request
  -> validated tool call
  -> capability-scoped tool execution
  -> protected tool result
  -> protected application output
```

The managed configurer installs a complete privacy bundle only on builders the
application selects. It does not globally modify every `ChatClient`, expose
independent switches for mandatory boundaries, or treat unrelated advisor beans
as replacements for that bundle.

Each component validates the session and the data it actually observes. An
incomplete or duplicated privacy bundle fails closed before protected execution.
Clients derived from a protected client retain its privacy boundary.

Custom advisors may add or replace content, options, callbacks, or responses.
Mutations that occur outside the corresponding privacy boundary are owned by
the application and require separate protection. The supported composition and
customization rules are documented under
[ChatClient Boundary](configuration.md#chatclient-boundary).

## Model and Output Boundary

All supported model-bound text is protected before provider invocation. This
includes initial input and supported text added later in the client flow.
Unsupported message implementations that can conceal text fail closed rather
than being rebuilt with unknown fields silently discarded.

When output protection is enabled, policy is applied to each complete logical
response before protected content is returned to the application. Streaming
responses are buffered when full-response inspection is required, so sensitive
values split across frames cannot bypass the policy.

Known textual channels receive the configured privacy action. Opaque control
data and application-defined metadata remain outside content inspection unless
the application classifies and protects them separately.

Output actions, supported streaming behavior, structured response handling, and
inspection limits are documented under
[Output Policy and Streaming](configuration.md#output-policy-and-streaming).

## Tool Execution Boundary

`PrivacyToolCallbackFactory` is the public construction path for privacy-aware
callbacks and callback providers. Wrapped tools are bound to their creating
privacy service and require an active matching request session.

Tool disclosure is default-deny. The application grants a set of canonical
entity types to an exact tool identity; only those values are restored
immediately before delegate execution. Registration alone grants no disclosure
capability.

Tool definitions and executable names that cross the model boundary are checked
for sensitive content. Tool input is protected according to its structured
contract before scoped disclosure. Tool results are protected again before
they can return to a model or application boundary.

Application-owned tool managers, resolvers, callbacks, and mutations outside the
factory-managed path are trusted application infrastructure. The library cannot
infer provenance or enforce disclosure on paths Spring AI does not expose.

Delegate failures propagate with their application-owned type and cause. Such
exceptions may contain disclosed values, so exception handling, logs, retries,
and observations remain application privacy responsibilities.

JSON handling, disclosure configuration, direct-return behavior, and resource
limits are documented under
[Tool Disclosure](configuration.md#tool-disclosure).

## Failure and Diagnostic Boundary

Failures created by the privacy library are typed and sanitized. Analyzer
failures cross core as stable provider, code, phase, and attempt metadata rather
than provider messages, response bodies, causes, or copied source text.

The optional failure observer receives the same safe event and cannot change the
analysis result. Safe health reporting exposes only a status and stable failure
category. Metrics, tracing, dashboards, and protected diagnostic sinks remain
application integrations.

Failures owned by model providers, tools, resource loaders, or other application
extensions propagate according to their native contracts. Applications must
assume those failures and their SDK logs can contain sensitive content. The
failure taxonomy is documented under
[Typed Privacy Failures](configuration.md#typed-privacy-failures).

## Resource and Cancellation Boundary

Privacy-owned text transformation, structured traversal, analyzer result
retention, and buffered inspection are bounded. Exact limits and units are
documented in [Configuration](configuration.md#final-model-output-inspection).

Reactor cancellation closes the request session, but Java cannot forcibly stop
arbitrary synchronous analyzer or trusted-tool code. Those collaborators must
honor interruption and their own finite network or execution deadlines.

Generation count, tool-loop count, cost, side effects, concurrency, and rate
limits are application or orchestration policy rather than privacy-library
policy.

## Test Boundary

`spring-ai-privacy-guardrails-test` records the content visible at model and
delegate-tool boundaries so applications can assert scoped disclosure,
retokenization, and session cleanup without a remote model.

The probe is test-only. It can retain captured raw values until cleared or
closed and must not be used as production observability infrastructure. Usage
is documented under [Test Support](configuration.md#test-support).

## Provider and Transport Boundary

Provider adapters translate provider results into the common analyzer SPI. They
do not change core resolution or disclosure policy. Remote transport security,
credentials, deployment topology, and recognizer or model quality remain
provider and application concerns.

The available remote and in-process profiles are described under
[Artifacts](configuration.md#artifacts).

## Persistence Boundary

Advisors protect copies of memory and retrieved content that cross a supported
model or tool boundary. They do not rewrite data already stored in
`ChatMemory`, a vector store, a database, traces, logs, or provider systems.

Applications must protect writes, retention, deletion, access control, and
diagnostics for persistent data separately. See
[Persistence and Diagnostic Boundaries](configuration.md#persistence-and-diagnostic-boundaries).

# Threat Model

[English](threat-model.md) | [한국어](ko/threat-model.md)

## Assets

- Raw PII in prompts, retrieved content, model text, tool arguments, and tool
  results.
- Request-scoped token-to-original mappings.
- Analyzer credentials, requests, and responses.
- Application policy defining which entity types a tool may receive.
- Protected output before it reaches application persistence, logs, or users.

## Trust Boundaries

The application, model provider, analyzer providers, and tool targets are
separate trust boundaries. Registering a tool does not authorize plaintext
disclosure; the application must grant canonical entity types to an exact tool
identity.

Model and analyzer confidentiality depends on their deployment contracts.
In-process extensions are trusted application code, but their data is validated
when it crosses a supported privacy boundary.

## Threats And Controls

| Threat category | Library control | Application responsibility |
| --- | --- | --- |
| Detected PII reaches a model or application response | Supported model-bound content is transformed before invocation; enabled output policy inspects complete supported responses | Calibrate detection recall and protect unsupported fields or later mutations |
| A token or session is reused, forged, or used on another thread | Tokens, mappings, and the explicit session handle are scoped to an unpredictable request namespace; invalid or closed sessions fail before disclosure | Keep the session open for the request, do not expose mappings, and never treat tokens as authentication |
| A tool receives unauthorized PII or returns new PII | Disclosure is default-deny for exact tool and entity identities; supported results are protected before the next boundary | Apply least privilege and separately protect custom managers, resolvers, callbacks, or result paths |
| Structured, streamed, or direct-return content hides sensitive values | Supported structures are decoded under bounds; enabled output protection inspects each complete logical response, including direct returns | Normalize application-specific encodings and accept buffering when complete-response inspection is enabled |
| Analyzer evidence is invalid, conflicting, or unavailable | Core validates and resolves evidence, enforces result bounds, and applies the configured failure policy | Secure, select, and calibrate providers; choose whether reduced availability may reduce coverage |
| Failures or diagnostics contain sensitive values | Library-owned failures and observations expose sanitized, low-cardinality metadata | Protect provider and tool exceptions, SDK logs, retries, traces, and detailed diagnostic sinks |
| Sensitive data survives in storage or process memory | Request cleanup removes library registry references and mappings are not serialized into framework payloads | Protect databases, `ChatMemory`, vector stores, logs, heap dumps, debuggers, and retained copies |
| Untrusted input or synchronous extensions exhaust resources | Privacy-owned traversal, retained evidence, and buffered inspection are bounded; extension contracts require finite deadlines and interruption cooperation | Set smaller deployment limits where needed and review trusted regex, analyzer, and tool implementations |
| Unsupported content or custom composition bypasses a boundary | Unknown content-bearing types fail closed where they cannot be preserved safely; managed integration validates the supported path | Keep custom mutations inside a protected boundary or protect them separately |

Exact policy choices and configurable limits are documented in
[Configuration](configuration.md).

## Important Limits

Protection applies to content detected by configured analyzers at supported
boundaries. It is not a guarantee that every sensitive value will be detected.

Opaque tokens and `PrivacyContextHandle` are in-process, pseudonymous request
metadata, not authentication credentials or anonymous data. Session cleanup
releases library references; it is not secure erasure of immutable strings or
other copies.

Partial analyzer results can reduce coverage. Library-owned failure events are
sanitized, while application-owned provider and tool failures retain their
native semantics. See [Configuration](configuration.md) for the failure-policy
contract.

## Out Of Scope

This project does not claim to prevent every form of data exfiltration,
malicious model behavior, prompt injection, compromised application or host
processes, unsafe application logging, privileged memory inspection, or
unauthorized access to persistent data.

It also does not replace provider governance, data-retention policy, network
security, tool authorization, or legal review.

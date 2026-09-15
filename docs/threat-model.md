# Threat Model

**English** | [한국어](ko/threat-model.md)

## Protected Assets

This document describes threats to the following information, the library's
protections, and the areas the application must manage separately.

- Original PII that may appear in model input, retrieved content, tool input and
  results, and final output.
- Token-to-original-PII-value mappings managed per request through
  `PrivacySession`.
- Credentials used for remote analyzer services and analyzer requests and
  responses.
- Original-value disclosure policy defining which entity types each tool may
  receive.
- When Spring Security integration is enabled, decisions about which tools may
  be exposed to the model and executed for the current user.

## Trust Boundaries

When privacy protection is configured, model and tool data is inspected and
protected through the library's supported integrations. The application is
responsible for data sent through other paths.

Remote analyzers such as Presidio receive the original text to inspect. The
application must manage service credentials and transport security, and check
the data retention policies of remote analyzers and model providers.

Custom analyzers, advisors, and tools are trusted application code. The library
validates detection results and protects data at supported processing points;
it does not restrict what that application code can do outside those points.

Spring Security integration relies on the application's authentication and tool
authorization policy. When privacy protection is also configured, registering
or authorizing a tool does not by itself permit original PII disclosure. The
application must separately configure which entity types may be restored from
tokens for each tool.

## Threats and Controls

### Model Input and Output

| Threat | Library control |
| --- | --- |
| Unprotected PII in model input or final output | Detected PII in supported model input text is replaced with tokens before the model call. When output protection is enabled, the configured policy applies to the final response body and supported reasoning text. |
| Inspection bypass through structured values, streaming, or `returnDirect` | JSON and supported value structures are inspected under size and complexity bounds. When output protection is enabled, streaming responses are collected through completion for inspection, and the configured output policy also applies to `returnDirect` results. |
| Inspection bypass through unsupported message types | Unsupported `Message` implementations cause an error instead of being sent to the model. |

**Scope and considerations**

- **Protection scope:** Unsupported content and data added or changed after the final
  privacy check are outside the automatic protection scope.
- **Encoded or compressed content:** Encoded or compressed content whose original text
  cannot be read directly must be converted by the application into text or supported
  JSON before inspection.
- **Metadata and media:** Apart from [supported reasoning text](configuration.md#reasoning-text-protection),
  response metadata and non-text content such as images and audio must be protected
  separately. Media limits check data size only; they do not detect PII in media content.

### Tool Privacy and Authorization

| Threat | Library control |
| --- | --- |
| Unauthorized original PII or new PII in tool results | For tools with privacy protection, restoring tokens to original values is denied by default. Only configured entity types are restored immediately before execution. Detected PII in tool results is replaced with tokens before the results are passed on. |
| Privacy steps omitted by custom advisors or tool paths | On a `ChatClient` with the library's standard privacy configuration, missing or duplicate required advisors and unprotected tool callbacks are detected. |
| Unauthorized tool discovery or execution | When Spring Security integration is configured, denied tools are excluded from the model's tool list and cannot be executed by requesting them by name. All tools requested in one model response are authorized before any runs. Each tool is checked again immediately before execution and, when privacy protection is enabled, before original PII is restored. |
| Callbacks added or replaced after authorization | With Spring Security integration configured, tool callbacks are checked against those present at the start of the request. Unsupported additions or replacements are rejected before execution. |

**Scope and considerations**

- **Tool privacy protection:** Original-value disclosure and result tokenization apply
  to tools wrapped by `PrivacyToolCallbackFactory`. Other execution paths must be
  protected separately.
- **Custom execution paths:** The application must protect advisors that change data
  outside the privacy boundary and separate execution paths that use a custom
  `ToolCallingManager` or `ToolCallbackResolver`.
- **Tool authorization:** Checks apply only to clients configured with Spring Security
  integration. Direct tool calls that bypass these checks are outside its scope. See
  [Spring Security Tool Authorization](security.md) for supported configurations.
- **Authentication and data access:** The application is responsible for authentication
  and authorization policy correctness. Access to a particular customer or record,
  identified by tool arguments, must be checked by the tool or service handling it.
- **Permission changes during a request:** Checks use the authentication captured at
  request start. To reflect permission revocation during a long-running request, the
  policy must consult current external state.

### Detection and Diagnostics

| Threat | Library control |
| --- | --- |
| Invalid or overlapping detections, or analyzer failures | The library validates detection spans and entity types. It resolves overlapping results and type conflicts according to defined rules and handles analyzer failures using the configured failure policy. |
| PII in errors or diagnostics | Privacy failures and analyzer-failure notifications produced by the library do not include original values or analyzer-service response content. |

**Scope and considerations**

- **External errors and logs:** Error messages and SDK logs from model providers and
  tools must be managed through the configuration of those components.

### Sessions and Resource Use

| Threat | Library control |
| --- | --- |
| Token reuse or invalid-session access | Tokens and original-value mappings are managed separately for each request through `PrivacySession`. Tokens from another request are not restored to original values, and unknown or closed sessions fail. |
| PII retained in a session after a request ends | When a Spring AI request using the standard privacy configuration completes, fails, or its stream is cancelled, the library cleans up the token-to-original-value mappings it manages. The mappings themselves are not included in model requests or tool input. |
| Resource exhaustion from very large or deeply nested input | The library enforces size, item-count, nesting-depth, and similar bounds when inspecting supported text, JSON, value structures, and responses. |

**Scope and considerations**

- **Direct API use:** Applications that use `PrivacyService` directly must manage each
  `PrivacySession` lifecycle and close the session.
- **Execution limits:** The application must configure execution-time and resource
  limits for custom analyzers and tools. Stream cancellation cleans up the session but
  cannot forcibly stop synchronous analyzer or tool code that is already running.

Exact policies and processing bounds are documented in
[Configuration](configuration.md).

## Important Limitations

Privacy protection applies to content detected by the configured analyzers. It
does not guarantee detection or classification of every sensitive value. Coverage
depends on the analyzers and score thresholds. Allowing some analyzer failures
lets processing continue with successful results, which may reduce coverage. See
[Detection and Resolution](configuration.md#detection-and-resolution) for details.

Tokenization does not guarantee anonymization. Tokens and session handles
(`PrivacyContextHandle`) must not be used as proof of user identity or permission.

Session cleanup releases the mappings held by the library. It does not delete
data already saved in storage or strings and copies retained by other components.

Spring Security integration controls which tools the model may discover and
execute. When combined with privacy protection, the `tools.disclosures` settings
still apply per tool and entity type; enabling authorization does not make those
settings user-specific.

## Areas Managed Separately

The protections described here apply when using the library's privacy APIs and
supported Spring AI integrations. The application and deployment environment
must manage the following areas separately.

- Prompt injection and model content safety.
- Application and host security, authentication, authorization policy design,
  tool calls that bypass authorization checks, storage access control,
  network security, and log management.
- Data-retention policies and legal or regulatory compliance for model and
  analyzer services.

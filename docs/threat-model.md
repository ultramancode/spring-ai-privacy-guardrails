# Threat Model

**English** | [한국어](ko/threat-model.md)

## Protected Assets

This threat model treats the following information as protected assets at the
privacy boundaries supported by the library.

- Original PII that may appear in model input, retrieved content, tool input and
  results, and final output.
- Token-to-original-PII-value mappings managed per request through
  `PrivacySession`.
- Credentials used for remote analyzer services and analyzer requests and
  responses.
- Original-value disclosure policy defining which entity types each tool may
  receive.

## Trust Boundaries

The application, external model providers, remote analyzer services, and tool
execution targets form separate trust boundaries. Registering a tool does not
by itself authorize original-value disclosure; the application must explicitly
configure the entity types that each tool may receive.

Custom analyzers and extensions run as part of the application. The library
validates analyzer results and applies the same policy to data that extensions
exchange through a supported privacy boundary. When a remote model or analyzer
service is used, transport security and data handling follow that service's and
the deployment environment's configuration.

## Threats and Controls

| Threat | Library control | Scope and considerations |
| --- | --- | --- |
| PII reaches a model without protection or is returned in a final response | PII in supported model input is protected before the model call. When output protection is enabled, the configured output policy is applied to the final response. | Detection coverage depends on the configured analyzers and settings. Unsupported content and changes made outside a privacy boundary are outside the automatic protection scope. |
| A token from another request is reused, or an original-value mapping is accessed through an invalid session | Tokens and original-value mappings are managed separately for each request through `PrivacySession`. Tokens from another request are not restored to original values, and unknown or closed sessions fail. | A session handle is an internal value used to distinguish request-scoped privacy state. |
| A tool receives unauthorized original PII or returns new PII | Original-value disclosure to tools is default-deny, and only configured entity types are restored immediately before execution. Results from protected tools are inspected and protected again. | On a protected `ChatClient`'s standard path, callbacks not wrapped by `PrivacyToolCallbackFactory` are rejected. Custom tool execution paths must be protected separately. |
| Structured values, streaming responses, or `returnDirect` flows bypass PII inspection | JSON and supported value structures are inspected under size and complexity bounds. When output protection is enabled, streaming responses are collected through completion for inspection, and the configured output policy also applies to `returnDirect` results. | Encoded or compressed content whose original text cannot be read directly must be converted by the application into text or supported JSON before inspection. |
| An analyzer returns invalid results, results overlap, or some analyzers fail | The library validates detection spans and entity types. It resolves overlapping results and type conflicts according to defined rules and handles analyzer failures using the configured failure policy. | Actual detection coverage can vary with the analyzers, score thresholds, and whether some analyzer failures are allowed. |
| Errors or diagnostic information contain PII | Privacy failures and analyzer-failure notifications produced by the library do not include original values or analyzer-service response content. | Error messages and SDK logs from model providers and tools must be managed through the configuration of those components. |
| PII remains in storage or another in-memory copy | When a Spring AI request using the standard privacy configuration completes, fails, or its stream is cancelled, the library cleans up the session mappings it manages. Original-value mappings are not included in model requests or tool input. | Applications that use `PrivacyService` directly must manage each `PrivacySession` lifecycle and close the session. The library does not automatically change or delete data already stored or copies created outside the library. |
| Very large input or slow custom analyzers and tools cause excessive processing time or memory use | The library enforces size, item-count, nesting-depth, and similar bounds when inspecting supported text and JSON, `core` value structures, and responses. | The application must configure execution-time and resource limits for custom analyzers and tools. Stream cancellation cleans up the session but cannot forcibly stop synchronous analyzer or tool code that is already running. |
| Unsupported messages, metadata, or non-text content contain PII | `Message` implementations that cannot be processed safely fail instead of being sent to the model. Privacy protection is applied to text in supported messages and to reasoning text explicitly supported by the library. | Other response metadata and non-text content such as images and audio must be protected separately. Media limits check data size only; they do not detect PII in media content. |
| A custom advisor or tool execution path omits a privacy step | On a `ChatClient` with the library's standard privacy configuration, missing or duplicate required advisors and unprotected tool callbacks are detected. | The application must protect advisors that change data outside the privacy boundary and separate execution paths that use a custom `ToolCallingManager` or `ToolCallbackResolver`. |

Exact policies and processing bounds are documented in
[Configuration](configuration.md).

## Important Limitations

Privacy protection operates at supported boundaries and applies to content
detected by the configured analyzers. It does not guarantee that every sensitive
value will always be detected or classified.

Tokens and `PrivacyContextHandle` are internal identifiers for request-scoped
PII mappings. They are not authentication credentials, and tokenized data is
not necessarily anonymous. Session cleanup releases mappings held by the
library, but it does not remove strings or copies already retained by other
components.

If the failure policy allows some analyzer failures, request processing continues
with results from the analyzers that succeeded. Detection coverage may therefore
be reduced. See
[Configuration](configuration.md#detection-and-resolution) for details.

## Areas Managed Separately

This threat model covers PII processing at the supported direct-use `core`
boundary and Spring AI integration boundaries. The application and deployment
environment must manage the following areas separately.

- Prompt injection and model content safety.
- Application and host security, user access control, tool-call authorization,
  storage access control, network security, and log management.
- Data-retention policies and legal or regulatory compliance for model and
  analyzer services.

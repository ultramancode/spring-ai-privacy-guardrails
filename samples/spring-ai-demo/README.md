# Spring AI Privacy Guardrails Demo

[English](README.md) | [한국어](README.ko.md)

This runnable sample shows how PII is protected before reaching the model and
which original values are allowed through to tools. The default configuration
uses fixed examples and a local scripted `ChatModel`, so no external LLM API key is required.
Detected PII is replaced with opaque tokens, replacement strings that do not
directly reveal the original values.

```text
raw user input -> tokenize PII in the model prompt
-> restore only allowed originals for tools -> tokenize PII in tool results
```

Demo responses do not serialize opaque token mappings, but unmatched text can still be
returned unchanged. Custom text is accepted only in a POST body so it is not
copied into URLs, browser history, or ordinary access-log request lines.

Commands in this document assume a Linux shell and are run from the repository
root.

## Run

For prerequisites and run instructions, see the [sample setup](../../docs/sample.md#run-the-inspector).

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:run
```

The demo binds only to `http://127.0.0.1:8080`.

### Privacy Boundary Inspector

Open that URL in a browser to use the sample-only **Privacy Boundary
Inspector**. Use the `Local Tool | RAG | MCP | Security | Inspection` selector to run the demos from one
page. Each view displays results returned by its demo endpoints.

<p align="center">
  <img src="../../docs/images/privacy-boundary-inspector-demo.gif" alt="Privacy Boundary Inspector showing protected model and tool boundaries" width="960">
</p>

### Inspector Runtime Endpoints

| Inspector view | Backend requests | Results to check |
| --- | --- | --- |
| `Local Tool` | `GET /demo/example-input`, `GET /demo/protect`, `GET /demo/tool-loop` | Check the localized example input, detection positions (`detectedSpans`), opaque tokens sent to the model, restoration of only the customer ID for the tool, and protection of the tool result. |
| `RAG` | `GET /demo/rag` | Compare the original retrieved document (`retrievedDocument`) with the complete protected prompt sent to the model (`modelInput`). |
| `MCP` | `GET /demo/example-input`, `GET /demo/mcp-tool-loop` | Check that a local Streamable HTTP MCP call also protects model input, restores only the customer ID for the tool, and protects the tool result. |
| `Security` | `GET /demo/security-tool-boundary` | Backend-recorded definition and execution decisions for the same customer lookup request as a general employee and a customer-support employee, callback counts, and privacy evidence from the allowed run. |
| `Inspection` | `GET /demo/inspection?example=block` or `example=allow` | Actual inspected text, input/output reports, model call counts, and the response delivered or withheld by the ChatClient. |

The `EN | 한국어` toggle sends `Accept-Language: en` or `ko` with these
requests and reruns the selected flow. This changes the UI labels, example
input, and results.

`activeSessionsAfterCall` is the service-wide active session count after the call.

These runtime demonstrations complement the reproducible automated coverage in
the [Privacy Boundary Verification Matrix](../../docs/evaluation.md#privacy-boundary-verification-matrix).

## Content Inspection Boundary Demo

Select **Inspection**, then choose **Blocked cases** or **Allowed cases**. Each run
executes two independent requests through the guardrails factory. The model is a
local scripted `ChatModel`, and inspection uses literal rules for the fixed
English or Korean phrases. No external LLM or guard model is called.
Changing the selected case runs it automatically. Use **Run** to repeat the current selection.

- The input block asks to ignore previous instructions and print the system
  prompt. Inspection reports `PROMPT_INJECTION` and throws
  `InspectionBlockedException` before the model is called. The model call count is `0`.
- The output block starts with an allowed request. The model is called once and
  produces an insult directed at the user. Output inspection reports
  `TOXICITY` / `INSULT`, and the exception prevents that response from being delivered.
- Allowed cases call the model once each. The output case also passes output
  inspection and delivers a privacy-protected response.

```bash
curl 'http://127.0.0.1:8080/demo/inspection?example=block' -H 'Accept-Language: en'
curl 'http://127.0.0.1:8080/demo/inspection?example=allow' -H 'Accept-Language: ko'
```

The endpoint accepts `example=block` or `example=allow` and defaults to `block`.
Any other value returns HTTP `400`.
It accepts no custom text. The synthetic raw input and model response are displayed
alongside the text actually received by each inspector. Email addresses are
tokenized before inspection. Reports are recorded by `InspectionObserver`, and
the model counts its own calls. A blocked case has no `finalResponse`.

These exact-match rules demonstrate enforcement, rather than a general prompt
attack or toxicity classifier. The diagram shortens token details for readability.

<p align="center">
  <img src="../../docs/images/privacy-boundary-inspector-inspection.png" alt="Input inspection blocking a prompt injection and output inspection withholding an insulting response" width="960">
</p>

## ChatClient With The Guardrails Factory

```bash
curl "http://127.0.0.1:8080/demo/chat-client"
```

This endpoint uses a Spring AI `ChatClient` with the starter's privacy
configuration. `finalResponse` shows the opaque tokens that reached the model.

Custom synthetic text can be sent with `POST /demo/chat-client` and a JSON body
such as `{"text":"My employee id is EMP-1234"}`. The `GET /demo/chat-client`
endpoint runs its fixed example. POST requests require nonblank `text` and
return HTTP `400` when it is missing or blank.

## Local RAG Boundary Demo

```bash
curl "http://127.0.0.1:8080/demo/rag" \
  -H 'Accept-Language: en'
```

This endpoint retrieves a fixed synthetic document from an in-memory
`SimpleVectorStore`. The response includes the raw retrieved document and the
complete protected input received by the local model, including the question,
prompt template, and retrieved document. Detected PII is replaced with opaque
tokens before model execution. No external
vector store, embedding service, or model is used.

## Local Rule Protection

```bash
curl "http://127.0.0.1:8080/demo/protect" \
  -H 'Accept-Language: en'
```

`GET /demo/protect` selects its fixed English or Korean demo data from
`Accept-Language`. This command selects the English example used below.

Expected shape:

```json
{
  "protectedPrompt": "Employee ID is [PII_EMPLOYEE_ID_1:<opaque>], email is [PII_EMAIL_ADDRESS_1:<opaque>], phone is [PII_PHONE_NUMBER_1:<opaque>], and customer ID is [PII_CUSTOMER_ID_1:<opaque>].",
  "detectedSpans": [
    {"type":"EMPLOYEE_ID","start":15,"end":23,"providers":["RULES"],"reason":"SINGLE_EVIDENCE"},
    {"type":"EMAIL_ADDRESS","start":34,"end":50,"providers":["RULES"],"reason":"SINGLE_EVIDENCE"},
    {"type":"PHONE_NUMBER","start":61,"end":74,"providers":["RULES"],"reason":"SINGLE_EVIDENCE"},
    {"type":"CUSTOMER_ID","start":95,"end":106,"providers":["RULES"],"reason":"SINGLE_EVIDENCE"}
  ],
  "successfulProviders": ["RULES"]
}
```

If your application code handles opaque tokens directly, preserve the complete token string.

`<opaque>` is generated for each privacy session. Calling the endpoint twice with the same
input produces different opaque tokens.
Each span's `providers` list identifies analyzers whose findings contributed to that result.
`successfulProviders` lists analyzers that completed successfully, including
those that found no PII.

The default configuration uses COMMON/KR presets and application-owned Java rules for:

- Email addresses.
- Card number candidates that pass Luhn validation.
- Korean mobile phone numbers.
- Synthetic Korean resident registration number format.
- Employee IDs such as `EMP-1234`.
- Customer IDs such as `CUST-123456`.

The same default profile can protect structured identifiers inside synthetic
English text without adding an external analyzer:

```bash
curl -X POST http://127.0.0.1:8080/demo/protect \
  -H 'Content-Type: application/json' \
  -d '{"text":"Email alice@example.com, phone 010-2345-6789, customer ID CUST-654321, employee ID EMP-1234."}'
```

The response should contain `EMAIL_ADDRESS`, `PHONE_NUMBER`, `CUSTOMER_ID`,
and `EMPLOYEE_ID` opaque tokens. Person names are semantic entities rather than stable
identifier formats, so the local rule profile does not guess them. Use a
configured NER provider such as Presidio or the optional OpenNLP profile below
when `PERSON` detection is required.

The employee and customer ID rules belong to this sample. The other rules come
from the built-in catalog. Check their documented format limits against your own
data, and add `PiiRule` beans or independent `PiiAnalyzer` providers as needed. The checked-in
[evaluation baseline](../../docs/evaluation.md) covers positive, negative, and
adversarial synthetic cases using this configuration.

## ChatClient Tool Loop Demo

```bash
curl "http://127.0.0.1:8080/demo/tool-loop" \
  -H 'Accept-Language: en'
```

The response shows that detected values in the demo data reach the model
only as opaque tokens. Within the current request, the CRM delegate receives
the original `customerId` while `employeeId`, `email`, and `phone` remain
opaque tokens. Detected values in the tool result are protected before the next
model call.

The sample defines a `GuardrailsPolicy` bean in `GuardrailsConfiguration`.
It selects privacy, output protection and tool authorization, and uses
`ToolDisclosurePolicy` to allow original customer IDs for `customerLookup`.
Raw tools are registered with `GuardrailsChatClientFactory`, which applies the
policy for each request.

The `/demo/tool-loop` endpoint calls a local CRM tool included in the sample,
so no external CRM service is required.

## Spring Security Tool Boundary Demo

```bash
curl "http://127.0.0.1:8080/demo/security-tool-boundary" \
  -H 'Accept-Language: en'
```

This endpoint runs the same customer lookup request as two sample users.
The local model deliberately requests the tool even when it is hidden, to verify
execution blocking independently of tool-list filtering.

For a general employee (`ROLE_EMPLOYEE`), `customerLookup` is absent from the
model-visible definitions and the forced request is denied before the callback
runs. For a customer-support employee (`ROLE_CUSTOMER_SUPPORT`), the tool is
exposed and executed once. The disclosure policy restores only
`CUSTOMER_ID`, and the result is protected again before model re-entry. The
sample installs a fixed in-process authorization policy and requires no login,
authentication token issuer, external model, or external service.

<p align="center">
  <img src="../../docs/images/privacy-boundary-inspector-security.png" alt="Security Inspector comparing a blocked general employee with an authorized customer-support employee" width="960">
</p>

## Streamable HTTP MCP Tool Loop Demo

```bash
curl "http://127.0.0.1:8080/demo/mcp-tool-loop" \
  -H 'Accept-Language: en'
```

This endpoint runs the same deterministic flow through an actual local
Streamable HTTP MCP round trip. Its evidence shows detected raw values in the
demo data absent at the model, only `CUSTOMER_ID` restored at the MCP tool, and
detected values in the MCP result protected before returning to the model.
It requires no external MCP infrastructure or model.

## Reading the sample code

Start with [GuardrailsConfiguration](src/main/java/io/github/ultramancode/springai/privacy/sample/config/GuardrailsConfiguration.java) for the privacy policy, tool disclosure policy, authorization manager, and guarded `ChatClient`.

[DemoPiiRuleConfiguration](src/main/java/io/github/ultramancode/springai/privacy/sample/config/DemoPiiRuleConfiguration.java) defines the sample's employee and customer ID rules.
[application.yml](src/main/resources/application.yml) selects these rules alongside the built-in presets.

[LocalChatModelConfiguration](src/main/java/io/github/ultramancode/springai/privacy/sample/config/LocalChatModelConfiguration.java) supplies the local model.
[DemoToolAuthorization](src/main/java/io/github/ultramancode/springai/privacy/sample/shared/security/authorization/DemoToolAuthorization.java) defines tool access for the demo roles.

Follow the service for the flow you want to inspect:

- [PromptProtectionDemoService](src/main/java/io/github/ultramancode/springai/privacy/sample/privacy/service/PromptProtectionDemoService.java): analyze and tokenize input through `PrivacyService`.
- [ChatClientDemoService](src/main/java/io/github/ultramancode/springai/privacy/sample/privacy/service/ChatClientDemoService.java): call the guarded `ChatClient`.
- [LocalToolLoopDemoService](src/main/java/io/github/ultramancode/springai/privacy/sample/localtool/service/LocalToolLoopDemoService.java): local tool calls with privacy protection and authorization.
- [SecurityDemoService](src/main/java/io/github/ultramancode/springai/privacy/sample/security/service/SecurityDemoService.java): compare tool access for two roles using the same tool execution.
- [RagDemoService](src/main/java/io/github/ultramancode/springai/privacy/sample/rag/service/RagDemoService.java): retrieval followed by privacy protection.
- [McpToolLoopDemoService](src/main/java/io/github/ultramancode/springai/privacy/sample/mcp/service/McpToolLoopDemoService.java): run the guarded MCP tool loop and collect privacy boundary evidence.
- [InspectionDemoService](src/main/java/io/github/ultramancode/springai/privacy/sample/inspection/service/InspectionDemoService.java): input/output inspectors, policy, and the guarded call.

[McpToolConfiguration](src/main/java/io/github/ultramancode/springai/privacy/sample/mcp/config/McpToolConfiguration.java) registers the CRM tool with the Spring AI MCP server starter.
The `/mcp` endpoint starts with the application on the same web server as the demo UI.
[McpCustomerLookupTool](src/main/java/io/github/ultramancode/springai/privacy/sample/mcp/infrastructure/McpCustomerLookupTool.java) looks up customer records and records the received arguments and lookup result.
[McpDemoClient](src/main/java/io/github/ultramancode/springai/privacy/sample/mcp/infrastructure/McpDemoClient.java) connects on the first MCP demo request, reuses the connection, and closes it when the application shuts down.

Code is grouped by feature under `privacy`, `inspection`, `rag`, `localtool`, `mcp`, and `security`.
Each feature separates HTTP handling in `controller`, execution in `service`, and
API records in `dto.request` or `dto.response`. `infrastructure` contains model
and tool implementations. `model` contains internal data types, including demo
inputs, messages, execution results, and state.

`shared.tool` contains the model, argument types, and evidence used by multiple tool demos.
`shared.security` contains the demo roles, authorization rules, and authorization checks shared by those demos.

The browser markup, styles, translations, and request/render logic live in `index.html`, `demo.css`, `demo-translations.js`, and `demo.js` under `src/main/resources/static`.
The Inspection diagram has its own renderer and styles in `inspection-demo.js` and `inspection-demo.css`.

## Tests

Run the sample's Java checks and JavaScript tests from the repository root:

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:check
node --test samples/spring-ai-demo/src/test/javascript/*.test.cjs
```

The JavaScript tests run the rendering and request logic in Node.js with a lightweight DOM substitute.
The Java checks run the local scenarios and verify that the optional live-model
configuration starts and loads its custom PII rules, without contacting a model.
Live model and analyzer checks require separate opt-in configuration described below.

## Opt-In OpenAI-Compatible Live Model Verification

The opt-in live harness connects Spring AI's `OpenAiChatModel` to an
application-supplied OpenAI-compatible endpoint. The `.env.example` configures
Gemini through its OpenAI-compatible endpoint. Other endpoints must support chat completions,
streaming, and tool calling.

Normal builds require no cloud credentials and never contact the endpoint. To
run it deliberately, copy the template and put a temporary test key only in the
ignored local file.

```bash
cp samples/spring-ai-demo/.env.example samples/spring-ai-demo/.env
chmod 600 samples/spring-ai-demo/.env
# Edit samples/spring-ai-demo/.env and fill in OPENAI_COMPATIBLE_API_KEY.
./gradlew :spring-ai-privacy-guardrails-sample-demo:openAiCompatibleLiveTest
```

The task requires `OPENAI_COMPATIBLE_API_KEY`,
`OPENAI_COMPATIBLE_BASE_URL`, and `OPENAI_COMPATIBLE_MODEL`. Process
environment variables take precedence over values in the ignored local file.

The harness covers blocking, streaming, tool-loop, and `returnDirect` paths. It
checks model-bound protection, scoped CRM disclosure, PII tokenization of tool
results, application output policy, and session cleanup.
Successful summaries omit
payloads and credentials. Provider and JUnit failures retain original SDK
diagnostics, so failed-run logs may contain endpoint responses.

## Optional Presidio Adapter

Start the local Presidio analyzer:

```bash
docker compose -f samples/presidio/docker-compose.yml up -d --wait
```

Then run the demo with the `presidio` profile:

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:run --args='--spring.profiles.active=presidio'
```

Once this optional profile is enabled, the default `REQUIRE_ALL` policy makes
Presidio availability mandatory for every analysis request. An outage fails
closed even if RULES succeeds. The profile uses language `en`, spaCy
`en_core_web_lg`, and a provider-specific minimum score of `0.8`. Production
deployments should calibrate that threshold with representative synthetic data.

Korean local rules remain active, but this sample does not provide Korean
Presidio NER support. A production Korean deployment must install and configure
a compatible Korean NLP and recognizer pipeline.

## Optional JVM-Only OpenNLP Adapter

The `opennlp` profile detects PII in the same JVM using model files you supply:
a tokenizer model to split text into tokens and a name-finder model to detect
people's names. Model files are not included in the repository. To check basic
functionality, download the
English tokenizer and person models from the
[Apache OpenNLP legacy model catalog](https://opennlp.sourceforge.net/models-1.5/)
into the ignored `build` directory:

```bash
mkdir -p build/manual-opennlp-models
curl -fL https://opennlp.sourceforge.net/models-1.5/en-token.bin \
  -o build/manual-opennlp-models/en-token.bin
curl -fL https://opennlp.sourceforge.net/models-1.5/en-ner-person.bin \
  -o build/manual-opennlp-models/en-ner-person.bin
printf '%s  %s\n' \
  2d0dd64ffb3d084382d7bdb65e7bd004c5001ba5503c36413d97c3e46321437c \
  build/manual-opennlp-models/en-token.bin \
  687a9263d96b37fced707c9f2ac0560f9edaf54658856395555901924f64dbe4 \
  build/manual-opennlp-models/en-ner-person.bin \
  | sha256sum -c -
```

Then pass their absolute file URIs and activate the profile:

```bash
export OPENNLP_TOKENIZER_MODEL="file:$PWD/build/manual-opennlp-models/en-token.bin"
export OPENNLP_PERSON_MODEL="file:$PWD/build/manual-opennlp-models/en-ner-person.bin"
./gradlew :spring-ai-privacy-guardrails-sample-demo:run \
  --args='--spring.profiles.active=opennlp'
```

In another terminal, send synthetic English text:

```bash
curl -X POST http://127.0.0.1:8080/demo/protect \
  -H 'Content-Type: application/json' \
  -d '{"text":"John Smith joined the board."}'
```

The response should replace `John Smith` with a `PERSON` opaque token. Its detected
span should cover offsets `0..10` and report `providers: ["OPENNLP"]`.
`successfulProviders` contains both `OPENNLP` and `RULES`: the OpenNLP model
found the span, while the still-enabled RULES analyzer also completed
successfully without contributing evidence to that span.

The profile's `0.8` OpenNLP threshold and these Apache-hosted legacy English
models are only a reproducible smoke-test baseline. They are not a
general-purpose PII recommendation. Before production use, the application
must review model provenance and licensing, use a tokenizer compatible with
the NER model, and calibrate accuracy on representative synthetic data.

To run the opt-in integration test against those same files:

```bash
OPENNLP_LIVE_TEST=true \
OPENNLP_TOKENIZER_MODEL="$OPENNLP_TOKENIZER_MODEL" \
OPENNLP_PERSON_MODEL="$OPENNLP_PERSON_MODEL" \
./gradlew :spring-ai-privacy-guardrails-sample-demo:test --rerun-tasks
```

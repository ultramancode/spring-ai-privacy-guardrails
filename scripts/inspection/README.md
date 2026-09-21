# Inspection integration verification

Contributor instructions for the inspection test fixtures and optional live tests.
Run the commands below from the repository root. See the
[content inspection guide](../../docs/inspection.md) for application configuration.

## Regular tests

Regular tests use synthetic data, a local HTTP fault server, and a tiny ONNX graph
executed through the real Java tokenizer/native runtime. The graph is reproducible
with `scripts/inspection/generate_onnx_fixture.py` for deterministic execution tests.

```powershell
.\gradlew.bat check verifyDocTranslations verifyPublishedModuleBoundaries
```

To regenerate the fixture's Base64 representation, install `onnx==1.20.1` in an
isolated Python environment and run `python scripts/inspection/generate_onnx_fixture.py`.

## Prompt Guard ONNX live tests

On 2026-09-17, the optional `inspection-onnx:liveTest` passed all three tests
using the publicly distributed
[Gravitee Prompt Guard 2 22M ONNX export](https://huggingface.co/gravitee-io/Llama-Prompt-Guard-2-22M-onnx/tree/da68d0f6023c7aeaf6b256eec549de295d5e8740),
revision `da68d0f6023c7aeaf6b256eec549de295d5e8740`.
The test uses Gravitee's quantized export and its matching tokenizer. Model artifacts and their
license/notice remain local under ignored `build/inspection-live/`; they are not bundled.

- `model.quant.onnx`: 72,542,430 bytes, SHA-256
  `38c3f03e30a4d5d229aeb7bf638e778322f8179d0ed0d4953eb22f88d8e0cf6b`.
- `tokenizer.json`: SHA-256
  `92c8b45d0b12ae0dd9680fbfe9804503542c377d65838558cd0b48a795385dde`.
- Java: ONNX Runtime 1.30.0, DJL 0.38.0, workspace-local Temurin 17.0.20.1.
- Python reference: ONNX Runtime 1.30.0, Transformers 4.57.6, tokenizers 0.22.2.

Eight synthetic inputs produced ten 512-token padded windows. Java token IDs,
attention masks, and malicious-class scores matched the Python execution of the
**same ONNX export**; measured score differences were zero on this machine
(test tolerance: `1e-4`). The tests also verify default policy application,
overlapping-window coverage beyond 512 tokens, and `LIMIT_EXCEEDED` handling
when the window budget is insufficient. Java/Python runtime versions must match
for the execution comparison.

To reproduce, provision the pinned model, tokenizer, `config.json`,
`tokenizer_config.json`, and `special_tokens_map.json` with their license/notice.
Use Python 3.11+ in an isolated virtual environment; the reference script is offline
and does not execute remote model code. For example, after provisioning:

```powershell
python -m pip install onnxruntime==1.30.0 transformers==4.57.6 tokenizers==0.22.2
$artifacts = (Resolve-Path 'build/inspection-live/prompt-guard-2-22m').Path
$env:INSPECTION_ONNX_MODEL = Join-Path $artifacts 'model.quant.onnx'
$env:INSPECTION_ONNX_TOKENIZER = Join-Path $artifacts 'tokenizer.json'
$env:INSPECTION_ONNX_REFERENCE = Join-Path $artifacts 'reference.properties'
python scripts/inspection/prompt_guard_reference.py --model $env:INSPECTION_ONNX_MODEL --tokenizer-dir $artifacts --output $env:INSPECTION_ONNX_REFERENCE
.\gradlew.bat :spring-ai-privacy-guardrails-inspection-onnx:liveTest
```

The live test checks model/tokenizer hashes against the reference, tokenization,
per-window scores, default policy decisions, and long-input coverage. It is excluded
from regular `test`; absent environment variables produce a skip, not verification.

## OpenAI-compatible live test

The optional live test uses synthetic prompts only:

```powershell
$env:INSPECTION_OPENAI_ENDPOINT = 'http://127.0.0.1:18084/v1/chat/completions'
$env:INSPECTION_OPENAI_MODEL = 'local-guard'
# Set INSPECTION_OPENAI_API_KEY locally if required.
# Set INSPECTION_OPENAI_PROTOCOL=kanana only for the matching model/template.
.\gradlew.bat :spring-ai-privacy-guardrails-inspection-openai-compatible:liveTest
```

Without the endpoint, the live test is skipped; a skip is not live verification.
The development smoke test used llama.cpp b11012 CPU + Qwen2.5-0.5B-Instruct
Q4_K_M, via `jsonGuard`. The model file SHA-256 was
`74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`.
Both benign and attack-shaped synthetic inputs completed the strict protocol.
This live test verifies HTTP execution and strict `jsonGuard` response handling
for the recorded model/runtime combination.

## Windows native runtime

Use a current JDK and the [ONNX Runtime prerequisites](https://onnxruntime.ai/docs/install/).
The recorded native tests used Temurin 17.0.20.1. Corretto 17.0.16's bundled C++ DLL
caused an ONNX initialization crash in the same environment; updating the test JDK
resolved it. To select a workspace-local JDK without changing the global installation:

```powershell
.\gradlew.bat :spring-ai-privacy-guardrails-inspection-onnx:liveTest `
    "-Porg.gradle.java.installations.paths=$PWD/build/inspection-live/temurin17/jdk-17.0.20.1+1" `
    '-Porg.gradle.java.installations.auto-detect=false'
```

## Multiple ONNX model contracts

The ONNX implementation separates `OnnxContentInspector` / its internal runtime from
`OnnxInspectionModel`. Supported adapters are `PromptGuard2Model` and
`ProtectAiDebertaV2Model`; the other profiles below are architecture fixtures only.

On 2026-09-19 (macOS arm64, Corretto 17.0.8.1, ONNX Runtime 1.30.0, DJL 0.38.0),
all five exports passed Java/Python token-ID, attention-mask, window-count and score
comparison. The four-model matrix uses eight synthetic cases per model. Prompt Guard
retains its eight-case reference and policy/long-tail tests. Score tolerance is `1e-4`;
this is an execution comparison, not a model-quality or Korean-accuracy benchmark.

| Export | Test window | Output contract | Role |
| --- | --- | --- | --- |
| Prompt Guard 2 22M | 512 | 2 logits, softmax | Supplied adapter |
| ProtectAI DeBERTa v2 | 512 | 2 logits, softmax | Supplied adapter |
| DistilBERT prompt injection | 512 | 2 logits, softmax | Tokenizer/export fixture |
| ModernBERT prompt injection | 2,048 | 2 logits, softmax | Long-input fixture |
| Agent Guard ModernBERT | 1,024 | 17 logits, independent sigmoid | Multi-label fixture |

The selected DistilBERT export uses the same two INT64 input names as Prompt Guard.
The deterministic CI graph separately verifies INT32 inputs and `token_type_ids`.
Other deterministic tests cover 8,192-token windows, nonzero padding IDs, shape mismatch,
partial evidence, interruption and resource closure. Real ModernBERT runs exercise
2,048 tokens; they do not establish quality or execution at its advertised maximum.

The pinned revisions and SHA-256 checksums for models, tokenizers and configs are in
[onnx-model-matrix.json](onnx-model-matrix.json). The preparation script verifies all
artifacts before loading them, uses no remote model code and only downloads when
explicitly passed `--download` (about 2.2 GB). Run in the isolated Python environment
with `onnxruntime==1.30.0`, `transformers==4.57.6`, and `tokenizers==0.22.2`:

```sh
python scripts/inspection/prepare_onnx_matrix.py \
  --directory build/inspection-live/models --download
INSPECTION_ONNX_MODELS="$PWD/build/inspection-live/models" \
  ./gradlew :spring-ai-privacy-guardrails-inspection-onnx:liveTest --tests '*OnnxModelLiveTest'
```

For provisioned/offline artifacts, omit `--download`. ProtectAI uses its `onnx/`
tokenizer/config files. ModernBERT fixtures load `tokenizer_config.json` explicitly:
its pad token ID is 50283, so treating tokenizer.json alone as sufficient produces
incorrect padded input. The runtime does not choose or infer model-specific padding.
Agent Guard fixture labels are numeric; its test checks all sigmoid scores without
publishing unverified secondary-label category mappings.

Regular `check`/CI uses tiny deterministic graphs without downloading weights. The
optional `liveTest` runs the pinned exports when their environment variables are set.

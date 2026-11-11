# OpenAI-compatible inspection with Kanana

These examples verify OpenAI-compatible content inspection with two Kanana models:

| Inspection | Model | Server | Protocol |
| --- | --- | --- | --- |
| Input | Safeguard-Prompt 2.1B | vLLM CPU | `KananaSafeguardPromptProtocol` |
| Output | Safeguard 8B, Q4_K_M GGUF | llama.cpp | `KananaSafeguardOutputProtocol` |

Run the commands from the repository root. Both live tests are excluded from ordinary
`test` runs.

## Input inspection with vLLM

The input example runs Kanana Safeguard-Prompt on a pinned vLLM CPU server.
The Java live test checks a safe question about South Korea, a prompt-injection
attempt and a prompt-leaking attempt through `OpenAiCompatibleContentInspector`.

Allocate at least 8 GB of memory to Docker.
The first run downloads roughly 4.2 GB of model files and the server image.
Model files are cached under `~/.cache/huggingface`. Set `KANANA_MODEL_CACHE`
to use another directory.

### Start and test

```bash
docker compose -f samples/openai-compatible-inspection/docker-compose.yml up -d --wait --wait-timeout 1200 && \
INSPECTION_OPENAI_BASE_URL=http://localhost:8000/v1 ./gradlew \
  :spring-ai-privacy-guardrails-inspection-openai-compatible:liveTest \
  --tests '*OpenAiCompatibleLiveTest'
```

The Compose file pins both the server image and the model revision.
The test uses `KananaSafeguardPromptProtocol` with its default generation settings.

### Stop

```bash
docker compose -f samples/openai-compatible-inspection/docker-compose.yml down
```

### GitHub Actions

Run the **Inspection model smoke test** workflow manually to execute the same
test on a standard Ubuntu CPU runner. The workflow caches the pinned model and
prints server resource usage. It does not run automatically on pull requests or
main-branch pushes.

## Output inspection with llama.cpp

Tests output inspection with [DevQuasar's Q4_K_M GGUF conversion](https://huggingface.co/DevQuasar/kakaocorp.kanana-safeguard-8b-GGUF/tree/6d8461cdc07c0afe0ff91a336cbbe499e6ae0539)
of Kanana Safeguard 8B.

Use Docker with at least 8 GB of memory and 4 CPUs.
The first start downloads about 4.92 GB of model files, in addition to the server image.

Model files are cached under `build/kanana-output-model`. Set `KANANA_OUTPUT_MODEL_DIR`
to an absolute directory path to use another location.

### Start and test

```bash
docker compose -f samples/openai-compatible-inspection/docker-compose.output.yml up -d --wait --wait-timeout 1200 && \
INSPECTION_KANANA_OUTPUT_BASE_URL=http://127.0.0.1:8002/v1 ./gradlew \
  :spring-ai-privacy-guardrails-inspection-openai-compatible:liveTest \
  --tests '*KananaSafeguardOutputLiveTest'
```

The server uses [`--no-prefill-assistant`](https://github.com/ggml-org/llama.cpp/blob/99b95488cac0f00ce3f05af113a8c1e287753f87/tools/server/README.md)
so llama.cpp classifies the completed answer instead of continuing it.

### Stop

```bash
docker compose -f samples/openai-compatible-inspection/docker-compose.output.yml down
```

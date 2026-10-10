# OpenAI-compatible inspection with Kanana

This sample verifies OpenAI-compatible content inspection with Kanana
Safeguard-Prompt running on a pinned vLLM CPU server.
The Java live test checks a safe question about South Korea, a prompt-injection
attempt and a prompt-leaking attempt through `OpenAiCompatibleContentInspector`.

Run these commands from the repository root with Docker available and at least
8 GB of memory allocated to Docker.
The first run downloads roughly 4.2 GB of model files and the server image.
Model files are cached under `~/.cache/huggingface`. Set `KANANA_MODEL_CACHE`
to use another directory.

## Start and test

```bash
docker compose -f samples/openai-compatible-inspection/docker-compose.yml up -d --wait --wait-timeout 1200

INSPECTION_OPENAI_BASE_URL=http://localhost:8000/v1 ./gradlew \
  :spring-ai-privacy-guardrails-inspection-openai-compatible:liveTest
```

The Compose file pins both the server image and the model revision.
The test uses the built-in Kanana protocol with its default generation settings.
It is excluded from ordinary `test` runs.

## Stop

```bash
docker compose -f samples/openai-compatible-inspection/docker-compose.yml down
```

## GitHub Actions

Run the **Inspection model smoke test** workflow manually to execute the same
test on a standard Ubuntu CPU runner. The workflow caches the pinned model and
prints server resource usage. It does not run automatically on pull requests or
main-branch pushes.

# Presidio Sample

This sample starts a local Presidio analyzer endpoint for integration testing.
The Compose file pins Presidio Analyzer `2.2.363` and its multi-platform image
digest for reproducible smoke tests. Run the commands below from the repository
root in a Linux shell.

The pinned image uses spaCy `en_core_web_lg`. Presidio combines its NLP pipeline
with pattern-, context-, and checksum-based recognizers; this adapter reports
their combined output as provider `PRESIDIO`.

## Start

```bash
docker compose -f samples/presidio/docker-compose.yml up -d --wait
```

Check readiness:

```bash
curl http://localhost:5002/health
```

Expected response:

```text
Presidio Analyzer service is up
```

## Configure The Application

The Presidio starter includes the core privacy starter transitively:

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.1.1"
}
```

Then point the optional Presidio starter to the local analyzer endpoint:

```yaml
spring:
  ai:
    privacy:
      enabled: true
      analysis:
        language: en
      presidio:
        enabled: true
        analyzer-url: http://localhost:5002
```

`analyzer-url` accepts a base HTTP(S) URI without user-info, query, or fragment
components. Put credentials in `presidio.headers` rather than in the URL.

## Manual API Check

```bash
curl -X POST http://localhost:5002/analyze \
  -H "Content-Type: application/json" \
  -d '{"text":"My name is John Smith and my email is john.smith@example.com.","language":"en"}'
```

Presidio should return entities such as `PERSON` and `EMAIL_ADDRESS`. Results
depend on the image, model, language, and recognizers; calibrate production
configurations against representative synthetic data.

Run the adapter and sample-profile live integration tests:

```bash
PRESIDIO_LIVE_TEST=true ./gradlew \
  :spring-ai-privacy-guardrails-presidio:test \
  :spring-ai-privacy-guardrails-sample-demo:test \
  --rerun-tasks
```

## Stop

```bash
docker compose -f samples/presidio/docker-compose.yml down
```

# Spring AI Privacy Guardrails 데모

[English](README.md) | [한국어](README.ko.md)

<!-- i18n-source: samples/spring-ai-demo/README.md -->
<!-- i18n-source-sha256: ea5d1b44b608933696c67f4cbd3354259ec0d2955ba879c94d8c052c7932e0cd -->

이 실행 가능한 샘플은 외부 LLM API 키 없이 항상 같은 결과를 반환하는 로컬
`ChatModel`로 개인정보 보호 advisor 경로를 검증합니다. 샘플에서 보호할
`ChatClient` builder에 스타터의 `PrivacyChatClientConfigurer`를 명시적으로
적용합니다.

```text
사용자 원문 입력 -> 모델 프롬프트 토큰화 -> 기능 범위가 제한된 도구 공개
-> 도구 결과 재토큰화
```

데모 응답은 토큰 매핑을 직렬화하지 않지만, 탐지되지 않은 텍스트는 변경 없이 반환될
수 있습니다. 사용자 정의 텍스트는 URL, 브라우저 기록 또는 일반적인 액세스 로그의
요청 줄에 복사되지 않도록 POST 본문으로만 받습니다.

이 문서의 명령은 Linux 셸을 기준으로 하며 저장소 루트에서 실행합니다.

## 실행

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:run
```

데모는 `http://127.0.0.1:8080`에만 바인딩됩니다.

### Privacy Boundary Inspector

브라우저에서 이 주소를 열면 샘플 전용 **Privacy Boundary Inspector**를 사용할 수
있습니다. `Local Tool | RAG | MCP` 선택기로 한 페이지에서 기존 런타임 데모를
실행할 수 있습니다. `EN | 한국어` 토글은 선택한 로케일을 데모 엔드포인트에 전달하고
선택한 고정 시나리오를 다시 실행하므로 UI 문구, 예제 입력, 런타임 결과가
일치합니다. 각 화면은 데모 엔드포인트가 반환한 근거만 렌더링하며, Inspector는 토큰
매핑, recognizer 내부 구현 또는 모델 설정을 노출하지 않습니다.

<p align="center">
  <img src="../../docs/images/privacy-boundary-inspector-demo-ko.gif" alt="보호된 모델 및 도구 경계를 보여주는 Privacy Boundary Inspector" width="960">
</p>

### Inspector 런타임 엔드포인트

| Inspector 화면 | 백엔드 요청 | 반환되는 런타임 근거 |
| --- | --- | --- |
| `Local Tool` | `GET /demo/scenario`, `GET /demo/protect`, `GET /demo/tool-loop` | 언어별 고정 입력과 `detectedSpans`, 불투명 토큰 상태의 모델 입력 및 도구 인자, 범위가 제한된 `CUSTOMER_ID` 복원, 다시 보호된 도구 결과 |
| `RAG` | `GET /demo/rag` | 원문 `retrievedDocument`, 실제 `modelVisibleContext`, 두 단계의 원문 및 토큰화 개인정보를 비교하는 백엔드 불리언 필드 |
| `MCP` | `GET /demo/scenario`, `GET /demo/mcp-tool-loop` | 실제 로컬 Streamable HTTP MCP 실행 모드, 보호된 모델 및 도구 호출 값, 범위가 제한된 공개, 결과 재보호 |

Inspector는 이 요청들에 `Accept-Language: en` 또는 `ko`를 보내고 선택한 흐름을 다시
실행합니다. 백엔드 로케일은 고정 입력, RAG 질의와 프롬프트 템플릿, CRM 결과 문구를
바꾸므로 브라우저 문구만 번역하는 동작이 아닙니다. 엔드포인트 응답에는 화면이 표시하는
근거 필드가 포함되지만 토큰 매핑은 포함되지 않습니다.

이 런타임 데모에 대응하는 재현 가능한 자동 검증 범위는
[개인정보 보호 경계 검증 매트릭스](../../docs/ko/evaluation.md#개인정보-보호-경계-검증-매트릭스)를
참고하세요.

## 명시적 Privacy Configurer를 사용하는 ChatClient

```bash
curl "http://127.0.0.1:8080/demo/chat-client"
```

이 엔드포인트는 스타터의 고정 개인정보 보호 advisor bundle이 적용된 Spring AI
`ChatClient`를 사용합니다. `modelResponse`에는 모델에 도달한 불투명 토큰이 표시됩니다.
`activeSessionsAfterCall`은 호출 후 관측된 서비스 전체 활성 세션 수입니다. 단독으로
실행하는 샘플에서는 일반적으로 `0`이지만, 동시 호출 중에는 현재 요청의 세션 누수를
의미하지 않으면서 0보다 클 수 있습니다.

`POST /demo/chat-client`에 `{"text":"My employee id is EMP-1234"}` 같은 JSON
본문을 보내 예제 텍스트를 지정할 수 있습니다. `GET /demo/chat-client`는 저장소에
포함된 고정 예제를 실행합니다. POST 요청의 `text`가 없거나 공백이면 예제를 대신
사용하지 않고 HTTP `400`으로 거부합니다.

## 로컬 RAG 경계 데모

```bash
curl "http://127.0.0.1:8080/demo/rag" \
  -H 'Accept-Language: ko'
```

이 엔드포인트는 메모리 내 `SimpleVectorStore`에서 예제 문서를 검색합니다.
응답에는 검색된 원문 문서와 deterministic 로컬 모델이 실제로 받은 토큰화 컨텍스트가
포함됩니다. 이를 통해 모델 실행 전에 검색 문서의 개인정보가 토큰화되었음을 확인할 수
있습니다. 외부 벡터 저장소, 임베딩 서비스 또는 모델은 사용하지 않습니다.

## 정규식 전용 보호

```bash
curl "http://127.0.0.1:8080/demo/protect" \
  -H 'Accept-Language: ko'
```

`GET /demo/protect`는 `Accept-Language`에 따라 영어 또는 한국어 고정 예제 데이터를
선택합니다. 위 명령은 아래에서 사용하는 한국어 예제를 고정합니다.

예상 응답 형태:

```json
{
  "protectedPrompt": "직원번호는 [[PII_EMPLOYEE_ID_<32-hex-session-nonce>_1]]이고, 이메일은 [[PII_EMAIL_ADDRESS_<32-hex-session-nonce>_1]], 전화번호는 [[PII_PHONE_NUMBER_<32-hex-session-nonce>_1]], 고객번호는 [[PII_CUSTOMER_ID_<32-hex-session-nonce>_1]]입니다.",
  "detectedSpans": [
    {"type":"EMPLOYEE_ID","start":6,"end":14,"providers":["REGEX"],"reason":"SINGLE_EVIDENCE"},
    {"type":"EMAIL_ADDRESS","start":23,"end":39,"providers":["REGEX"],"reason":"SINGLE_EVIDENCE"},
    {"type":"PHONE_NUMBER","start":47,"end":60,"providers":["REGEX"],"reason":"SINGLE_EVIDENCE"},
    {"type":"CUSTOMER_ID","start":68,"end":79,"providers":["REGEX"],"reason":"SINGLE_EVIDENCE"}
  ],
  "successfulProviders": ["REGEX"]
}
```

세션 nonce는 요청마다 새로 생성됩니다. 같은 입력으로 엔드포인트를 두 번 호출해도 서로
다른 불투명 토큰이 생성되어야 합니다. 각 span의 `providers` 목록에는 확정된 근거에서
가져온 표준 분석기 provider ID만 포함됩니다.

기본 설정은 다음 형식에 소규모 형식 기반 정규식 규칙을 사용합니다.

- 이메일 주소
- 한국 휴대전화 번호
- 예제용 한국 주민등록번호 형식
- `EMP-1234` 같은 직원 ID
- `CUST-123456` 같은 고객 ID

외부 분석기를 추가하지 않아도 동일한 기본 profile로 예제 영어 텍스트의 구조화 식별자를
보호할 수 있습니다.

```bash
curl -X POST http://127.0.0.1:8080/demo/protect \
  -H 'Content-Type: application/json' \
  -d '{"text":"Email alice@example.com, phone 010-2345-6789, customer ID CUST-654321, employee ID EMP-1234."}'
```

응답에는 `EMAIL_ADDRESS`, `PHONE_NUMBER`, `CUSTOMER_ID`, `EMPLOYEE_ID` 토큰이
포함되어야 합니다. 사람 이름은 안정적인 식별자 형식이 아니라 의미 기반 엔티티이므로
정규식 전용 profile은 이를 추측하지 않습니다. `PERSON` 탐지가 필요하면 Presidio 또는
아래의 선택적 OpenNLP profile처럼 구성된 NER provider를 사용하세요.

이 규칙은 샘플용입니다. 운영 시스템은 자체 데이터에 맞게 조정하거나 사용자 정의
`PiiAnalyzer` bean을 추가해야 합니다. 저장소에 포함된
[평가 기준](../../docs/ko/evaluation.md)은 이 설정으로 구성한 positive, negative,
adversarial 사례를 다룹니다.

## ChatClient 도구 루프 데모

```bash
curl "http://127.0.0.1:8080/demo/tool-loop" \
  -H 'Accept-Language: ko'
```

응답은 고정 예제 데이터에서 탐지된 값이 모델에 불투명 토큰으로만 전달되는 것을 보여줍니다.
현재 요청 안에서 CRM delegate에는 원문 `customerId`가 전달되지만 `employeeId`, `email`,
`phone`은 불투명 토큰 상태를 유지합니다. 도구 결과에서 탐지된 값은 다음 모델 호출 전에
다시 보호됩니다. `activeSessionsAfterCall`은 호출 후 관측한 서비스 전체 활성 세션
수입니다. 동시 요청이 있으면 현재 요청의 세션 누수를 의미하지 않으면서 0보다 클 수
있습니다.

공개 범위는 `spring.ai.privacy.tools.disclosures`에서 가져옵니다. 데모는 별도 정책을
만들지 않고 자동 구성된 `PrivacyToolCallbackFactory`와 명시적 privacy configurer를
사용합니다.

`/demo/tool-loop` 엔드포인트는 프로세스 내부 CRM delegate를 사용합니다.

## Streamable HTTP MCP 도구 루프 데모

```bash
curl "http://127.0.0.1:8080/demo/mcp-tool-loop" \
  -H 'Accept-Language: ko'
```

이 엔드포인트는 실제 로컬 Streamable HTTP MCP 왕복 호출을 통해 같은 고정 흐름을
실행합니다. 근거에는 고정 예제 데이터에서 탐지된 원문 값이 모델에서 관찰되지 않고,
MCP 도구에서는 `CUSTOMER_ID`만 복원되며, MCP 결과에서 탐지된 값이 모델 재진입 전에
보호되는 과정이 표시됩니다.
호출 후 관측된 서비스 전체 활성 세션 수도 함께 보고합니다. 외부 MCP 인프라나 모델은
필요하지 않습니다.

## 선택적 OpenAI 호환 실제 모델 검증

선택적 실제 모델 harness는 Spring AI의 `OpenAiChatModel`을 애플리케이션이 지정한
OpenAI 호환 엔드포인트에 연결합니다. 저장소의 `.env.example`은 Google 전용 모델
adapter 없이 Gemini 호환 엔드포인트를 사용하는 검증된 설정입니다. 다른 엔드포인트는
chat completions, streaming, tool calling을 지원해야 합니다.

일반 build는 클라우드 자격 증명이 필요하지 않으며 엔드포인트에 연결하지 않습니다.
의도적으로 실행하려면 template을 복사하고 임시 테스트 키를 gitignored 로컬 파일에만
입력하세요.

```bash
cp samples/spring-ai-demo/.env.example samples/spring-ai-demo/.env
chmod 600 samples/spring-ai-demo/.env
# samples/spring-ai-demo/.env를 편집하고 OPENAI_COMPATIBLE_API_KEY를 입력합니다.
./gradlew :spring-ai-privacy-guardrails-sample-demo:openAiCompatibleLiveTest
```

이 task에는 `OPENAI_COMPATIBLE_API_KEY`, `OPENAI_COMPATIBLE_BASE_URL`,
`OPENAI_COMPATIBLE_MODEL`이 필요합니다. 기존 프로세스 환경 변수는 gitignored 로컬
파일의 값보다 우선합니다.

harness는 blocking, streaming, tool-loop, `returnDirect` 경로를 다룹니다. 모델 경계
보호, 범위가 제한된 CRM 공개, 결과 재토큰화, 애플리케이션 출력 정책, 세션 정리를
검증합니다. 성공 요약에는 payload와 자격 증명이 포함되지 않습니다. Provider와 JUnit
실패는 원래 SDK 진단을 유지하므로 실패한 실행 로그에는 엔드포인트 응답이 포함될 수
있습니다.

## 선택적 Presidio Adapter

로컬 Presidio analyzer를 시작합니다.

```bash
docker compose -f samples/presidio/docker-compose.yml up -d --wait
```

그다음 `presidio` profile로 데모를 실행합니다.

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:run --args='--spring.profiles.active=presidio'
```

이 선택적 profile을 활성화하면 기본 `REQUIRE_ALL` 정책에 따라 모든 분석 요청에서
Presidio를 반드시 사용할 수 있어야 합니다. 정규식 분석이 성공해도 Presidio 장애 시
fail closed로 처리됩니다. 이 profile은 언어 `en`, spaCy `en_core_web_lg`, provider별
최소 점수 `0.8`을 사용합니다. 운영 배포 전에는 운영 환경을 대표하는 검증 데이터로
임계값을 조정해야 합니다.

한국어 정규식 규칙은 계속 활성화되지만 이 샘플은 한국어 Presidio NER를 제공하지
않습니다. 운영 한국어 배포에는 호환되는 한국어 NLP 및 recognizer pipeline을 설치하고
구성해야 합니다.

## 선택적 JVM 전용 OpenNLP Adapter

`opennlp` profile은 애플리케이션이 제공하는 tokenizer 및 name-finder 모델로 선택적
프로세스 내부 adapter를 실행합니다. 저장소에는 모델 binary를 포함하거나 재배포하지
않습니다. 로컬 smoke test를 위해
[Apache OpenNLP legacy model catalog](https://opennlp.sourceforge.net/models-1.5/)에서
영어 tokenizer와 person 모델을 gitignored `build` 디렉터리로 내려받습니다.

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

그다음 절대 파일 URI를 전달하고 profile을 활성화합니다.

```bash
export OPENNLP_TOKENIZER_MODEL="file:$PWD/build/manual-opennlp-models/en-token.bin"
export OPENNLP_PERSON_MODEL="file:$PWD/build/manual-opennlp-models/en-ner-person.bin"
./gradlew :spring-ai-privacy-guardrails-sample-demo:run \
  --args='--spring.profiles.active=opennlp'
```

다른 terminal에서 예제 영어 텍스트를 전송합니다.

```bash
curl -X POST http://127.0.0.1:8080/demo/protect \
  -H 'Content-Type: application/json' \
  -d '{"text":"John Smith joined the board."}'
```

응답은 `John Smith`를 `PERSON` 토큰으로 바꿔야 합니다. 탐지된 span은 offset `0..10`을
포함하고 `providers: ["OPENNLP"]`를 보고해야 합니다. `successfulProviders`에는
`OPENNLP`와 `REGEX`가 모두 포함됩니다. OpenNLP 모델은 span을 찾았고 계속 활성화된
정규식 분석기는 해당 span의 근거를 만들지 않았지만 성공적으로 완료되었기 때문입니다.

이 profile의 OpenNLP 임계값 `0.8`과 Apache가 호스팅하는 legacy 영어 모델은 재현 가능한
smoke-test 기준일 뿐, 범용 개인정보 탐지 권장 사항이 아닙니다. 운영 사용 전에
애플리케이션은 모델 출처와 라이선스를 검토하고, NER 모델과 호환되는 tokenizer를
사용하며, 운영 환경을 대표하는 검증 데이터로 정확도를 조정해야 합니다.

같은 파일로 선택적 integration test를 실행하려면 다음 명령을 사용합니다.

```bash
OPENNLP_LIVE_TEST=true \
OPENNLP_TOKENIZER_MODEL="$OPENNLP_TOKENIZER_MODEL" \
OPENNLP_PERSON_MODEL="$OPENNLP_PERSON_MODEL" \
./gradlew :spring-ai-privacy-guardrails-sample-demo:test --rerun-tasks
```

# 샘플 / 데모 가이드

[English](../sample.md) | **한국어**

<!-- i18n-source: docs/sample.md -->
<!-- i18n-source-sha256: ce61a618104ab587d077c930acc644492a53adc9b1921a7be22249e35c2c7806 -->

샘플은 로컬 도구, 문서 검색(RAG), MCP 도구 호출에서 개인정보가 보호되는 과정을
보여줍니다. **Privacy Boundary Inspector**에서 모델과 도구가 전달받은 값을 비교할 수
있습니다. 기본 샘플은 모델, 문서 검색, MCP 서버를 모두 로컬 환경에서 실행하므로
외부 서비스 계정이나 API 키가 필요하지 않습니다.

## Inspector 실행

JDK 17이 설치된 환경에서 저장소 루트의 다음 명령을 실행합니다.

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:run
```

브라우저에서 `http://127.0.0.1:8080`을 엽니다. 샘플은 로컬 접속만 허용합니다.
`Local Tool | RAG | MCP` 선택기로 시나리오를 실행하고, `EN | 한국어`로 언어를 선택해
시나리오를 다시 실행할 수 있습니다.

<div style="position: relative; width: 100%; aspect-ratio: 16 / 9;">
  <iframe
    src="https://www.youtube-nocookie.com/embed/vir-x78e9j8"
    title="Spring AI Privacy Guardrails 한국어 데모"
    style="position: absolute; inset: 0; width: 100%; height: 100%; border: 0;"
    loading="lazy"
    referrerpolicy="strict-origin-when-cross-origin"
    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
    allowfullscreen>
  </iframe>
</div>

## 시나리오별 확인 방법

### Local Tool

**Local Tool**을 선택하고 단계별로 전달되는 값을 확인하세요.

1. **모델 입력과 도구 인자:** 예제의 사번, 이메일, 전화번호, 고객번호가 모두 토큰으로
   바뀝니다. 모델이 생성한 도구 인자에도 이 토큰이 사용됩니다.
2. **도구 실행:** 고객 정보를 조회하는 CRM 도구에는 정책이 허용한 고객번호
   (`CUSTOMER_ID`)만 원문으로 전달됩니다. 나머지 세 값은 토큰 상태를 유지합니다.
3. **도구 결과:** 결과에서 탐지된 개인정보는 모델에 다시 전달되기 전에 토큰으로
   바뀝니다.

화면 상단의 ‘관측 / 전체’는 검사한 예제 값 중 원문이 확인된 개수를 나타냅니다.
예를 들어 ‘모델 원문 노출’의 `0/4`는 네 값 모두 원문이 모델에서 발견되지 않았다는
뜻이고, ‘허용된 원문 복원’의 `1/1`은 허용된 고객번호가 원문으로 전달되었다는 뜻입니다.
각 항목에는 `PASS` 또는 `FAIL`도 표시됩니다.

![보호된 모델 입력, 허용된 고객번호 원문 전달과 보호된 도구 결과를 보여주는 Local Tool Inspector](../images/privacy-boundary-inspector-local-tool-ko.png)

### RAG

**RAG**를 선택하고 검색된 문서와 모델이 전달받은 프롬프트를 비교하세요. 문서의
`alice@example.com`이 모델의 프롬프트에서는 `EMAIL_ADDRESS` 토큰으로 바뀝니다.

화면에는 검색된 문서 원문과 개인정보 보호가 적용된 전체 프롬프트가 나란히 표시됩니다.
전체 프롬프트에는 질문, 프롬프트 템플릿과 검색 내용이 포함됩니다. 저장된 문서 자체는
변경되지 않습니다.

로컬 임베딩 모델로 문서를 검색하며, 외부 벡터 저장소, 임베딩 서비스
또는 LLM은 사용하지 않습니다.

![검색된 문서와 모델에 노출된 보호 컨텍스트를 비교하는 RAG 개인정보 경계 Inspector](../images/privacy-boundary-inspector-rag-ko.png)

### MCP

**MCP**를 선택하면 Streamable HTTP로 `customerLookup` 도구를 호출합니다.
도구에는 고객번호(`CUSTOMER_ID`)만 원문으로 전달되고 나머지 개인정보는 토큰을 유지하는지,
결과에서 탐지된 개인정보는 모델에 다시 전달되기 전에 보호되는지 확인하세요.

샘플에 로컬 MCP 서버가 포함되어 있으므로 MCP 서비스를 별도로 배포할 필요가 없습니다.

![HTTP 도구 호출, 허용된 고객번호 원문 전달과 보호된 도구 결과를 보여주는 MCP Inspector](../images/privacy-boundary-inspector-mcp-ko.png)

## API로 결과 확인

Inspector 화면에서 확인한 결과를 JSON으로 조회하려면 다음 API를 사용하세요.

| 엔드포인트 | 반환 내용 |
| --- | --- |
| `GET /demo/scenario` | 선택한 언어의 예제 입력 |
| `GET /demo/protect` | 고정 예제에서 개인정보를 탐지한 위치와 토큰화 결과. 모델을 호출하지 않습니다. |
| `POST /demo/protect` | JSON 본문의 `text`에 입력한 텍스트의 탐지·토큰화 결과. `text`는 비어 있거나 공백으로만 이루어져서는 안 됩니다. |
| `GET /demo/tool-loop` | CRM 도구 호출 시 모델 입력, 도구 인자, 보호된 결과와 단계별 검증 결과(`boundaryEvidence`) |
| `GET /demo/rag` | 검색된 문서 원문(`retrievedDocument`)과 모델에 전달된 전체 보호 프롬프트(`modelVisibleContext`) |
| `GET /demo/mcp-tool-loop` | 로컬 Streamable HTTP MCP 도구 호출 시 모델 입력, 도구 인자와 결과 |

요청·응답 예제, 다른 분석기를 사용하는 설정과 실제 모델을 연결하는 검증 방법은
[전체 샘플 가이드](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/samples/spring-ai-demo/README.ko.md)를
참고하세요.

## EN/KO 런타임 로케일

Inspector에서 언어를 바꾸면 화면 문구뿐 아니라 예제 입력과 결과도 선택한 언어로
바뀝니다. API를 직접 호출할 때는 요청에 `Accept-Language: en` 또는
`Accept-Language: ko` 헤더를 지정하세요.

- Local Tool과 MCP는 언어별 고정 입력과 최종 결과 문구를 사용합니다.
- RAG는 언어별 질의, 검색 문서 접두어, 프롬프트 템플릿을 사용합니다.
- 엔드포인트 경로, 응답 필드 이름, 코드 식별자, 엔티티 유형은 바뀌지 않습니다.

`POST /demo/protect`는 항상 전달된 `text`를 분석하며 로케일이 사용자 입력을 바꾸지
않습니다.

## 해석과 검증

기본 Regex 규칙과 모든 예제 값은 샘플용입니다. 활성화된 분석기가 탐지하지 못한 텍스트는
변경되지 않을 수 있으며, 이 고정 시나리오는 일반적인 탐지 정확도나 지원되지 않는 실행
경로의 보호를 입증하지 않습니다.

재현 가능한 자동 검증 범위는
[개인정보 보호 경계 검증 매트릭스](evaluation.md#개인정보-보호-경계-검증-매트릭스)를
참고하세요.

---
description: >-
  규칙, 로컬 ONNX 분류기, HTTP 검사 모델로 모델 입력과 최종 출력을 검사합니다.
---

# 콘텐츠 검사

[English](../inspection.md) | **한국어**

<!-- i18n-source: docs/inspection.md -->
<!-- i18n-source-sha256: d1bdd6994942d18d631639f9f7245e8d6ba6bd96eb97f0fb5499d24109941839 -->

콘텐츠 검사는 규칙이나 검사 모델로 모델 입력을 평가하고, 설정한 정책을 위반하는 요청을
차단합니다. 도구 루프의 후속 호출을 포함해 매 모델 호출 전에 실행합니다.
필요하면 최종 응답 검사도 활성화할 수 있습니다.

## 검사할 클라이언트 구성

규칙 기반 검사를 사용하려면 Spring AI `ChatModel`이 구성된 애플리케이션에 다음 모듈을
추가합니다.

- `spring-ai-privacy-guardrails-inspection-spring-boot-starter`
- `spring-ai-privacy-guardrails-inspection-rules`

`application.yml`에서 검사를 활성화합니다.

```yaml
spring:
  ai:
    inspection:
      enabled: true
```

Spring 설정에 규칙을 등록하고 클라이언트에 적용합니다.

```java
@Bean
ContentInspector applicationRules() {
    return new RuleBasedContentInspector("application-rules", List.of(
        InspectionRule.literal("blocked-phrase", InspectionFinding.Category.PROMPT_INJECTION,
            "ignore previous instructions")));
}

@Bean
ChatClient inspectedChatClient(ChatModel chatModel, InspectionChatClientConfigurer inspectionConfigurer) {
    return inspectionConfigurer.configure(ChatClient.builder(chatModel)).build();
}
```

이 예제는 `ignore previous instructions`가 포함된 입력에 대해 모델 실행 전에
`InspectionBlockedException`을 발생시킵니다. 이렇게 구성한 클라이언트에만 검사가
적용됩니다. 검사를 활성화하려면 `ContentInspector` 빈이 하나 이상 필요합니다.

문자열 규칙은 대소문자를 구분합니다. 정규식이 필요하면 RE2/J 패턴을 사용하는
`InspectionRule.regex(...)`를 사용하세요. 애플리케이션에 맞는 문자열과 패턴을 설정합니다.

### 개인정보 보호 또는 도구 권한 검사와 조합

스타터가 제공하는 configurer들을 조합한 뒤 한 번만 적용합니다.

```java
ChatClient client = ModelRequestBoundaryConfigurer.compose(
        privacyConfigurer, inspectionConfigurer)
    .configure(ChatClient.builder(chatModel))
    .build();
```

개인정보 처리, 도구 정의 권한 검사, 콘텐츠 검사 순으로 실행합니다.
개인정보 보호와 권한 검사를 함께 사용한다면
`privacySecurityFactory.builderWithBoundary(chatModel, inspectionConfigurer).build()`를,
권한 검사만 사용한다면 `ToolAuthorizationChatClientFactory.builderWithBoundary`를
사용합니다. 설정 방법은 [도구 권한](security.md)을 참고하세요.

## 선택적 모델 출력 검사

출력 검사의 기본값은 비활성화입니다. 구성한 클라이언트에 다음 설정으로 활성화합니다.

```yaml
spring:
  ai:
    inspection:
      enabled: true
      output:
        enabled: true
```

출력 검사는 모델 호출과 도구 실행 후, 애플리케이션에 전달할 최종 assistant 본문을
검사합니다. `returnDirect` 도구 결과도 포함합니다. `ALLOW`는 응답을 반환하고
`BLOCK`은 `InspectionBlockedException`을 발생시킵니다.

개인정보 출력 보호도 활성화했다면 그 처리 이후에 콘텐츠를 검사합니다.
개인정보 처리가 완료된 출력을 요구하는 HTTP 검사기를 사용한다면 개인정보 출력 보호를
함께 활성화하세요.

스트리밍 응답은 버퍼에 모아 검사를 마친 뒤 전달합니다. 애플리케이션에는 최종 모델
응답이나 도구 결과를 전달하며, 도구 루프의 중간 텍스트는 생략합니다.
스트림 수집이 실패하거나 취소되면 `FAIL_OPEN`에서도 버퍼의 내용을 폐기합니다.
수집 한도는 [아래 설정 표](#검사-범위와-한도)를 참고하세요.

Boot는 입력과 출력에 같은 검사기와 정책을 적용합니다. 다른 출력 규칙이 필요하면
별도 서비스를 사용하는 Advisor를 추가합니다.

```java
InspectionChatClientConfigurer configurer = inspectionConfigurer
    .withOutputInspection(new InspectionOutputAdvisor(outputService));
```

반환된 configurer로 클라이언트를 구성합니다. 출력 검사만 필요하면
`InspectionOutputAdvisor`를 직접 등록할 수도 있습니다.

## HTTP 검사 모델과 개인정보 처리

`spring-ai-privacy-guardrails-inspection-openai-compatible`을 추가하고,
배포한 검사 모델에 맞춰 검사기를 등록합니다.

```java
@Bean
ContentInspector httpGuard() {
    OpenAiCompatibleInspectionConfig config = new OpenAiCompatibleInspectionConfig(
        URI.create("http://127.0.0.1:8000/v1/chat/completions"),
        "kakaocorp/kanana-safeguard-prompt-2.1b",
        null,
        Duration.ofSeconds(10),
        16_384,
        true);
    return new OpenAiCompatibleContentInspector("primary-guard", config, new KananaPromptProtocol());
}
```

`endpoint`에는 chat-completions의 전체 URL을 지정합니다. 생성자의 세 번째 인자는
API 키이며, 인증을 생략하려면 `null`을 사용합니다. 다음 두 인자는 요청별 제한 시간과
최대 응답 크기(바이트)입니다.

배포한 모델에 맞는 프로토콜을 선택합니다.

| 프로토콜 | 요구하는 응답 |
| --- | --- |
| `KananaPromptProtocol` | Kanana Safeguard-Prompt 레이블: `<SAFE>`, `<UNSAFE-A1>`, `<UNSAFE-A2>` |
| `JsonVerdictProtocol` | 정확히 `{"verdict":"SAFE"}` 또는 `{"verdict":"UNSAFE"}` |

모델 실행 조건은 [Kanana 모델 카드](https://huggingface.co/kakaocorp/kanana-safeguard-prompt-2.1b/blob/main/README.md)를
참고하세요. OpenAI 호환 엔드포인트라도 선택한 프로토콜을 지원해야 합니다.
다른 응답 형식을 사용하려면 `GuardModelProtocol`을 구현합니다.

마지막 설정 인자인 `requirePrivacyProcessedContent`는 검사 모델로 텍스트를 보낼 수 있는
조건입니다. `true`이면 모든 검사 구간의 개인정보 처리가 완료되어야 합니다.
`false`이면 개인정보 보호 없이도 전송할 수 있습니다.
개인정보 보호 기능에서 수행한 처리의 완료 여부를 확인하는 설정입니다.

| 개인정보 처리 상태 | 의미 |
| --- | --- |
| `PROCESSED` | 해당 텍스트에 설정된 개인정보 처리가 완료됨 |
| `UNPROCESSED` | 호출자가 개인정보 처리를 적용하지 않았음을 알고 있음 |
| `UNKNOWN` | 처리 완료 여부를 확인할 수 없음 |

스타터는 개인정보 보호 통합을 사용할 수 있으면 입력 상태를 자동으로 판단합니다.
Java에서 직접 구성할 때 기본 resolver는 `UNKNOWN`을 반환합니다.
네 인자 생성자에 `PrivacyProcessingStatusResolver`를 전달하고,
`PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(request)`의 반환값이
`true`이면 `PROCESSED`, `false`이면 `UNKNOWN`으로 매핑합니다.

HTTP 검사기는 텍스트 구간마다 요청을 한 번 보냅니다.
설정한 요청 제한 시간과 전체 검사에서 남은 시간 중 짧은 쪽을 적용합니다.

## 로컬 ONNX 분류기

`spring-ai-privacy-guardrails-inspection-onnx`를 추가하면 ONNX Runtime으로
CPU에서 분류기를 실행할 수 있습니다. 토큰화는 DJL을 사용합니다.
서로 호환되는 모델과 토크나이저 파일을 준비하고 검사기를 등록합니다.

이 예시는 모델의 출력 순서가 정상(0), 인젝션(1), 유출(2)인 경우의 설정입니다.
`Label.index`는 사용하는 모델의 실제 출력 순서에 맞춰 지정하세요.

```java
@Bean(destroyMethod = "close")
OnnxContentInspector localGuard() {
    Path directory = Path.of("/opt/models/local-classifier");
    OnnxClassificationConfig onnxClassificationConfig = OnnxClassificationConfig.builder()
        .tokenizer(directory.resolve("tokenizer.json"))
        .tokenizerConfig(directory.resolve("tokenizer_config.json"))
        .maxTokens(256)
        .overlapTokens(32)
        .logitCount(3)
        .activation(OnnxClassificationConfig.Activation.SOFTMAX)
        .labels(List.of(
            new OnnxClassificationConfig.Label(1,
                InspectionFinding.Category.PROMPT_INJECTION, "INJECTION", 0.9),
            new OnnxClassificationConfig.Label(2,
                InspectionFinding.Category.PROMPT_LEAKING, "LEAKING", 0.9)))
        .tokenInputType(OnnxClassificationConfig.TokenInputType.INT64)
        .build();
    return new OnnxContentInspector("local-guard",
        OnnxInspectionConfig.defaults(directory.resolve("model.onnx")),
        onnxClassificationConfig);
}
```

겹침 32토큰과 임계값 `0.9`는 애플리케이션 설정의 예시입니다.
애플리케이션에서 사용하는 언어의 정상 입력과 공격 입력으로 임계값을 조정하세요.
창 길이, 겹침이나 모델 양자화가 바뀌면 임계값을 다시 조정하세요.

빌더에서는 두 토크나이저 파일, `maxTokens`, `logitCount`, `activation`, `labels`를
반드시 지정해야 합니다. 기본값은 겹침 0, 토큰 입력 타입 INT64, 모델 출력 이름 `logits`입니다.

시작 전에 `model.onnx`, `tokenizer.json`, `tokenizer_config.json`과 모델이 참조하는
외부 데이터 파일을 준비합니다. 오프라인 환경에서는
DJL의 네이티브 토크나이저 라이브러리도 미리 준비해야 할 수 있습니다.
위 빈 선언처럼 더 이상 사용하지 않을 때 검사기를 닫도록 구성합니다.

### 모델 요구 사항

| 구성 요소 | 지원 형식 |
| --- | --- |
| 입력 | `input_ids`, 선택적 `attention_mask`와 `token_type_ids`. 각각 `[1, maxTokens]` 형태 |
| 토큰 타입 | 기본 INT64. INT32 모델은 모든 토큰 입력의 타입을 명시적으로 설정 |
| 출력 | `[1, logitCount]` 형태의 FLOAT 원시 로짓. 출력 이름의 기본값은 `logits` |

내보낸 모델의 출력 이름이 다르면 빌더에 `.modelOutputName("classification_output")`을
지정합니다.

초기화할 때 입력 이름, 타입, 고정된 차원과 선택한 출력을 설정과 비교합니다.
모델 로딩 실패와 메타정보 불일치는 검사 전에 `CONFIGURATION`을 담은
`InspectionException`을 발생시킵니다. 동적인 출력 형태는 추론 중 다시 확인하며,
예상과 다른 출력 형태나 유한하지 않은 로짓은 `MODEL_ERROR`로 처리합니다.

입력 이름, 전처리나 출력 구조가 다른 모델은 요구 사항에 맞게 내보내거나
`ContentInspector`를 별도로 구현해 연결합니다.

### 분류 설정

출력 이름, 인덱스, 타입과 점수 계산 방식은 모델 문서와 내보내기 파일에 맞춰 지정합니다.
탐지 결과 매핑, 임계값과 겹침은 애플리케이션에 맞게 선택합니다.
이 값들은 `OnnxClassificationConfig`에서 설정합니다.

| 설정 | 지정 방법 |
| --- | --- |
| `tokenizer`, `tokenizerConfig` | 모델과 일치하는 로컬 토크나이저 파일을 지정합니다. |
| `modelOutputName` | 내보낸 모델의 원시 로짓 출력 이름을 지정합니다. 기본값은 `logits`입니다. |
| `logitCount` | 출력 형태가 `[1, N]`이면 N을 지정합니다. 탐지 결과에 매핑하지 않는 출력도 포함합니다. |
| `activation` | 모델 문서에 명시된 로짓에서 점수로의 변환 방식을 사용합니다. |
| `Label.index` | 탐지할 출력의 위치를 0부터 세어 지정합니다. |
| `tokenInputType` | 내보내기 파일의 모든 토큰 입력과 정수 타입을 맞춥니다. |
| `maxTokens` | 특수 토큰과 패딩을 포함한 창 길이입니다. 모델이 지원하는 길이를 선택하고, 고정 길이 모델은 정확한 길이를 사용합니다. |
| `overlapTokens` | 창 사이에 반복할 본문 토큰 수를 정합니다. 특수 토큰을 제외한 창의 본문 용량보다 작아야 합니다. |
| `Label.category`, `Label.code` | 탐지 조건마다 범주와 진단용 코드를 지정합니다. |
| `Label.threshold` | 활성화 함수 적용 후 점수가 이 값 이상이면 탐지 결과를 생성합니다. |

`labels`는 선택한 모델 출력을 탐지 결과에 연결합니다. 위 예시에서는 정상 출력을
매핑하지 않습니다. 서로 다른 코드를 가진 여러 레이블이 같은 출력 인덱스를 사용할 수
있습니다. 임계값은 각각 독립적으로 적용되며, 조건을 충족한 매핑마다 탐지 결과가 생성됩니다.

### `activation` 선택과 임계값

`OnnxClassificationConfig.activation`은 모델의 원시 출력(로짓)을 0에서 1 사이의
점수로 변환하는 방식을 지정합니다. 검사기가 변환을 수행하고 그 점수를 `Label.threshold`와
비교합니다. 모델 문서에 맞는 값을 선택하세요.

| `activation` | 사용하는 모델 |
| --- | --- |
| `SOFTMAX` | 정상·인젝션·유출처럼 서로 배타적인 범주 중 하나를 판별하는 모델. 로짓 두 개 이상 |
| `SIGMOID` | 항목별로 독립적으로 판단하는 모델 또는 로짓 하나를 반환하는 이진 분류 모델 |

로짓 하나를 반환하는 이진 분류 모델은 `logitCount=1`을 사용합니다.
모델 출력은 활성화 함수를 적용하기 전의 원시 로짓이어야 합니다. 이미 확률을 반환하는
모델은 원시 로짓을 반환하도록 내보내거나 별도 검사기를 사용합니다.

숫자로 보면, 한 창의 모델 출력이 `logits = [[3.0, 7.0, -3.0]]`이고 순서가 위 예시처럼
정상, 인젝션, 유출이라고 가정합니다. `[1, 3]`은 한 창에서 원시 점수 세 개를
반환한다는 뜻입니다.

| 출력 인덱스 | 의미 | 원시 로짓 | SOFTMAX 적용 후 점수 |
| --- | --- | --- | --- |
| 0 | 정상 | 3.0 | 0.017985 |
| 1 | 인젝션 | 7.0 | 0.981970 |
| 2 | 유출 | -3.0 | 0.000045 |

위 예시의 임계값 `0.9`를 적용하면 1번만 조건을 충족합니다. 검사기는 다음 탐지 결과를 반환합니다.

```java
new InspectionFinding("s0", InspectionFinding.Category.PROMPT_INJECTION,
    "INJECTION", 0.9819700105182744);
```

기본 정책은 이 탐지 결과를 보고 차단합니다. 인젝션 임계값을 `0.99`로 설정하면 매핑한 두 출력
모두 조건을 충족하지 않으므로 탐지 결과가 없고, 기본 정책은 완료된 검사를 허용합니다.
임계값은 활성화 함수를 적용한 점수와 `score >= threshold`로 비교합니다. 공개 결과에는
조건을 충족한 탐지 결과와 해당 점수가 담깁니다.

### 토크나이저 파일

`tokenizer.json`은 DJL의 Hugging Face Tokenizers 구현이 지원하는 Tokenizers JSON
형식으로 준비합니다. 그래프에는 모델의 정규화, 사전 토큰화, 어휘, 추가 토큰과
특수 토큰 처리가 보존되어야 합니다.
[DJL의 토크나이저 로딩](https://github.com/deepjavalibrary/djl/blob/v0.38.0/extensions/tokenizers/README.md#from-huggingface-pipeline)을 참고하세요.

`tokenizer_config.json`의 패딩과 잘림 방향은 그래프의 설정보다 우선합니다.
패딩 토큰은 어휘나 추가 토큰에 있어야 합니다. 다른 토크나이저 형식은 호환되는 JSON으로
내보내거나 별도 `ContentInspector`로 연결합니다.

### 실행 한도

CPU 스레드 수(`intraOpThreads`)와 요청의 창 개수 한도(`maxWindows`)는
`OnnxInspectionConfig`에서 설정합니다. 기본값은 스레드 2개와 창 256개입니다.
전체 검사 시간 제한은 `InspectionLimits`에서 설정하며 기본값은 10초입니다.
선택한 모델과 예상 입력 길이에 맞춰 자원 한도를 정하세요.

긴 텍스트는 겹치는 창으로 나누고 요청 전체에 창 개수 한도를 적용합니다.
매핑한 레이블마다 각 구간에서 임계값을 통과한 최고 점수를 보존합니다.
모든 창을 검사해야 해당 구간의 검사가 완료됩니다.

같은 검사기에 들어온 동시 요청은 순서대로 실행합니다. 대기, 토큰화와 추론 모두 요청의
제한 시간을 사용합니다. 동기 토큰화와 추론의 전후에 시간 초과와 인터럽트를 확인합니다.
제한 시간을 넘긴 네이티브 호출은 반환된 뒤 `TIMEOUT`으로 처리하며, 해당 구간은
미완료 상태로 남습니다. 로컬 ONNX 검사기와 규칙 검사기는 개인정보 처리 상태와
관계없이 텍스트를 검사합니다.

## 결과, 정책, 실패

스타터는 검사기 빈을 `@Order` 순서대로 구성합니다. `InspectionService`를 직접 생성하면
전달한 목록 순서대로 실행합니다. 기본 정책은 점수와 관계없이 탐지 결과가 하나라도 있으면
차단하고 이후 검사기를 실행하지 않습니다. 판단 방식을 바꾸려면 `InspectionPolicy` 빈을
등록합니다. 탐지 임계값은 각 검사기에서 정하며 서로 다른 모델의 점수를 직접 비교할 수는
없습니다.

`InspectionFinding`에는 구간 ID, `category`, `code`와 선택적인 `score`가 담깁니다.
검사한 텍스트는 포함하지 않습니다. 점수가 있으면 0에서 1 사이의 값을 사용합니다.
ONNX 탐지 결과에는 활성화 함수 적용 후 점수가 들어가며, 규칙, Kanana와 JSON-verdict는
`null`을 사용합니다.

| 범주 | 의미 |
| --- | --- |
| `PROMPT_ATTACK` | 구체적인 유형을 구분하지 않은 프롬프트 공격 |
| `PROMPT_INJECTION` | 의도된 지시를 덮어쓰려는 시도 |
| `PROMPT_LEAKING` | 숨겨진 프롬프트나 지시를 추출하려는 시도 |
| `POLICY_VIOLATION` | 프롬프트 공격 외의 애플리케이션 콘텐츠 정책 위반 |

검사기 ID, 구간 ID와 탐지 코드는 ASCII 영문자, 숫자, 밑줄, 점, 하이픈으로 구성된
1~128자이며 영문자나 숫자로 시작해야 합니다. 검사 원문을 넣지 않은 안정적인
진단용 식별자를 사용하세요.

기본 실패 정책은 `FAIL_CLOSED`입니다. 허용 가능한 운영 실패에도 요청을 진행하려면
`spring.ai.inspection.failure-policy=FAIL_OPEN`을 설정합니다.
콘텐츠 정책의 차단 결정은 실패 정책과 관계없이 적용합니다.

| 실패 | 동작 |
| --- | --- |
| `TIMEOUT`, `TRANSPORT_ERROR`, `HTTP_ERROR`, `MODEL_ERROR`, `INVALID_RESPONSE`, `INCOMPLETE` | `FAIL_CLOSED`는 차단. `FAIL_OPEN`은 보존된 탐지 결과를 콘텐츠 정책이 허용할 때만 통과 |
| `CANCELLED`, `LIMIT_EXCEEDED`, `DISCLOSURE_DENIED`, `CONFIGURATION`, `UNSUPPORTED_CONTENT`, `INVALID_RESULT` | 실패 정책과 관계없이 `InspectionException` 발생 |

`InspectionReport`에는 실행 순서대로 검사 결과가 담깁니다.
`allowedAfterFailure()`로 운영 실패 이후 허용된 요청을 구분할 수 있습니다.
서비스 예외에는 그 시점까지 수집된 보고서가 있으면 함께 담깁니다.

사용자 정의 검사기는 고유하고 안정적인 ID를 가진 `ContentInspector`로 구현합니다.
모든 구간을 검사한 경우에만 `COMPLETED`를 보고하고, 실패하면 완료된 구간 ID와
부분 탐지 결과를 보존합니다. 요청의 한도, 제한 시간과 인터럽트를 준수해야 합니다.
전체 구현 계약은 인터페이스 JavaDoc을 참고하세요.

## 관측

`InspectionObserver` 빈을 등록하면 검사 보고서와 실패를 받을 수 있습니다.

```java
@Bean
InspectionObserver inspectionObserver() {
    return new InspectionObserver() {
        @Override
        public void onInspection(InspectionReport report) {
            auditDecision(report);
        }

        @Override
        public void onFailure(InspectionException failure) {
            auditFailure(failure.failure(), failure.report());
        }
    };
}
```

예제의 audit 메서드는 애플리케이션에서 구현합니다. `onInspection`은 허용·차단 결정을,
`onFailure`는 강제 차단 실패를 받습니다. 콜백은 동시에 실행될 수 있으므로 빠르게
반환해야 합니다. 관측 콜백의 런타임 예외는 검사 결정에 영향을 주지 않습니다.

## 검사 범위와 한도

입력 검사는 메시지 본문, 도구 결과, 도구 호출 인자와 지원하는 추론 텍스트를 다룹니다.
DeepSeek의 `reasoningContent`와 assistant 메타데이터의 문자열
`reasoningContent`, `thinking`도 포함합니다. 미디어와 지원하지 않는 메시지 하위 타입은
거부합니다.

입력 본문은 각각 하나의 구간으로 검사기에 전달합니다. JSON의 키, 값, 구조와
이스케이프 표기를 보존합니다. 규칙은 JSON 이스케이프를 해석하지 않고 전달받은 텍스트에
적용합니다. 개인정보 보호를 함께 사용하면 개인정보 처리 후의 본문을 받으며,
JSON 키는 그대로 유지됩니다.

출력 검사는 최종 assistant 본문을 다룹니다. 모델 제공자별 추론 필드, 도구 호출 인자와
메타데이터는 제외합니다. `isThought=true` 또는 `thinking=true`로 표시된 본문 프레임은
답변 프레임과 분리해서 검사합니다.
JSON 출력에서는 문자열과 숫자 값을 각각 검사하고 키, boolean과 null은 제외합니다.
유효하지 않은 JSON은 일반 텍스트로 검사합니다. 검사는 입력과 출력 본문을 수정하지 않습니다.

도구 정의, MCP 스키마, 구조화된 출력 설정과 기타 메타데이터는 검사 범위 밖입니다.
입력 검사는 모델이 새로 생성한 도구 호출 인자를 실행 전에 검사하지 않습니다.
실행 권한은 [도구 권한](security.md)으로 제어하세요.

다음 설정은 `spring.ai.inspection` 접두사를 사용합니다.

| 설정 | 기본값 | 적용 범위 |
| --- | --- | --- |
| `max-segments` | 64 | 빈 본문을 포함한 추출된 텍스트 구간 |
| `max-characters` | 131072 | JSON 구문을 포함한 원본 텍스트의 UTF-16 단위 합산 길이 |
| `max-findings` | 10000 | 전체 검사기의 탐지 결과 수 |
| `timeout` | 10s | 한 번의 검사에서 검사기와 정책 평가가 공유하는 제한 시간 |
| `output.max-frames` | 4096 | 빈 프레임을 포함한 스트리밍 도구 루프 전체의 프레임 수 |
| `output.stream-timeout` | 60s | 모델 호출과 도구 실행을 포함한 전체 스트림 수집 시간 |

매 모델 호출마다 검사 한도를 새로 적용하며 출력 검사에도 별도 한도를 적용합니다.
`spring.ai.privacy.processing`, `spring.ai.privacy.response-inspection` 설정과는
독립적입니다. 직접 API를 사용할 때는 `InspectionLimits`를 전달합니다.

## 모듈과 책임

| 모듈 접미사 (`spring-ai-privacy-guardrails-…`) | 용도 |
| --- | --- |
| `inspection-core` | Spring에 의존하지 않는 검사 계약, 정책과 실행 |
| `inspection-rules` | 문자열 및 RE2/J 규칙 매칭 |
| `inspection-onnx` | 로컬 ONNX 시퀀스 분류기 |
| `inspection-openai-compatible` | 명시적 프로토콜에 따른 HTTP 검사 모델 호출 |
| `inspection-spring-ai` | 클라이언트별 입력·출력 검사 |
| `inspection-spring-boot-starter` | Spring Boot 설정과 빈 구성 |

## 평가

애플리케이션의 언어와 사용 사례를 대표하는 정상 입력과 공격 입력으로 탐지 품질을
평가하세요.

프로젝트의 테스트와 평가 도구는 [평가](evaluation.md)를 참고하세요.

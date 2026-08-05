# 설정과 사용법

[English](../configuration.md) | [한국어](configuration.md)

<!-- i18n-source: docs/configuration.md -->
<!-- i18n-source-sha256: ea9f24d31321e160c0981825ae31f38112ca969f493b31493a812d3d0a8447de -->

이 문서는 Spring AI Privacy Guardrails를 사용하는 애플리케이션을 위한 전체
참고 문서입니다. Base starter는 `core`와 Spring AI 경계를 제공하며, provider
starter는 별도의 개인정보 보호 정책을 만들지 않고 분석기 하나를 추가합니다.

## 배포 artifact

> **첫 릴리즈 전 안내:** 이 절의 `0.1.0` 좌표는 첫 릴리즈 예정 좌표이며 아직
> Maven Central에서 내려받을 수 없습니다. 그전까지는 이 저장소를 복제해
> 포함된 샘플을 실행하거나 소스에서 직접 빌드하세요.

주 진입점 하나를 선택하세요.

| 진입점 | 의존성 | 용도 |
| --- | --- | --- |
| Presidio starter | `io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.1.0` | 일반 개인정보 탐지에 권장합니다. Base starter, HTTP adapter와 조건부 health indicator를 포함합니다. |
| Base starter | `io.github.ultramancode:spring-ai-privacy-guardrails-spring-boot-starter:0.1.0` | Regex 또는 사용자 정의 분석기용입니다. provider adapter는 포함하지 않습니다. |
| OpenNLP starter | `io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.1.0` | 호환되는 NER 모델을 이미 보유한 애플리케이션을 위한 고급 JVM 전용 구성입니다. |

모든 starter는 기본적으로 비활성화됩니다. 전역 개인정보 보호 기능과 선택한
분석기를 명시적으로 활성화하세요. Presidio를 `http://localhost:5002` 외의
주소에서 실행한다면 `analyzer-url`도 설정해야 합니다.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      presidio:
        enabled: true
```

분석기는 조합해 사용할 수 있습니다. Presidio에는 기본 Regex 설정이 이미 포함되므로,
Presidio와 OpenNLP를 함께 사용할 때는 base starter가 아니라 두 provider starter를
선언합니다. 선택한 모든 분석기가 원문 텍스트를 받으므로 필요한 조합만 구성하세요.

## 설정 속성

| 속성 | 기본값 | 의미 |
| --- | --- | --- |
| `spring.ai.privacy.enabled` | `false` | 개인정보 보호 기반 기능과 선택한 고정 요청 경계를 활성화합니다. |
| `analysis.language` | `en` | 대소문자를 구분하지 않는 ASCII 언어 코드입니다. 소문자 정규형으로 분석기에 전달합니다. |
| `analysis.included-entity-types` | 비어 있음 | 탐지 허용 목록입니다. 신뢰할 수 있는 유형을 등록하는 설정은 아닙니다. |
| `analysis.minimum-score` | `0.0` | 전체 신뢰도 하한입니다. |
| `analysis.mode` | `UNION` | 탐지 근거 선택 전략입니다. |
| `analysis.primary-provider` | 미설정 | primary mode와 `REQUIRE_PRIMARY`에서 필요한 provider ID입니다. 대소문자를 구분하지 않습니다. |
| `analysis.supplemental-providers` | 비어 있음 | primary provider를 보완하는 provider ID 목록입니다. |
| `analysis.failure-policy` | `REQUIRE_ALL` | provider 가용성에 대한 실패 정책입니다. |
| `analysis.provider-minimum-scores` | 비어 있음 | provider ID별 신뢰도 하한입니다. 전체 하한과 provider 하한 중 더 큰 값을 적용합니다. |
| `analysis.entity-aliases` | 비어 있음 | 분석기 엔티티 label을 정규 유형에 연결하는 명시적 매핑입니다. |
| `analysis.type-conflict-fallback` | `PII` | 해석하지 못한 중첩 유형 충돌에 사용할 유형입니다. |
| `output.enabled` | `false` | 출력 보호를 활성화합니다. |
| `output.action` | `TOKENIZE` | `TOKENIZE`, `REDACT` 또는 유형이 지정된 예외를 던지는 `BLOCK`을 선택합니다. |
| `output.block-exception-message` | `Response blocked by privacy guardrail.` | `BLOCK` 예외에 사용할 안전한 메시지입니다. |
| `response-inspection.max-stream-frames` | `1024` | 스트리밍 응답 하나에서 검사할 최대 frame 수입니다. |
| `response-inspection.max-characters` | `1000000` | 호출 또는 스트리밍 응답 하나에서 검사할 텍스트와 도구 인자의 최대 문자 수입니다. |
| `response-inspection.max-media-bytes` | `16777216` | 호출 또는 스트리밍 응답 하나에서 검사할 수 있는 미디어의 최대 byte 수입니다. |
| `response-inspection.stream-idle-timeout` | `60s` | 스트리밍 응답 frame이 도착하지 않아도 기다리는 최대 시간입니다. |
| `tools.disclosures` | 비어 있음 | 정확한 도구 이름을 원문으로 받을 수 있는 엔티티 유형에 매핑합니다. |
| `regex.enabled` | `false` | 애플리케이션이 제공한 Regex 규칙을 활성화합니다. |
| `regex.rules[].entity-type` | 규칙마다 필수 | 규칙이 반환할 정확한 정규 엔티티 유형입니다. |
| `regex.rules[].pattern` | 규칙마다 필수 | 원문 텍스트에 적용할 Java 정규식입니다. |
| `regex.rules[].score` | `0.85` | 각 일치 결과에 부여할 신뢰도입니다. |
| `regex.rules[].capture-group` | `0` | 탐지 범위(span)로 사용할 capture group입니다. |
| `presidio.enabled` | `false` | Presidio provider를 활성화합니다. |
| `presidio.analyzer-url` | `http://localhost:5002` | 분석기의 기본 URI입니다. |
| `presidio.timeout` | `5s` | 응답 본문 수신 완료를 포함한 각 HTTP 시도와 health check의 제한 시간입니다. |
| `presidio.max-retries` | `1` | 첫 시도 이후의 재시도 횟수입니다. |
| `presidio.retry-backoff` | `300ms` | 시도 사이의 대기 시간입니다. |
| `presidio.max-response-bytes` | `8388608` | 검증을 위해 보존할 Presidio 응답 본문의 최대 byte 수입니다. |
| `presidio.headers` | 비어 있음 | 추가로 전송할 HTTP 요청 header입니다. `Content-Type`은 라이브러리가 관리합니다. |
| `opennlp.enabled` | `false` | 사용자가 제공한 로컬 OpenNLP 모델을 활성화합니다. |
| `opennlp.tokenizer-model` | 미설정 | 선택적인 tokenizer 모델 resource입니다. 설정하지 않으면 `SimpleTokenizer`를 사용합니다. |
| `opennlp.entity-models` | 비어 있음 | 정규 엔티티 유형을 필수 name-finder 모델 resource에 연결합니다. |

Regex `rules`, Presidio `headers`, OpenNLP `entity-models`의 기본값은 비어 있습니다.
Spring Boot 설정 metadata는 IDE 자동 완성을 제공합니다.
`analysis.language`는 영숫자 단어를 하나의 hyphen 또는 underscore로 구분한
1~64자 ASCII 식별자를 받습니다. 대소문자를 구분하지 않으며 `core`는
소문자 정규형을 모든 분석기에 전달합니다. 공백, 문법에 없는 문장부호,
빈 구분자와 반복된 구분자는 자동으로 잘라내거나 보정하지 않고 거부합니다.

Starter 의존성만 추가하면 모든 개인정보 보호 자동 설정은 비활성 상태로
남으며 분석기를 요구하지 않습니다. 전역 기능을 활성화한 뒤에는 분석기가
하나도 없으면 동작하지 않는 보호 경계를 노출하는 대신 애플리케이션 시작을
실패시킵니다.

이 라이브러리는 생성 횟수, 도구 호출 횟수, 등록한 도구 수, 누적 에이전트 반복
예산을 강제하지 않습니다. 호출 횟수, 반복, 비용, 동시성과 부수 효과 제한은
애플리케이션이나 orchestration framework에서 설정하세요. 나머지 출력 상한은
라이브러리가 개인정보 보호 경계를 집행하기 위해 검사하거나 보존해야 하는
스트림 콘텐츠만 제한합니다.

## 탐지와 해석

분석기는 원문에서 탐지한 범위를 근거로 반환합니다. Core는 별칭, 탐지 허용 목록,
신뢰도 하한, provider 선택과 중첩 해석을 적용합니다. 부분 문자열의 기준은 언제나
원문 텍스트입니다. Regex, Presidio, 사용자 정의 `PiiAnalyzer` bean과 OpenNLP를
조합할 수 있습니다.

`UNION`은 설정한 분석기를 모두 실행하고 같은 탐지 근거를 합칩니다. 겹친 범위를
하나의 span이 완전히 덮으면 그 유형을 유지합니다. 일부만 겹치면 부분 문자열이
노출되지 않도록 범위를 합치고, 유형 충돌을 해결할 수 없으면 기본적으로 `PII`를
사용합니다. `REQUIRE_ALL`은 provider 하나라도 실패하면 개인정보를 제거한 진단
정보만 남기고 안전하게 차단합니다. `REQUIRE_PRIMARY`는 primary provider의 성공을
요구하되 다른 provider의 실패는 허용합니다. `ALLOW_PARTIAL`은 성공한 탐지 결과를
유지하므로 탐지 범위가 줄어들 수 있으며, 모든 provider가 실패하면 호출도
실패합니다.

Presidio starter를 사용할 때 다음 설정은 Presidio를 primary provider로,
애플리케이션 전용 Regex 분석기를 supplemental provider로 등록합니다.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      analysis:
        mode: primary-with-fallback
        primary-provider: presidio
        supplemental-providers: [regex]
        failure-policy: allow-partial
        entity-aliases:
          US_SSN: NATIONAL_ID
      presidio:
        enabled: true
      regex:
        enabled: true
        rules:
          - entity-type: EMPLOYEE_ID
            pattern: "\\bEMP-\\d{4}\\b"
            score: 0.90
```

`PRIMARY`는 primary와 supplemental provider를 실행하고, 이 집합에 포함되지 않은
분석기가 설정되어 있으면 거부합니다. `PRIMARY_WITH_FALLBACK`에는 `ALLOW_PARTIAL`,
primary provider와 하나 이상의 non-primary 분석기가 필요합니다. Supplemental
provider는 항상 primary와 함께 실행하고, fallback 전용 provider는 primary가 실패한
뒤에만 실행합니다. provider별 신뢰도 하한을 추가한다고 provider가 등록되는 것은
아닙니다. 따라서 이 예제의 Regex는 평소에도 `EMPLOYEE_ID`를 보호하며 Presidio 장애
시에도 남아 있는 탐지 수단이 됩니다.

provider ID는 영숫자 구간을 하나의 hyphen 또는 underscore로 구분한 1~128자의
ASCII 식별자입니다. Core는 대소문자를 대문자로 정규화합니다. 잘못된 구분자,
문장부호, 공백과 중복 ID는 거부합니다.

엔티티 label은 대문자 ASCII 영숫자 구간을 하나의 underscore로 구분한 1~128자의
식별자입니다. 소문자, 공백, hyphen, 문장부호와 빈 구간은 임의로 보정하지 않고
거부합니다. 잘못된 설정이나 분석기 출력은 해당 계약의 실패로 처리합니다.

기본 registry가 그대로 인정하는 정규 유형은 `PII`, `PERSON`,
`ORGANIZATION`, `LOCATION`, `EMAIL_ADDRESS`, `PHONE_NUMBER`, `NATIONAL_ID`,
`CREDIT_CARD`, `DATE_TIME`, `IP_ADDRESS`, `URL`, `IBAN_CODE`, `CRYPTO`, `NRP`,
`MEDICAL_LICENSE`입니다. 암묵적인 provider 별칭은 포함하지 않습니다. 따라서
Presidio의 `US_SSN`처럼 provider, 모델, 국가 또는 애플리케이션에 종속된 label을
하나의 애플리케이션 정책 유형으로 취급하려면 명시적으로 매핑해야 합니다.

```yaml
spring:
  ai:
    privacy:
      analysis:
        entity-aliases:
          US_SSN: NATIONAL_ID
          KR_RRN: NATIONAL_ID
```

형식은 올바르지만 registry에 없는 label은 `PII`가 됩니다. 탐지 허용 목록에서
`PII`를 제외했다면 해당 결과도 제거됩니다. 필요하면 허용된 정규 유형으로
매핑하세요. `PiiAnalyzer.trustedEntityTypes()`를 사용하면 로컬 분석기가 자신에게
속한 신뢰 유형을 선언할 수 있습니다. 탐지 허용 목록 자체가 유형을 신뢰 대상으로
만드는 것은 아니며, 한 분석기가 다른 provider의 label을 신뢰 유형으로 등록할 수도
없습니다. Registry 별칭과 명시적으로 등록한 신뢰 정규 유형은 전역에 적용됩니다.

사용자 정의 분석기는 여러 요청이 공유하는 singleton 객체입니다. 고유한 provider ID를
제공하고 스레드 안전(thread-safe)하며 재진입 가능해야 합니다. 차단 작업에는 유한한
시간 제한을 적용하고 중단 요청에도 협조해야 합니다. 또한
`PiiAnalyzer.MAX_RESULT_SPANS`(100,000)개 이하의 span만 반환해야 하며, 반환 전에
할당하는 메모리의 상한도 분석기 구현이 직접 관리해야 합니다.

## Regex provider

Regex는 모든 개인정보를 탐지하는 용도가 아니라 프로젝트 고유 식별자와 결과가
결정적인 로컬 흐름에 적합합니다. Pattern은 신뢰하는 설정으로 취급되며 Java의
backtracking 정규식 엔진에서 실행됩니다. 사용자 입력으로 pattern을 받지 말고,
중첩되거나 모호한 수량자는 피하세요. Span 수 상한만으로 이미 진행 중인
backtracking을 중단할 수는 없습니다.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      regex:
        enabled: true
        rules:
          - entity-type: CUSTOMER_ID
            pattern: "\\bCUST-\\d{6}\\b"
            score: 0.90
            capture-group: 0
```

각 규칙에는 `entity-type`과 `pattern`이 필요합니다. `score` 기본값은 `0.85`,
`capture-group` 기본값은 `0`입니다. 규칙 검증과 Java pattern 컴파일 실패는
애플리케이션 소유의 원래 예외 세부 정보를 유지합니다. Pattern에 비밀값을 넣지
말고 시작 시 출력되는 진단 정보도 보호하세요.

## Presidio provider

```yaml
spring:
  ai:
    privacy:
      enabled: true
      analysis:
        language: en
      presidio:
        enabled: true
        analyzer-url: https://presidio.internal
        timeout: 5s
        max-retries: 1
        retry-backoff: 300ms
        max-response-bytes: 8388608
        headers:
          X-API-Key: ${PRESIDIO_API_KEY}
```

`analyzer-url`은 사용자 정보, query와 fragment가 없는 기본 HTTP(S) URI여야 합니다.
자격 증명은 URL이나 소스 파일이 아니라 header와 비밀값 관리 시스템에 두세요. URI
문법 오류는 애플리케이션 소유의 원래 예외 세부 정보를 유지합니다. 설정한 header
이름은 앞뒤 공백 없이 정확한 HTTP field-name 문법을 따라야 합니다. HTTP 규약에
따라 header 이름 비교는 대소문자를 구분하지 않으며, header 값은 잘라내지 않고
그대로 유지합니다. Adapter는 응답 본문 수신이 끝날 때까지 `timeout`을 적용합니다.
시간이 초과되면 대기 중인 클라이언트 측 HTTP 시도의 취소를 요청하지만, 원격
서비스의 처리까지 이미 중단되었다고 보장하지는 않습니다. 전송 실패, timeout,
HTTP 408/429와 5xx 응답은 재시도하고, 그 밖의 4xx 응답은 즉시 실패합니다.
`max-response-bytes`는 양수여야 하며 기본값은 8 MiB입니다. 같은 byte 상한을 HTTP
응답 수집과 parser가 처리하는 문서 길이에 모두 적용합니다. 이 값을 늘리면 분석기
응답 하나가 사용할 수 있는 최대 메모리도 증가합니다. 크기가 상한을 넘거나 구조가
안전하지 않은 응답은 개인정보를 포함하지 않는 안전한 분석기 응답 오류로 처리합니다.

Spring Boot Health가 있으면 provider가 필요할 때 생성되는
`presidioHealthIndicator`를 제공합니다. 이 indicator는 상태와 안정적인 실패 유형만
보고하며 URL, header, 응답 본문 또는 전송 예외를 노출하지 않습니다.

로컬 Docker 구성은 [samples/presidio](../../samples/presidio/README.md)에
있습니다.

## 선택형 JVM 전용 OpenNLP provider

이 구성은 호환되는 OpenNLP NER 모델을 이미 운영하면서 Python sidecar나 원격
분석기 요청 없이 같은 JVM에서 분석해야 하는 애플리케이션을 위한 것입니다. 모델의
언어, 라이선스, 학습 데이터와 tokenizer 호환성은 애플리케이션이 결정해야 하므로
라이브러리에 모델을 포함하지 않습니다.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      analysis:
        language: en
      opennlp:
        enabled: true
        tokenizer-model: classpath:/models/en-token.bin
        entity-models:
          PERSON: classpath:/models/en-ner-person.bin
          ORGANIZATION: classpath:/models/en-ner-organization.bin
```

`tokenizer-model`이 없으면 OpenNLP `SimpleTokenizer`를 사용합니다. 명시적으로 빈
resource 위치는 미설정으로 간주하지 않고 잘못된 설정으로 거부합니다. 엔티티 유형은
해당 모델 resource를 열기 전에 검증합니다. 모델 resource와 loader 실패는 개인정보
보호 라이브러리가 다른 예외로 교체하지 않고 애플리케이션 소유의 원래 원인을
유지합니다. Resource 위치에 자격 증명을 넣지 말고 시작 시 출력되는 진단 정보도
보호하세요. Tokenizer는 NER 모델 학습에 사용한 것과 일치해야 합니다. 탐지 품질은
전적으로 모델에 달려 있으므로 애플리케이션이 직접 보정해야 합니다. 이 기능은
선택형 배포 구성이며 범용 개인정보 탐지에 권장하는 기본값이 아닙니다.

[실행 가능한 샘플 가이드](../../samples/spring-ai-demo/README.md#optional-jvm-only-opennlp-adapter)는
모델 다운로드, checksum 확인, 구성 실행과 명시적으로 활성화하는 실제 연동 테스트
절차를 설명합니다.

## ChatClient 경계

완전한 개인정보 보호 경계가 필요한 모든 builder에 starter의
`PrivacyChatClientConfigurer`를 적용하세요.

```java
@Bean
ChatClient chatClient(
        ChatClient.Builder builder,
        PrivacyChatClientConfigurer privacyConfigurer
) {
    return privacyConfigurer.configure(builder).build();
}
```

이 설정은 수명주기, 입력, 도구 컨텍스트, 도구 호출 검증과 모델 경계를 설치하고,
활성화되어 있으면 출력 보호도 추가합니다. 다른 builder는 변경하지 않으며 전역 자동
적용 설정은 없습니다. 같은 builder에 configurer를 두 번 적용하면 잘못된 설정입니다.
관리되는 Advisor는 개별적으로 교체할 수 있는 Spring bean이 아닙니다.

파생 builder는 개인정보 보호 구성 전체를 유지합니다.

```java
ChatClient protectedClient = privacyConfigurer.configure(builder).build();
ChatClient derivedClient = protectedClient.mutate().build(); // already protected
```

`ChatClient.Builder.clone()` 또는 `ChatClient.mutate()`로 만든 builder에는 다시
configurer를 적용하지 마세요.

관련 없는 Advisor와 사용자 정의 숫자 순서는 허용됩니다. 직접 구성한다면 응답 검사
상한을 일관되게 사용하세요. 옵션 교체는 도구 컨텍스트 경계 앞에, 도구 호출 검증은
도구 interpreter 바로 안쪽에, 모델로 보낼 콘텐츠를 만드는 구성 요소는 모델 경계
앞에, 응답 후처리는 수명주기 경계 안쪽에 배치해야 합니다. 이후 변경은 애플리케이션이
책임지며 starter가 관리하는 순서를 권장합니다.

도구 이름, 설명과 JSON schema는 모델을 호출하기 전에 개인정보를 검사합니다.
provider 호출 ID와 유형 label은 내용을 해석하지 않는 제어 데이터로 유지됩니다.

보호 경계는 Spring AI의 표준 `UserMessage`, `SystemMessage`, `AssistantMessage`,
`ToolResponseMessage` 클래스와 reasoning/prefix field를 제공하는 공식
`DeepSeekAssistantMessage`를 지원합니다. 그 밖의 provider 전용 subclass와
애플리케이션이 정의한 `Message` 구현은 안전하게 차단합니다. Spring AI의 기본
`Message` 기본 계약만으로는 알 수 없는 field까지 빠짐없이 안전하게 복사할 수 없기
때문입니다.

## 도구 원문 공개

개인정보 보호 경계에 참여하는 콜백은 반드시 `PrivacyToolCallbackFactory`로
만드세요.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      tools:
        disclosures:
          customerLookup:
            - CUSTOMER_ID
```

```java
List<ToolCallback> protectedTools = toolCallbackFactory.wrapAll(
        List.of(customerLookup, knowledgeSearch));

ChatClient chatClient = privacyConfigurer.configure(ChatClient.builder(chatModel)
        .defaultTools(protectedTools.toArray(ToolCallback[]::new)))
        .build();
```

MCP나 다른 동적 `ToolCallbackProvider`는 한 번 조회한 콜백 목록이 아니라 provider
자체를 감싸세요. provider 하나에는 `wrapProvider(source)`를 사용하고, 여러
provider는 `wrapProviders(...)`로 결합합니다.

```java
ToolCallbackProvider protectedTools = toolCallbackFactory.wrapProviders(
        mcpTools,
        localToolProvider
);

return privacyConfigurer.configure(builder)
        .defaultTools(protectedTools)
        .build();
```

보호된 클라이언트 하나에 등록하는 모든 콜백은 같은 관리 factory에 전달했던 원본
입력이어야 합니다. 중복 이름, 보호되지 않은 콜백, 이미 감싼 값과 잘못된 provider
snapshot은 안전하게 실패합니다. 애플리케이션 소유의 provider와 accessor 예외는
원형대로 전파되므로 호스트의 진단 정보와 로그를 보호하세요.

원문 공개는 기본적으로 거부됩니다. `tools.disclosures`에 등록한 정확하고
대소문자를 구분하는 도구 이름과 정규 엔티티 유형만 원문을 받습니다. 동적 provider가
이름에 prefix를 붙인다면 최종 `ToolDefinition.name()`을 기준으로 설정하세요.
Wildcard는 없으며 일반 유형인 `PII`도 명시적으로 나열해야 합니다.

감싼 콜백은 같은 privacy starter로 설정한 `ChatClient`에만 등록하세요. 보호하지
않는 클라이언트에는 원본 콜백을 사용합니다. 요청 컨텍스트가 없거나 비활성 상태면
위임 대상을 실행하기 전에 실패합니다. 사용자 정의 `ToolCallingManager`와
`ToolCallbackResolver` 실행은 지원 범위 밖입니다. Spring AI 도구 option을 통해
콜백 또는 감싼 provider를 명시적으로 등록하세요.

설정에 없는 도구에는 토큰만 전달하며, 도구별 원문 공개 목록을 비워 두는 것은 잘못된
설정입니다.

도구 wrapper는 비어 있지 않은 도구 입력이 유효한 JSON일 것을 요구하고, 선택적으로 원문을
공개하기 전에 JSON 문자열, 탐지된 숫자 scalar와 map key를 보호합니다. 위임 대상을
실행하기 전에 `ToolContext`에서 내부 세션 객체를 제거합니다. 모든 결과는 유효한
JSON이면 재귀적으로 해석하고, 그렇지 않은 임의의 평문이면 그대로 처리한 뒤 다시
토큰화합니다. 권한 있는 위임 대상에서 발생한 실패는 원형대로 전파하여 호스트
애플리케이션의 예외 유형, 원인, 재시도, 대체 처리와 진단 정보를 보존합니다. 원문을
받은 도구의 예외에는 원문 개인정보가 포함될 수 있으므로 호스트가 예외 처리와 로그를
보호해야 합니다. 라이브러리는 애플리케이션 소유의 실패를 잡아 다른 예외로 교체하지
않습니다. 개인정보 보호 wrapper 자체에서 발생한 실패는 안전하고 유형이 지정된
가드레일 실패로 유지됩니다.

## 출력 정책과 스트리밍

```yaml
spring:
  ai:
    privacy:
      enabled: true
      output:
        enabled: true
        action: tokenize
```

`TOKENIZE`는 요청 안에서 같은 값을 식별할 수 있게 유지하고, `REDACT`는 되돌릴 수
없는 유형 label을 출력하며, `BLOCK`은 `PrivacyOutputBlockedException`을 던집니다.
출력 보호는 일반 모델 응답, 도구 호출 인자와 `returnDirect` 결과에 적용됩니다.

### `returnDirect` 도구 흐름

`returnDirect`는 도구 호출 뒤 Spring AI가 모델을 다시 호출할지를 제어합니다. 이
라이브러리의 개인정보 보호 경계를 켜거나 끄는 설정은 아닙니다. 도구 wrapper는 위임 대상
도구의 `returnDirect` metadata를 변경하지 않고 복사합니다.

| `returnDirect` | 결과 경로 | 사용 시점 |
| --- | --- | --- |
| `false` | 일반 도구 결과를 다시 토큰화해 모델에 반환합니다. 모델은 결과를 해석하거나 다른 도구를 호출하거나 최종 답변을 작성할 수 있습니다. | 모델 또는 에이전트가 처리를 계속해야 할 때 |
| `true` | 도구 결과를 다시 토큰화합니다. 해당 응답에서 실제 선택된 콜백이 모두 direct이면 Spring AI가 최종 `returnDirect` generation으로 반환하고, 활성화된 출력 정책은 애플리케이션 경계에서 적용됩니다. | 도구 결과가 그대로 표시할 수 있거나 최종 응답으로 사용하려는 값일 때 |

하나의 클라이언트에 두 종류를 모두 등록해도 됩니다. Spring AI는 한 응답에서 실제로
선택된 콜백이 모두 `returnDirect = true`일 때만 직접 반환 경로를 선택합니다.
그렇지 않으면 보호된 모든
결과가 모델로 돌아갑니다. 두 종류의 도구를 함께 등록하는 것 자체는 허용됩니다. 출력
보호가 비활성화되어도 최종 직접 반환 결과에는 안전을 위한 토큰화가 유지됩니다. 출력
보호가 활성화되면 설정한 출력 동작을 애플리케이션 경계에서 적용합니다.

### 최종 모델 출력 검사

`output.enabled`는 모델이 생성한 최종 응답을 검사할지를 제어하는 별도의
선택입니다. 입력, 모델 경계 또는 도구 결과 보호를 비활성화하지 않습니다.

| `output.enabled` | 최종 모델 출력 검사 | 전달 방식 |
| --- | --- | --- |
| `false` | 아니요 | 모델이 생성하는 텍스트를 점진적으로 전달합니다. 입력, 모델 경계, 일반 도구 결과 보호와 안전을 위한 `returnDirect` 토큰화는 계속 적용됩니다. |
| `true` | 예 | 완성된 논리 응답을 각각 버퍼링하고 `TOKENIZE`, `REDACT` 또는 `BLOCK`을 적용한 뒤 보호된 frame을 다시 전달합니다. |

출력 보호가 활성화되어도 Spring AI stream API는 사용할 수 있지만, 보호된 텍스트가
모델 token 단위로 전달되지는 않습니다. 실시간으로 점진적인 출력이 필요하다면 출력
보호를 비활성화하고 최종 모델 출력의 개인정보 보호를 애플리케이션에서 책임지세요.

`response-inspection.*` 상한은 `output.enabled`와 독립적입니다. 도구가 등록되어
있으면 중간 모델 frame을 보호합니다. 출력 보호가 활성화되면 스트리밍하지 않는 최종
응답에도 문자와 미디어 상한을 적용합니다. 도구를 등록하지 않고 출력 보호도
비활성화했다면 일반 모델 응답의 상한은 애플리케이션 정책으로 남습니다.

스트리밍하지 않는 개인정보 처리에는 provider와 무관한 다음 절대 상한이 적용됩니다.

| 범위 | 최대치 |
| --- | ---: |
| 텍스트 또는 구조화된 payload 하나 | 1,000,000자 |
| 누적 정규 분석기 입력 | 1,000,000자 |
| 해석된 JSON 문자열 또는 property 이름 하나 | 250,000자 |
| JSON 숫자 표기 하나 | 1,000자 |
| 지수를 전개한 숫자 값 하나 | 4,096자 |
| Property 이름을 포함한 JSON node | 100,000개 |
| JSON 중첩 단계 | 128 |
| 한 번의 전체 분석에서 반환할 분석기 span | 100,000개 |
| 변환으로 달라진 출력 | 8,000,000자 |

문자 수 상한은 byte나 모델 token이 아니라 Java `String`의 UTF-16 code unit를
기준으로 합니다. JSON scalar와 평문 payload는 분할하지 않으므로 모든 분석기는
적용되는 최대 크기를 처리할 수 있어야 합니다. 그렇지 않으면 애플리케이션이 더 작은
상한을 강제해야 합니다. 애플리케이션은 이 상한을 낮출 수는 있지만 높일 수는
없습니다. 상한을 넘으면 위임 대상 실행 또는 결과 전달 전에
`PAYLOAD_LIMIT_EXCEEDED`가 발생합니다.

구조화된 JSON이 필요한 경계에서만 잘못된 JSON을 안전하게 차단합니다. 임의의 도구
결과와 일반 message가 유효한 JSON이 아니면 평문 보호를 적용합니다. 이런 경계에서
문자 그대로의 `\uXXXX` 문자열은 평문으로 취급합니다.

스트리밍 보호가 활성화되면 완성된 논리 응답 선택지를 각각 버퍼링한 뒤 다시
전달합니다. 지원되는 `reasoningContent`와 `thinking` 텍스트에도 같은 정책을 적용하며,
Google과 Anthropic이 사고 과정으로 표시한 콘텐츠는 일반 답변과 분리합니다. 내용을
해석하지 않는 서명과 알 수 없는 유형의 metadata는 보존합니다. 추가 field를 안전하게
복사할 수 없는 미지원 assistant 하위 클래스는 안전하게 차단합니다.

## core 직접 사용

직접 호출하는 코드도 같은 명시적 세션 모델을 사용합니다.

```java
try (PrivacySession session = privacyService.openSession()) {
    PiiTokenizationResult result = privacyService.analyzeAndTokenize(
            session.handle(), sourceText);
    String protectedText = result.tokenizedText();
    List<ResolvedPiiSpan> spans = result.analysis().spans();
}
```

허가된 경계에서 원문이 필요할 때는 엔티티 유형 범위를 지정하는 `detokenize`
overload를 사용하세요. 매핑이 필요한 API는 없거나 알 수 없거나 이미 닫힌 handle을
거부합니다.

## 테스트 지원

```gradle
dependencies {
    testImplementation "io.github.ultramancode:spring-ai-privacy-guardrails-test:0.1.0"
}
```

```java
try (PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService)) {
    ChatModel model = probe.wrapModel(delegateModel);
    ToolCallback tool = probe.wrapTool(customerLookup, toolCallbackFactory);

    assertThatPrivacy(probe)
            .modelRequestsDoNotContainRawValues("Alice", "alice@example.com")
            .modelRequestsContainOpaqueToken("PERSON")
            .toolInputsContain("customerLookup", "Alice")
            .hasNoActivePrivacySessions();
}
```

테스트용 probe는 `clear()` 또는 `close()`를 호출할 때까지 캡처한 텍스트를
의도적으로 보존합니다.

## 영속성과 진단 경계

Advisor는 메모리와 검색한 문서를 모델에 보낼 때 만드는 사본을 보호합니다. 이미
저장된 `ChatMemory`, 벡터 저장소 데이터, 데이터베이스, 로그, 추적 정보, 응답
메타데이터, 알 수 없는 provider/애플리케이션 메타데이터 또는 텍스트가 아닌 미디어를
다시 쓰지는 않습니다. 이 데이터의 보호는 애플리케이션 책임입니다.

`PiiAnalyzerFailureObserver`에는 정제된 provider, code, phase와 시도 횟수만
전달됩니다. 분석기와 권한 있는 도구의 상세 진단 정보는 애플리케이션이 관리하는
보호된 저장 지점에서 수집해야 합니다.

### 유형별 개인정보 보호 실패

`PrivacyGuardrailException`은 개인정보 보호 라이브러리에서 발생한 실패를
나타냅니다. `code()`는 안정적이고 종류가 제한된 실패 유형이며, `phase()`는 실패가
발생한 개인정보 보호 작업을 나타냅니다. 모델 provider 예외와 애플리케이션 소유의
도구 위임 예외는 원형대로 전파되므로 `PrivacyFailureCode`가 부여되지 않습니다.

`PrivacyFailureCode`가 기준 목록이며 각 유형은 Javadoc에서 설명합니다. 단계는
`ANALYSIS`, `TOKENIZATION`, `REDACTION`, `DETOKENIZATION`, `SESSION`,
`OUTPUT_POLICY`, `TOOL_INPUT`, `TOOL_EXECUTION`, `TOOL_OUTPUT` 중 하나입니다.
`code()` 값은 실패 분류로 사용하고 모든 환경에 통용되는 재시도 권고로 해석하지
마세요.
재시도, 대체 처리와 사고 대응은 provider 계약과 부수 효과 모델에 따라 호스트
애플리케이션이 결정합니다.

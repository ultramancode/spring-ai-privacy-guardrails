# 설정과 사용법

[English](../configuration.md) | **한국어**

<!-- i18n-source: docs/configuration.md -->
<!-- i18n-source-sha256: 8d60ba69814540e60ba7ddeb415c913a43b1480d2d3deaac811a316e1e9731c2 -->

이 문서는 Spring AI Privacy Guardrails를 사용하는 애플리케이션을 위한 종합
참고 문서입니다. 기본 Spring Boot 스타터는 `core` 모듈과 Spring AI 통합 경계를
제공하며, 분석기별 Spring Boot 스타터는 별도의 개인정보 보호 정책을 정의하지 않고
해당 분석기 연동을 추가합니다. 선택적 Spring Security 스타터는 이와 별도의 경계로
사용자별 도구 권한 부여를 추가합니다.

## 스타터 선택

사용할 분석기에 맞는 스타터를 선택하세요.

| 스타터 | 의존성 | 용도 |
| --- | --- | --- |
| Presidio Spring Boot 스타터 | `io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.3.0` | 애플리케이션 고유 형식보다 다양한 PII 유형을 탐지할 때 사용합니다. 기본 Spring Boot 스타터, Presidio HTTP 연동과 조건부 상태 점검 기능을 포함합니다. |
| 기본 Spring Boot 스타터 | `io.github.ultramancode:spring-ai-privacy-guardrails-spring-boot-starter:0.3.0` | Regex 또는 사용자 정의 분석기용입니다. 별도의 분석기 연동은 포함하지 않습니다. |
| OpenNLP Spring Boot 스타터 | `io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.3.0` | 호환되는 NER 모델을 이미 보유한 애플리케이션을 위한 고급 JVM 전용 구성입니다. |

스타터 의존성만 추가해도 개인정보 보호 기능이 자동으로 켜지지는 않습니다. 전역
기능과 사용할 분석기를 명시적으로 활성화하세요. Presidio를
`http://localhost:5002` 외의 주소에서 실행한다면 `analyzer-url`도 설정해야 합니다.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      presidio:
        enabled: true
```

분석기는 조합해 사용할 수 있습니다. Presidio Spring Boot 스타터에는 기본 Spring Boot
스타터가 이미 포함되므로, Presidio와 OpenNLP를 함께 사용할 때는 기본 스타터를 별도로
추가하지 않고 두 분석기용 스타터를 선언합니다. 선택한 모든 분석기가 원문 텍스트를
받으므로 필요한 조합만 구성하세요.

### 선택적 Spring Security 스타터

`0.3.0`부터 현재 사용자에 따라 도구 발견과 실행을 제한하려면
`spring-ai-privacy-guardrails-spring-security-spring-boot-starter`를 추가합니다.
이 스타터는 기본 Privacy Guardrails 스타터를 포함하지만, 분석기별 연동이나 인증
인프라는 포함하지 않습니다.

기존 애플리케이션을 업그레이드할 때는 별도로 선언한 기본 스타터를 제거하고, 함께
사용하는 모든 Privacy Guardrails artifact를 버전 `0.3.0`으로 맞추세요.

Security 경계를 사용하려면 `AuthorizationManager<ToolAuthorizationContext>` Bean과
`spring.ai.privacy.enabled=true`, `spring.ai.privacy.security.enabled=true`가 모두
필요합니다. 전체 설정은 [Spring Security 도구 권한 부여](security.md)를 참고하세요.

## ChatClient에 보호 적용

스타터와 분석기를 활성화해도 모든 `ChatClient`에 보호가 자동으로 적용되지는
않습니다. 개인정보 보호가 필요한 `ChatClient.Builder`에
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

`PrivacyChatClientConfigurer`는 입력, 모델 호출, 도구 실행과 요청 수명주기에 필요한
개인정보 보호 경계를 구성하며, 출력 보호가 활성화되어 있으면 출력 경계도 함께
적용합니다.

선택적 Spring Security 도구 경계를 사용하는 `ChatClient`에는 대신
`PrivacySecurityChatClientConfigurer`를 적용하세요. 이 configurer는 기존 개인정보 보호 구성에
Security context Advisor를 추가합니다. 같은 builder에 두 configurer를 모두 적용하지
마세요.

이미 보호 구성이 적용된 `ChatClient`나 builder를 `mutate()` 또는 `clone()`한 경우에는
`PrivacyChatClientConfigurer`를 다시 적용하지 마세요.

```java
ChatClient protectedClient = privacyConfigurer.configure(builder).build();
ChatClient derivedClient = protectedClient.mutate().build();
```

다른 Spring AI Advisor와 함께 사용할 수 있습니다. 다만 별도의 Advisor가 개인정보
보호 경계 밖에서 입력·도구·응답 데이터를 추가하거나 변경하면, 그 내용은 자동으로
보호되지 않을 수 있습니다. 개인정보 보호 경계의 순서를 유지하려면 개별 구성 요소를
직접 조립하기보다 `PrivacyChatClientConfigurer`를 사용하세요.

도구 이름, 설명과 JSON 스키마도 모델에 전달되기 전에 개인정보를 검사합니다.

Spring AI의 표준 `UserMessage`, `SystemMessage`, `AssistantMessage`,
`ToolResponseMessage`와 `DeepSeekAssistantMessage`를 지원합니다. 그 밖의 모델
제공자 전용 `Message` 하위 클래스와 애플리케이션이 직접 구현한 `Message`는 오류로
처리합니다. 알 수 없는 필드의 개인정보가 보호되지 않은 채 전달되는 것을 막기
위해서입니다.

## 설정 속성

아래 표에서 전체 경로를 따로 적지 않은 속성은 모두 `spring.ai.privacy` 아래에
설정합니다.

| 속성 | 기본값 | 의미 |
| --- | --- | --- |
| `spring.ai.privacy.enabled` | `false` | 개인정보 보호 구성 요소를 활성화합니다. 보호할 `ChatClient.Builder`에는 `PrivacyChatClientConfigurer`를 별도로 적용해야 합니다. |
| `spring.ai.privacy.security.enabled` | `false` | 선택적 Spring Security 도구 경계를 활성화합니다. 전역 개인정보 보호 활성화, `AuthorizationManager<ToolAuthorizationContext>`와 보호할 builder의 `PrivacySecurityChatClientConfigurer`가 필요합니다. |
| `analysis.language` | `en` | 대소문자를 구분하지 않는 ASCII 언어 코드입니다. 소문자 정규형으로 분석기에 전달합니다. |
| `analysis.included-entity-types` | 비어 있음 | 탐지 허용 목록입니다. 신뢰할 수 있는 유형을 등록하는 설정은 아닙니다. |
| `analysis.minimum-score` | `0.0` | 전체 신뢰도 하한입니다. |
| `analysis.mode` | `UNION` | 탐지 근거 선택 전략입니다. |
| `analysis.primary-provider` | 미설정 | `PRIMARY` 및 `PRIMARY_WITH_FALLBACK` 모드와 `REQUIRE_PRIMARY` 실패 정책에서 사용할 주 분석기(primary provider)의 ID입니다. 대소문자를 구분하지 않습니다. |
| `analysis.supplemental-providers` | 비어 있음 | 주 분석기와 함께 실행해 탐지를 보완할 보조 분석기(supplemental provider)의 ID 목록입니다. |
| `analysis.failure-policy` | `REQUIRE_ALL` | 분석기 가용성에 대한 실패 정책입니다. |
| `analysis.provider-minimum-scores` | 비어 있음 | provider ID별 신뢰도 하한입니다. 전체 하한과 provider 하한 중 더 큰 값을 적용합니다. |
| `analysis.entity-aliases` | 비어 있음 | 분석기 엔티티 레이블을 정규 유형에 연결하는 명시적 매핑입니다. |
| `analysis.type-conflict-fallback` | `PII` | 해석하지 못한 중첩 유형 충돌에 사용할 유형입니다. |
| `output.enabled` | `false` | 출력 보호를 활성화합니다. |
| `output.action` | `TOKENIZE` | `TOKENIZE`, `REDACT` 또는 유형이 지정된 예외를 던지는 `BLOCK`을 선택합니다. |
| `output.block-exception-message` | `Response blocked by privacy guardrail.` | `BLOCK` 예외에 사용할 안전한 메시지입니다. |
| `response-inspection.max-stream-frames` | `1024` | 스트리밍 응답 하나에서 검사할 최대 프레임 수입니다. |
| `response-inspection.max-characters` | `1000000` | 호출 또는 스트리밍 응답 하나에서 검사하는 텍스트 기반 콘텐츠의 최대 누적 문자 수입니다. |
| `response-inspection.max-media-bytes` | `16777216` | 응답 하나에서 허용하는 미디어 데이터의 최대 누적 바이트 수입니다. |
| `response-inspection.stream-idle-timeout` | `60s` | 스트리밍 응답 프레임이 도착하지 않아도 기다리는 최대 시간입니다. |
| `tools.disclosures` | 비어 있음 | 특정 도구에 원문으로 복원해 전달할 엔티티 유형을 지정합니다. |
| `regex.enabled` | `false` | 애플리케이션이 제공한 Regex 규칙을 활성화합니다. |
| `regex.rules[].entity-type` | 규칙마다 필수 | 규칙의 일치 결과에 부여할 정규 엔티티 유형입니다. |
| `regex.rules[].pattern` | 규칙마다 필수 | 원문 텍스트에 적용할 Java 정규식입니다. |
| `regex.rules[].score` | `0.85` | 각 일치 결과에 부여할 신뢰도입니다. |
| `regex.rules[].capture-group` | `0` | 탐지 범위(span)로 사용할 정규식 캡처 그룹 번호입니다. `0`은 전체 일치를 의미합니다. |
| `regex.rules[].validator-id` | 미설정 | 일치 후보를 추가로 검사할 선택적 `RegexPiiMatchValidator`의 ID입니다. Spring Bean 이름이 아닙니다. |
| `presidio.enabled` | `false` | Presidio 분석기를 활성화합니다. |
| `presidio.analyzer-url` | `http://localhost:5002` | 분석기의 기본 URI입니다. |
| `presidio.timeout` | `5s` | 응답 본문 수신 완료까지 포함해 각 HTTP 요청 시도와 상태 확인에 적용되는 제한 시간입니다. |
| `presidio.max-retries` | `1` | 첫 시도 이후의 재시도 횟수입니다. |
| `presidio.retry-backoff` | `300ms` | 시도 사이의 대기 시간입니다. |
| `presidio.max-response-bytes` | `8388608` | 검증을 위해 보존할 Presidio 응답 본문의 최대 바이트 수입니다. |
| `presidio.headers` | 비어 있음 | Presidio 요청에 추가할 HTTP 헤더입니다. `Content-Type`은 라이브러리가 관리하므로 설정할 수 없습니다. |
| `opennlp.enabled` | `false` | 사용자가 제공한 로컬 OpenNLP 모델을 활성화합니다. |
| `opennlp.tokenizer-model` | 미설정 | 선택적인 tokenizer 모델 리소스입니다. 설정하지 않으면 `SimpleTokenizer`를 사용합니다. |
| `opennlp.entity-models` | 비어 있음 | 정규 엔티티 유형을 필수 name-finder 모델 리소스에 연결합니다. |

Regex `rules`, Presidio `headers`, OpenNLP `entity-models`의 기본값은 비어 있습니다.
Spring Boot 설정 메타데이터는 IDE 자동 완성을 제공합니다.
`analysis.language`는 영숫자 단어를 하나의 하이픈 또는 밑줄로 구분한
1~64자 ASCII 식별자를 받습니다. 대소문자를 구분하지 않으며 `core`는
소문자 정규형을 모든 분석기에 전달합니다. 공백, 문법에 없는 문장부호,
빈 구분자와 반복된 구분자는 자동으로 잘라내거나 보정하지 않고 거부합니다.

스타터 의존성만 추가하면 개인정보 보호 자동 설정은 비활성 상태이므로 분석기가 필요하지
않습니다. `spring.ai.privacy.enabled=true`로 활성화한 뒤에는 분석기를 하나 이상 구성해야
하며, 분석기가 없으면 애플리케이션 시작이 실패합니다.

이 라이브러리는 모델 호출 횟수, 도구 호출 횟수, 등록 가능한 도구 수 또는 에이전트
반복 횟수를 제한하지 않습니다. 호출 횟수, 비용, 동시 실행 수와 도구의 부수 효과에
대한 제한은 애플리케이션이나 오케스트레이션 프레임워크에서 관리해야 합니다.
`response-inspection.*` 설정은 이러한 실행 횟수를 제한하는 것이 아니라, 개인정보
보호를 위해 라이브러리가 검사하거나 보존하는 응답 콘텐츠의 크기와 스트리밍 범위를
제한합니다.

### 설정 오타 진단

기본 Spring Boot 스타터는 이 라이브러리가 정의한 고정 `spring.ai.privacy` 설정에서
알 수 없는 프로퍼티 이름을 발견하면 경고합니다. 이 경고는 애플리케이션 시작을
막지 않습니다. 진단은 `spring.ai.privacy.enabled`와 별개로 실행되므로, 최상위
`enabled`의 오타 때문에 개인정보 보호 자동 설정이 활성화되지 않은 경우에도
문제를 알려줄 수 있습니다.

예를 들어 다음처럼 `output.enabled`를 잘못 입력하면 애플리케이션은 계속 시작되지만
올바른 프로퍼티 이름을 제안하는 경고가 기록됩니다.

```yaml
spring:
  ai:
    privacy:
      output:
        enabledd: true
```

최상위 `spring.ai.privacy`에서는 분석기나 애플리케이션의 확장 설정과 충돌하지
않도록 `enabled`의 오타로 보이는 이름만 검사합니다. `output`,
`response-inspection`, `analysis`, `regex`, `tools`처럼 이 라이브러리가 정의한
고정 설정 영역에서는 알 수 없는 프로퍼티 이름을 경고합니다.

`analysis.provider-minimum-scores`, `analysis.entity-aliases`, `tools.disclosures`
아래의 동적 키와 `analysis.included-entity-types`,
`analysis.supplemental-providers`의 목록 항목은 진단 대상이 아닙니다. Presidio의
`headers`와 OpenNLP의 `entity-models`처럼 분석기별 설정에서 사용하는 동적 맵도
검사하지 않습니다.

올바른 프로퍼티 이름으로 보이는 후보가 하나뿐이면 경고 메시지에 해당 이름을
제안합니다. 진단 메시지에는 설정값, 자격 증명 또는 동적 맵 키를 포함하지 않습니다.

## 탐지와 해석

분석기는 원문에서 탐지한 범위와 유형, 신뢰도 등의 근거를 반환합니다. `core` 모듈은
이 결과에 엔티티 별칭, 탐지 허용 목록, 신뢰도 하한, 분석기 선택과 중첩 범위 해석
규칙을 적용합니다. 탐지 범위의 위치는 항상 원문 텍스트를 기준으로 합니다. Regex,
Presidio, OpenNLP와 Spring Bean으로 등록한 사용자 정의 `PiiAnalyzer`를 함께 사용할
수도 있습니다.

`UNION` 모드에서는 설정한 모든 분석기를 실행하고 탐지 결과를 병합합니다. 겹치는
범위 중 하나가 다른 범위를 완전히 포함하면 해당 범위의 유형을 유지합니다. 일부만
겹치는 경우 개인정보의 일부가 노출되지 않도록 범위를 하나로 합치며, 유형 충돌을
해결할 수 없으면 범용 개인정보 유형인 `PII`로 처리합니다.

분석기 실행 실패는 `analysis.failure-policy` 설정에 따라 처리합니다. `REQUIRE_ALL`은
분석기 하나라도 실패하면 요청 처리도 실패합니다. `REQUIRE_PRIMARY`는 주 분석기의 성공을
요구하지만 다른 분석기의 실패는 허용합니다. `ALLOW_PARTIAL`은 성공한 분석기의 탐지
결과만 사용하므로 보호 범위가 줄어들 수 있으며, 모든 분석기가 실패하면 요청도
실패합니다.

다음 설정은 Presidio와 애플리케이션 전용 Regex 분석기를 함께 실행하고, 기본 `UNION`
모드로 탐지 결과를 병합합니다.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      analysis:
        entity-aliases:
          US_SSN: NATIONAL_ID
      presidio:
        enabled: true
      regex:
        enabled: true
        rules:
          - entity-type: EMPLOYEE_ID
            pattern: "(?<![A-Za-z0-9_])EMP-[0-9]{4}(?![A-Za-z0-9_])"
            score: 0.90
```

`PRIMARY` 모드에서는 주 분석기(primary provider)와 보조 분석기(supplemental provider)만
실행하며, 그 외의 분석기가 구성되어 있으면 설정 오류로 처리합니다.
`PRIMARY_WITH_FALLBACK` 모드를 사용하려면 `ALLOW_PARTIAL` 실패 정책, 주 분석기,
그리고 하나 이상의 주 분석기가 아닌 분석기가 필요합니다. 보조 분석기는 주 분석기와
항상 함께 실행하고, 대체 분석기(fallback provider)는 주 분석기가 실패한 경우에만
실행합니다.

`analysis.provider-minimum-scores`에 provider ID를 추가해도 해당 분석기가 자동으로
등록되거나 활성화되지는 않습니다. 이 설정은 이미 등록된 분석기의 탐지 결과에
적용할 신뢰도 하한만 지정합니다. 따라서 위 예제에서 Regex 분석기는 Presidio와 함께
실행되어 `EMPLOYEE_ID`를 탐지합니다.

provider ID는 영문자와 숫자로 이루어진 구간을 단일 하이픈(`-`) 또는 밑줄(`_`)로
연결한 1~128자의 ASCII 식별자입니다. `core` 모듈은 provider ID의 대소문자를
구분하지 않고 대문자 형태로 정규화합니다. 허용되지 않은 문장부호나 공백,
잘못되거나 연속된 구분자, 중복된 provider ID는 거부합니다.

엔티티 레이블은 대문자 영문자와 숫자로 이루어진 구간을 단일 밑줄(`_`)로 연결한
1~128자의 ASCII 식별자입니다. 소문자, 공백, 하이픈(`-`), 허용되지 않은 문장부호와
빈 구간은 자동으로 보정하지 않고 거부합니다. 잘못된 설정값이나 분석기가 반환한
유효하지 않은 레이블은 오류로 처리합니다.

기본으로 인정하는 정규 엔티티 유형은 `PII`, `PERSON`,
`ORGANIZATION`, `LOCATION`, `EMAIL_ADDRESS`, `PHONE_NUMBER`, `NATIONAL_ID`,
`CREDIT_CARD`, `DATE_TIME`, `IP_ADDRESS`, `URL`, `IBAN_CODE`, `CRYPTO`, `NRP`,
`MEDICAL_LICENSE`입니다. 분석기별 별칭은 자동으로 등록되지 않습니다. 따라서
Presidio의 `US_SSN`처럼 특정 분석기, 모델, 국가 또는 애플리케이션에 종속된 레이블을
공통 정책 유형으로 사용하려면 `entity-aliases`를 통해 명시적으로 매핑해야 합니다.

```yaml
spring:
  ai:
    privacy:
      analysis:
        entity-aliases:
          US_SSN: NATIONAL_ID
          KR_RRN: NATIONAL_ID
```

형식은 올바르지만 기본 정규 엔티티 유형 목록에 없는 레이블은 `PII`로 처리됩니다. 탐지 허용 목록에서
`PII`를 제외했다면 해당 결과도 제거됩니다. 필요하면 허용된 정규 유형으로
매핑하세요. `PiiAnalyzer.trustedEntityTypes()`를 사용하면 로컬 분석기가 자신의 탐지
결과에서 신뢰할 엔티티 유형을 선언할 수 있습니다.

탐지 허용 목록에 엔티티 유형을 추가해도 해당 유형이 자동으로 신뢰 유형으로
등록되지는 않습니다. 또한 한 분석기가 선언한 신뢰 유형은 다른 분석기의 엔티티
레이블에 적용되지 않습니다. 엔티티 별칭 매핑과 명시적으로 등록한 신뢰 정규 유형은
특정 분석기에 한정되지 않고 모든 분석기 결과에 공통으로 적용됩니다.

애플리케이션에서 `PiiAnalyzer`를 직접 구현한 사용자 정의 분석기는 여러 요청에서
공유될 수 있으므로 스레드 안전(thread-safe)하고 재진입 가능하게 구현해야 합니다.
각 분석기는 고유한 provider ID를 제공해야 하며, 블로킹 작업에는 유한한 제한 시간을
적용하고 스레드 중단 요청도 적절히 처리해야 합니다.

구조화된 JSON에서는 속성 이름과 문자열 값, 숫자 값을 분석합니다. 비어 있거나
공백으로만 이루어진 속성 이름과 문자열 값은 분석하지 않습니다. 라이브러리는 분석할
각 항목을 서로 독립된 텍스트로 `PiiAnalyzer.analyzeSegments(...)`에 전달합니다.
따라서 한 값의 분석이 다른 값에 영향을 주지 않고, 각 결과의 오프셋도 해당 값을
기준으로 계산됩니다.

예를 들어 `{"name":"Alice","city":"Seoul"}`에서는 `name`, `Alice`, `city`,
`Seoul`이 각각 분석 대상이 됩니다. 기본 구현은 `analyze(...)`를 네 번 호출하지만,
이 프로젝트가 제공하는 Presidio와 OpenNLP 어댑터는 각 실행 환경에 맞게
`analyzeSegments(...)`를 구현합니다. 분석 대상의 양이 많으면 여러 묶음으로 나누어
순서대로 전달합니다. Presidio는 각 묶음을 REST 배열 요청 한 번으로 처리하므로, 위
예시는 요청 한 번으로 처리됩니다. OpenNLP는 토크나이저와 개체명 탐지기를
재사용하면서 각 텍스트를 로컬에서 분석합니다. 따라서 외부 요청 횟수와 처리 비용은
분석기 구현에 따라 달라집니다.

텍스트 배열을 받는 외부 분석 서비스를 사용하는 경우에는 `analyzeSegments(...)`를
재정의해 여러 텍스트를 한 번의 요청으로 처리할 수 있습니다. 재정의한 구현은 입력
순서에 맞춰 텍스트별 결과를 반환하고, 각 오프셋을 해당 텍스트를 기준으로 계산해야
합니다. 애플리케이션에서도 `PrivacyService.analyzeSegments(...)`를 직접 호출해 여러
텍스트를 같은 방식으로 분석할 수 있습니다.

한 번의 `PrivacyService.analyzeSegments(...)` 호출에는 최대 100,000개의 텍스트
(`PiiAnalyzer.MAX_ANALYSIS_SEGMENTS`)를 전달할 수 있으며, 전체 입력 길이는
`PrivacyService.MAX_TEXT_INPUT_CHARACTERS`를 초과할 수 없습니다. 반환할 수 있는
span의 총합은 최대 100,000개(`PiiAnalyzer.MAX_RESULT_SPANS`)입니다. Presidio와
OpenNLP 어댑터는 처리량과 결과 크기에 안전 한도를 적용합니다. `PiiAnalyzer`를 직접
구현하는 사용자 정의 분석기도 처리 중 생성하는 임시 데이터와 결과 크기에 적절한
한도를 적용해야 합니다.

## Regex 분석기

Regex 분석기는 모든 개인정보를 탐지하기 위한 용도가 아니라, 사번이나 고객번호처럼
형식이 명확한 애플리케이션 고유 식별자를 탐지하는 데 적합합니다. 정규식 패턴은
애플리케이션이 관리하는 신뢰된 설정으로 사용하고, 불필요하게 복잡한 패턴은 피하세요.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      regex:
        enabled: true
        rules:
          - entity-type: CUSTOMER_ID
            pattern: "(?<![A-Za-z0-9_])CUST-[0-9]{6}(?![A-Za-z0-9_])"
            score: 0.90
            capture-group: 0
            validator-id: customer-id-check
```

각 규칙에는 `entity-type`과 `pattern`이 필요합니다. `score` 기본값은 `0.85`,
`capture-group` 기본값은 `0`입니다. `validator-id`는 선택 사항이며 Spring Bean 이름이
아니라 애플리케이션이 제공한 `RegexPiiMatchValidator`의 `id()`가 반환하는 고정 ID를
참조합니다.

```java
@Bean
RegexPiiMatchValidator customerIdMatchValidator() {
    return new RegexPiiMatchValidator() {
        @Override
        public String id() {
            return "customer-id-check";
        }

        @Override
        public boolean isValid(String candidate) {
            return CustomerIds.hasValidChecksum(candidate);
        }
    };
}
```

ID는 소문자 ASCII 영문자와 숫자로 이루어진 구간을 하나의 하이픈으로 연결합니다.
애플리케이션을 시작할 때 ID를 확인하며, 빈 값이나 잘못된 형식, 알 수 없는 ID가
있거나 둘 이상의 검증기 Bean이 같은 ID를 사용하면 시작에 실패합니다. 여러 규칙이
같은 ID를 참조하는 것은 허용됩니다.

`isValid()`에는 `capture-group`이 선택한 일치 후보만 전달됩니다. `true`를 반환하면
기존 탐지 범위와 점수를 유지하고, `false`를 반환하면 해당 후보를 제외합니다. 실행 중
발생한 예외는 설정한 분석기 실패 정책에 따라 처리합니다.

`RegexPiiMatchValidator` 구현체는 여러 요청에서 공유되므로 스레드 안전하게 작성해야
합니다. 검증 대상 문자열에는 개인정보 원문이 포함될 수 있으니 로그나 예외 메시지에
남기거나 장기간 보관하지 마세요. `validator-id`를 설정하지 않으면 정규식에 일치한 후보를
별도의 검증 없이 사용합니다.

## Presidio 분석기

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

`analyzer-url`에는 Presidio 서버의 기본 HTTP(S) 주소를 설정합니다. 인증 정보가
필요한 경우 URL에 포함하지 말고 `headers`와 애플리케이션의 비밀값 관리 기능을
사용하세요.

구조화된 JSON의 분석 대상 텍스트는 전체 길이가 커지면 여러 묶음으로 나누어
처리합니다. Presidio 어댑터는 각 묶음을 REST 배열 요청 한 번에 전송하고, 결과와
오프셋은 텍스트별로 분리합니다. 따라서 텍스트 간 독립성을 유지하면서 네트워크 요청
횟수를 줄일 수 있습니다.

`analyzeSegments(...)`가 사용하는 REST 배열 입력은 Presidio Analyzer 2.2.361
이상에서 지원되며, CI에서는 2.2.364로 검증합니다.

`timeout`은 Presidio 응답 본문을 모두 받을 때까지의 HTTP 요청 시간에 적용됩니다.
전송 실패, `timeout` 초과, HTTP 408/429와 5xx 응답은 `max-retries` 설정에 따라
재시도하며, 그 밖의 4xx 응답은 즉시 실패합니다.

`max-response-bytes`는 Presidio 응답 본문의 최대 크기이며 기본값은 8 MiB입니다.
이 값을 늘리면 응답 처리에 필요한 최대 메모리도 증가할 수 있습니다. 크기 제한을
초과하거나 올바르게 처리할 수 없는 응답은 분석 실패로 처리합니다.

Spring Boot의 상태 점검 기능을 사용하는 경우 Presidio 서비스의 상태도 함께 확인할
수 있습니다.

로컬 Docker 구성은 [samples/presidio](https://github.com/ultramancode/spring-ai-privacy-guardrails/tree/main/samples/presidio)에
있습니다.

## OpenNLP 분석기 (JVM 전용, 선택 사항)

이 구성은 호환되는 OpenNLP NER 모델을 사용해 별도의 외부 분석 서비스 없이
애플리케이션과 같은 JVM에서 개인정보를 분석하려는 경우에 적합합니다. OpenNLP NER
모델은 라이브러리에 포함되지 않으므로 사용할 모델 파일을 별도로 준비해야 합니다.

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

`tokenizer-model`을 지정하지 않으면 OpenNLP `SimpleTokenizer`를 사용합니다.
`entity-models`에는 각 엔티티 유형에 사용할 NER 모델 파일의 위치를 설정합니다.
사용하는 NER 모델과 토큰화 방식이 맞지 않으면 탐지 품질이 달라질 수 있으므로 실제
데이터에 맞게 검증해야 합니다.

`analyzeSegments(...)`는 한 번의 호출에서 토크나이저와 개체명 탐지기를 재사용해
여러 텍스트를 각각 독립적으로 분석합니다. OpenNLP 분석은 애플리케이션 내부에서
수행되며 외부 분석 서비스는 호출하지 않습니다.

OpenNLP 연동은 기존 NER 모델을 활용하려는 애플리케이션을 위한 선택적 구성으로,
범용 개인정보 탐지의 기본 방식으로 권장하지는 않습니다.

[실행 가능한 샘플 가이드](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/samples/spring-ai-demo/README.ko.md#선택적-jvm-전용-opennlp-adapter)에서
모델 준비, 설정 및 실제 연동 테스트 방법을 확인할 수 있습니다.

## 도구별 원문 공개

도구에는 기본적으로 개인정보 원문을 전달하지 않습니다. 특정 도구가 실제 값이 필요한
경우에만 `tools.disclosures`로 해당 도구에 공개할 엔티티 유형을 지정하세요.

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

위 설정에서는 `customerLookup` 도구에만 `CUSTOMER_ID`의 원문을 전달합니다.
공개하도록 지정하지 않은 엔티티 유형과 `tools.disclosures`에 등록되지 않은 도구에는
보호된 값이 그대로 전달됩니다.

개인정보 보호가 적용된 `ChatClient`에서 사용하는 `ToolCallback`은
`PrivacyToolCallbackFactory`로 감싸서 등록하세요.

```java
List<ToolCallback> protectedTools = toolCallbackFactory.wrapAll(
        List.of(customerLookup, knowledgeSearch));

ChatClient chatClient = privacyConfigurer.configure(ChatClient.builder(chatModel)
        .defaultTools(protectedTools.toArray(ToolCallback[]::new)))
        .build();
```

MCP처럼 실행 중에 도구 목록이 달라질 수 있다면 현재 콜백 목록만 감싸지 말고
`ToolCallbackProvider` 자체를 감싸세요. 하나는 `wrapProvider(...)`로, 여러 개는
`wrapProviders(...)`로 감싸 하나의 보호된 `ToolCallbackProvider`로 결합할 수 있습니다.

```java
ToolCallbackProvider protectedTools = toolCallbackFactory.wrapProviders(
        mcpTools,
        localToolProvider
);

return privacyConfigurer.configure(builder)
        .defaultTools(protectedTools)
        .build();
```

`tools.disclosures`의 도구 이름은 대소문자를 구분하며 최종
`ToolDefinition.name()`과 일치해야 합니다. 동적 provider가 도구 이름에 접두사를
추가한다면 변경된 최종 이름을 사용하세요. 와일드카드는 지원하지 않으며 `PII`를 포함해
원문 공개가 필요한 엔티티 유형은 모두 명시적으로 지정해야 합니다. 공개할 유형이
없다면 해당 도구를 `tools.disclosures`에 등록하지 마세요.

도구가 호출되기 전에 입력을 검사하고, 해당 도구에 허용된 엔티티 유형만 원문으로
복원합니다. 도구 실행 결과도 다시 검사하여 모델에 전달되기 전에 개인정보를
보호합니다.

원문 공개가 허용된 도구는 실제 개인정보를 전달받을 수 있으므로 도구 구현의 예외
메시지나 로그에 개인정보가 남지 않도록 주의하세요.

선택적 Spring Security 통합을 활성화하면 도구 권한을 이 공개 정책보다 먼저
확인합니다. 권한 정책은 현재 사용자가 도구를 발견하거나 실행할 수 있는지 결정하고,
`tools.disclosures`는 권한이 허용된 도구가 어떤 엔티티 유형을 받을 수 있는지 계속
결정합니다. 자세한 내용은
[Spring Security 도구 권한 부여](security.md#보장-범위)를 참고하세요.

이 기능은 Spring AI의 표준 `ToolCallback`과 `ToolCallbackProvider` 등록 경로를
대상으로 합니다. 사용자 정의 `ToolCallingManager`나 `ToolCallbackResolver`를 통한
실행은 자동 개인정보 보호 경계에 포함되지 않습니다. 선택적 Security 통합에서 사용자
정의 manager를 사용하려면 `SpringSecurityToolBoundary`를 명시적으로 제공해야 하며,
delegate가 보호된 prompt에 전달된 개인정보 보호 래퍼 콜백을 실행해야 합니다.

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

출력 보호를 활성화하면 모델이나 도구에서 애플리케이션으로 반환되는 개인정보에
설정한 정책을 적용합니다.

- `TOKENIZE`는 같은 요청 안에서 동일한 개인정보를 일관된 토큰으로 치환합니다.
- `REDACT`는 개인정보를 원문으로 복원할 수 없는 유형 마커로 대체합니다.
- `BLOCK`은 개인정보가 포함된 출력을 차단하고 `PrivacyOutputBlockedException`을
  발생시킵니다.

출력 보호는 일반 모델 응답, 도구 호출 인자와 `returnDirect` 결과에 적용됩니다.

### `returnDirect` 도구 흐름

`returnDirect`는 Spring AI에서 도구 실행 후 모델을 다시 호출할지 결정하는
설정입니다. `returnDirect=true`인 도구는 실행 결과를 모델에 다시 전달하지 않고
최종 응답으로 직접 반환할 수 있습니다. 개인정보 보호 기능 자체를 켜거나 끄는
설정은 아닙니다.

| `returnDirect` | 동작 | 사용 시점 |
| --- | --- | --- |
| `false` | 도구 결과를 보호한 뒤 모델에 다시 전달합니다. 모델은 결과를 바탕으로 답변을 생성하거나 다른 도구를 호출할 수 있습니다. | 모델이 도구 결과를 계속 처리해야 할 때 |
| `true` | 도구 결과를 보호한 뒤 최종 응답으로 직접 반환할 수 있습니다. | 도구 결과 자체를 최종 응답으로 사용할 때 |

한 `ChatClient`에 두 종류의 도구를 함께 등록할 수 있습니다. 한 번의 모델 응답에서
선택된 도구가 모두 `returnDirect = true`인 경우에만 Spring AI가 모델을 다시
호출하지 않고 결과를 직접 반환합니다. 그렇지 않으면 도구 결과는 모델로 다시
전달됩니다.

`output.enabled=false`인 경우에도 `returnDirect`로 직접 반환되는 도구 결과의
개인정보는 토큰화된 상태를 유지합니다. 출력 보호를 활성화하면 최종 결과에
`TOKENIZE`, `REDACT` 또는 `BLOCK` 정책을 적용합니다.

### 최종 모델 출력 검사

`output.enabled`는 모델이 생성한 최종 응답에 출력 정책을 적용할지를 결정합니다.
이 설정을 비활성화해도 입력, 모델 호출 경계와 도구 결과에 대한 개인정보 보호는 계속
적용됩니다.

| `output.enabled` | 최종 모델 출력 검사 | 전달 방식 |
| --- | --- | --- |
| `false` | 하지 않음 | 최종 모델 출력을 별도로 검사하지 않습니다. 스트리밍 호출에서는 모델이 생성하는 텍스트를 실시간으로 전달할 수 있습니다. 다른 개인정보 보호 경계와 `returnDirect` 결과의 안전한 토큰화는 계속 적용됩니다. |
| `true` | 적용 | 완성된 응답을 검사한 뒤 `TOKENIZE`, `REDACT` 또는 `BLOCK` 정책을 적용하여 전달합니다. |

출력 보호가 활성화되어도 Spring AI의 스트리밍 API를 사용할 수 있지만, 개인정보를
검사하기 위해 완성된 응답을 먼저 버퍼링합니다. 따라서 모델 응답이 생성되는 즉시
전달되는 실시간 스트리밍은 사용할 수 없습니다. 실시간 스트리밍이 반드시 필요하다면
`output.enabled=false`로 두고 최종 모델 출력의 개인정보 보호를 애플리케이션에서
처리해야 합니다.

`response-inspection.*` 설정은 `output.enabled`와 별개입니다. 이 설정들은 도구 호출
과정의 중간 응답 등 라이브러리가 검사해야 하는 콘텐츠의 크기와 스트리밍 범위를
제한합니다. 출력 보호가 활성화되면 `response-inspection.max-characters`와
`response-inspection.max-media-bytes` 제한을 최종 응답에도 적용합니다. 미디어
제한은 데이터 크기만 확인하며, 이미지나 오디오 내용에서 개인정보를 탐지하지는
않습니다.

설정으로 변경할 수 없는 내부 안전 상한도 있습니다. 지나치게 크거나 복잡한 입력으로
인한 메모리 사용을 제한하기 위한 값이며, 적용 위치와 계산 기준은 아래 표와 같습니다.

| 제한 적용 지점 | 측정 대상 | 최대치 |
| --- | --- | ---: |
| Spring AI 경계 | JSON 파싱 또는 평문 처리 전의 전체 페이로드 길이(UTF-16 코드 단위) | 1,000,000 |
| `core` 텍스트 처리 | 자동 분석하거나 호출자가 제공한 탐지 범위(span)로 처리하는 단일 텍스트 길이(UTF-16 코드 단위) | 1,000,000 |
| `core` 세그먼트 분석 | 한 번의 `analyzeSegments(...)` 호출에 전달된 모든 텍스트의 길이 합계(UTF-16 코드 단위) | 1,000,000 |
| `core` 값 트리 처리 | 단일 값 트리에 포함된 문자열, 맵 키 및 숫자 표현 길이의 합계(UTF-16 코드 단위) | 1,000,000 |
| JSON 또는 값 트리 처리 | 단일 JSON 문서 또는 `core` 값 트리의 노드 수 | 100,000 |
| JSON 또는 값 트리 처리 | 단일 JSON 문서 또는 `core` 값 트리의 중첩 깊이 | 128 |
| 개인정보 변환 | 한 번의 변환으로 생성되는 전체 출력 길이(UTF-16 코드 단위) | 8,000,000 |

텍스트 길이는 Java `String.length()` 기준입니다. 일반적인 글자는 대부분 UTF-16 코드
단위 1개로 계산하지만, 여러 이모지는 화면에 한 글자로 보여도 2개로 계산됩니다.

안전 상한을 초과하면 처리 또는 결과 전달 전에 `PAYLOAD_LIMIT_EXCEEDED` 오류가
발생합니다.

일반 메시지나 도구 결과가 JSON 형식이 아니더라도 평문으로 개인정보 보호를 적용할 수
있습니다. 구조화된 JSON이 반드시 필요한 경계에서만 잘못된 JSON을 거부합니다.

출력 보호가 활성화되면 라이브러리가 지원하는 추론 텍스트에도 동일한 개인정보 보호
정책을 적용합니다.

## `core` 모듈 직접 사용

이 절은 Spring AI 스타터를 통한 일반적인 사용이 아니라 `core` 모듈의
`PrivacyService` 메서드를 직접 호출하는 경우에만 해당합니다. 스타터를 사용하는
경우에는 아래의 세션이나 값 구조를 직접 관리할 필요가 없습니다.

`PrivacyService`를 직접 사용하는 경우에는 먼저 `PrivacySession`을 열고 해당 세션의
`handle`을 `analyzeAndTokenize()` 같은 메서드에 전달합니다.

```java
try (PrivacySession session = privacyService.openSession()) {
    PiiTokenizationResult result = privacyService.analyzeAndTokenize(
            session.handle(), sourceText);
    String protectedText = result.tokenizedText();
    List<ResolvedPiiSpan> spans = result.analysis().spans();
}
```

허가된 경계에서 원문 복원이 필요한 경우에는 공개할 엔티티 유형을 지정할 수 있는
`detokenize()` 메서드를 사용하세요. 존재하지 않거나 이미 종료된 세션 `handle`은 사용할
수 없습니다.

`tokenizeValueTree()`와 `detokenizeValueTree()` 메서드는 JSON과 호환되는 `Map`과 `List`
구조를 처리합니다. 값으로는 `null`, 불리언, 문자열과 `Byte`, `Short`, `Integer`,
`Long`, `BigInteger`, `BigDecimal`, 유한한 `Float`·`Double` 값을 사용할 수
있으며, `Map`의 키는 문자열이어야 합니다.

이 메서드들은 입력 객체를 직접 수정하지 않고 변환된 새로운 `Map`과 `List`를
반환합니다. 지원하지 않는 값이나 문자열이 아닌 `Map` 키, 순환 참조가 있으면
`TRANSFORMATION_CONFLICT`가 발생하며, 처리 가능한 크기 제한을 초과하면
`PAYLOAD_LIMIT_EXCEEDED`가 발생합니다.

일반 Java 객체나 Jackson `JsonNode`를 이 두 메서드에 직접 전달할 수는 없습니다.
직접 사용하는 경우에는 먼저 지원되는 `Map`/`List` 구조로 변환해야 합니다.

## 테스트 지원

테스트 모듈은 모델 요청과 도구 입력을 기록하는 유틸리티와 개인정보 보호 동작을 확인하는
검증 메서드를 제공합니다. 예제에 사용할 모델·도구·테스트 값은 애플리케이션에서
준비합니다.

```gradle
dependencies {
    testImplementation "io.github.ultramancode:spring-ai-privacy-guardrails-test:0.3.0"
}
```

```java
try (PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService)) {
    ChatModel model = probe.wrapModel(delegateModel);
    ToolCallback tool = probe.wrapTool(customerLookup, toolCallbackFactory);

    // 모델과 도구를 사용하는 테스트 대상 코드를 실행합니다.

    assertThatPrivacy(probe)
            .modelRequestsDoNotContainRawValues("Alice", "alice@example.com")
            .modelRequestsContainOpaqueToken("PERSON")
            .toolInputsContain("customerLookup", "Alice")
            .hasNoActivePrivacySessions();
}
```

`PrivacyTestProbe`는 감싼 모델 요청과 도구 입력을 기록해 이후 검증 메서드에서 확인할
수 있게 합니다. `"Alice"`, `"alice@example.com"`, `customerLookup`과
`delegateModel`은 예시이며, 실제 테스트에서는 애플리케이션의 테스트 데이터와
모델·도구를 사용합니다.

이 예제는 대표적인 검증 메서드를 보여줍니다. 전체 테스트 유틸리티와 검증 메서드는
각 클래스의 Javadoc과 IDE 자동완성에서 확인할 수 있습니다.

`PrivacyTestProbe`에는 테스트 원문이 기록될 수 있으므로 테스트 환경에서만
사용하세요. 위 예제처럼 try-with-resources로 사용하면 종료 시 기록이 정리됩니다.
테스트 도중 기록만 초기화하려면 `clear()`를 사용할 수 있습니다.

## 개인정보 보호 런타임 관측

애플리케이션은 선택적으로 `PrivacyEnforcementObserver` 타입의 Spring 빈을 하나 등록해
스타터가 관리하는 개인정보 보호 경계의 처리 결과를 받을 수 있습니다.

```java
@Bean
PrivacyEnforcementObserver privacyEnforcementObserver() {
    return event -> privacyMetrics.record(event.boundary(), event.outcome());
}
```

옵저버는 요청이 지원되는 개인정보 보호 경계를 지날 때마다 이벤트를 전달합니다.
`boundary()`는 이벤트가 발생한 지점을, `outcome()`은 해당 지점의 처리 결과를
나타냅니다. 따라서 요청 하나에서 여러 이벤트가 발생할 수 있습니다. 요청이 특정
경계를 지나지 않으면 해당 경계의 이벤트는 전달되지 않습니다. 경계 처리에 실패해도
별도의 빈 결과를 전달하지 않습니다.

`PrivacyEnforcementEvent`에는 의도적으로 `boundary()`와 `outcome()`만 포함됩니다.
개인정보 원문, 불투명 토큰, 엔티티 유형, 페이로드, 도구 이름, 요청 식별자와
상관관계 데이터는 전달하지 않습니다.

| 경계 (`boundary`) | 이벤트 발생 시점 | 결과 (`outcome`)와 의미 |
| --- | --- | --- |
| `MODEL` | 모델에 전달할 요청의 보호 처리가 완료된 뒤 | `PROTECTED` — 모델 요청 보호가 완료됨 |
| `TOOL_INPUT` | 도구 입력 처리가 완료된 뒤 | `DISCLOSED` — 정책에 따라 개인정보 원문을 하나 이상 복원함<br>`PROTECTED` — 도구에 전달하기 위해 복원된 개인정보 원문이 없음 |
| `TOOL_RESULT` | 도구 결과의 보호 처리가 완료된 뒤 | `PROTECTED` — 도구 결과 보호가 완료됨 |
| `APPLICATION_OUTPUT` | 최종 응답에 출력 보호를 적용할 때 | `PROTECTED` — 출력 보호가 완료되어 응답을 반환할 수 있음<br>`BLOCKED` — `BLOCK` 정책이 개인정보를 탐지해 응답 대신 차단 예외를 발생시킴 |

`PROTECTED`는 해당 경계의 보호 처리가 정상적으로 완료됐다는 뜻입니다. 개인정보가
있었는지 또는 실제 내용이 변경됐는지는 나타내지 않습니다. `TOOL_INPUT`에서
`PROTECTED`는 도구에 전달하기 위해 복원된 개인정보 원문이 없다는 뜻입니다.

스트리밍 애플리케이션 출력은 버퍼링된 전체 응답의 보호 처리가 끝난 뒤 이벤트를
한 번만 전달합니다. 옵저버 콜백은 여러 요청에서 동시에 실행될 수 있습니다. 스트리밍
출력에서는 Reactor가 응답 스트림을 처리하는 스레드에서 콜백을 실행할 수 있으므로,
요청을 처음 처리한 스레드와 같다고 가정하면 안 됩니다.

로그 기록이나 메트릭 갱신처럼 짧은 작업은 콜백에서 바로 수행해도 됩니다. 네트워크나
데이터베이스 I/O가 필요하면 작업을 별도 큐에 넣고 콜백은 즉시 반환하는 방식을
권장합니다. 옵저버 콜백에서 발생한 비치명적 오류는 무시되며 개인정보 보호 처리에는
영향을 주지 않습니다.

Spring Boot 스타터를 사용하면 등록한 옵저버 빈이 자동으로 연결됩니다. 스타터의 자동
구성 없이 `PrivacyModelBoundaryAdvisor`, `PrivacyToolCallbackFactory`,
`PrivacyOutputAdvisor`를 직접 생성하는 경우에는 옵저버를 생성자 인자로 전달해야
합니다. 옵저버 인자가 없는 기존 생성자를 사용하면 관측 이벤트는 전달되지 않습니다.

## 저장 데이터와 진단 정보

이 라이브러리는 모델에 전달되는 메모리와 검색 문서의 개인정보를 보호하지만, 이미
저장된 데이터 자체를 찾아서 수정하지는 않습니다. `ChatMemory`, 벡터 저장소,
데이터베이스, 로그와 추적 정보 등에 저장된 개인정보는 애플리케이션에서 별도로
보호해야 합니다.

라이브러리가 명시적으로 지원하는 추론 텍스트 외의 응답 메타데이터와 비텍스트
미디어는 자동으로 보호하지 않습니다.

`PiiAnalyzerFailureObserver`에는 개인정보나 상세 예외 내용 대신 provider ID, 실패
코드, 처리 단계와 시도 횟수처럼 정제된 실패 정보만 전달됩니다. 분석기나 원문 공개가
허용된 도구의 상세 진단 정보에는 개인정보가 포함될 수 있으므로, 애플리케이션의
안전한 로그 또는 모니터링 시스템에서 관리하세요.

### 개인정보 보호 오류 처리

`PrivacyGuardrailException`은 이 라이브러리의 개인정보 보호 처리 과정에서 발생한
오류를 나타냅니다. `code()`로 오류 유형을, `phase()`로 오류가 발생한 처리 단계를
확인할 수 있습니다.

`PrivacyFailureCode`는 이 라이브러리에서 발생한 오류에만 사용합니다. 모델 제공자나
애플리케이션 도구 자체에서 발생한 예외는 기존 예외를 그대로 전달합니다.

사용 가능한 오류 유형과 자세한 의미는 `PrivacyFailureCode`의 Javadoc에서 확인할 수
있습니다.

| `phase()` | 의미 |
| --- | --- |
| `ANALYSIS` | 개인정보 탐지 및 분석 |
| `TOKENIZATION` | 개인정보를 토큰으로 치환 |
| `REDACTION` | 개인정보를 마스킹 |
| `DETOKENIZATION` | 허용된 원문 복원 |
| `SESSION` | 개인정보 보호 세션 처리 |
| `OUTPUT_POLICY` | 최종 출력 정책 적용 |
| `TOOL_INPUT` | 도구 입력 검사 및 원문 공개 |
| `TOOL_EXECUTION` | 도구 실행 경계 검사 |
| `TOOL_OUTPUT` | 도구 실행 결과 보호 |

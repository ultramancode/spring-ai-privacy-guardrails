# 시작하기

[English](../getting-started.md) | **한국어**

<!-- i18n-source: docs/getting-started.md -->
<!-- i18n-source-sha256: a95496800cf193960f77713fb0ccc8a17be3af576fb43d0008b7343d8ae2e525 -->

이 가이드는 기존 Spring AI 애플리케이션에 Spring AI Privacy Guardrails를
추가해 모델, 도구, MCP 및 출력 경계에 개인정보 보호를 적용하는 기본 사용 방법을
설명합니다.

외부 분석기 서비스 없이 시작하려면 내장 Regex 분석기를 사용할 수 있습니다.
애플리케이션 고유 형식뿐 아니라 다양한 PII 유형을 탐지하려면, 오픈소스 PII
탐지·비식별화 프레임워크인 Presidio를 외부 분석 서비스로 연동할 수 있습니다.
JVM 내부에서 자체 NER 모델을 사용하려면 OpenNLP를, 애플리케이션에 특화된
탐지가 필요하면 사용자 정의 `PiiAnalyzer`를 사용할 수 있습니다.

애플리케이션에는 이미 `ChatModel`과 `ChatClient.Builder`가 구성되어 있다고
가정합니다.

클라우드 모델 자격 증명 없이 항상 같은 결과로 동작하는 데모를 실행하려면
[샘플 / 데모 가이드](sample.md)를 참고하세요.

## 사전 요구 사항

현재 릴리즈는 다음 환경에서 검증됩니다.

- Java 17
- Spring AI 2.0.1
- Spring Boot 4.1.1

## 1. 스타터 선택

사용할 분석기에 맞는 스타터를 선택합니다.

| 스타터 | 의존성 | 용도 |
| --- | --- | --- |
| 기본 Spring Boot 스타터 | `io.github.ultramancode:spring-ai-privacy-guardrails-spring-boot-starter:0.2.0` | 내장 Regex 규칙 또는 사용자 정의 분석기 |
| Presidio Spring Boot 스타터 | `io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.2.0` | Presidio를 외부 분석 서비스로 연동해 다양한 PII 유형 탐지 |
| OpenNLP Spring Boot 스타터 | `io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.2.0` | 애플리케이션이 제공하는 호환 모델을 이용한 JVM 내부 NER |

Presidio와 OpenNLP 스타터에는 기본 스타터가 이미 포함되어 있습니다.
스타터 의존성을 추가하는 것만으로 개인정보 보호나 분석기가 자동으로 활성화되지는
않습니다.

## 2. Regex로 빠르게 시작

외부 분석기 서비스 없이 개인정보 보호 경계를 가장 쉽게 확인하려면 내장
Regex 분석기를 사용할 수 있습니다.

기본 스타터를 추가합니다.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-spring-boot-starter:0.2.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

개인정보 보호를 활성화하고 애플리케이션 전용 식별자 규칙을 정의합니다.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      regex:
        enabled: true
        rules:
          - entity-type: EMPLOYEE_ID
            pattern: "(?<![A-Za-z0-9_])EMP-[0-9]{4}(?![A-Za-z0-9_])"
            score: 0.90
          - entity-type: CUSTOMER_ID
            pattern: "(?<![A-Za-z0-9_])CUST-[0-9]{6}(?![A-Za-z0-9_])"
            score: 0.90
```

이 규칙은 설정한 형식만 탐지합니다. Regex는 구조화된 애플리케이션 식별자에
적합하며, 일반적인 PII 전체를 탐지하기 위한 기능은 아닙니다.

`spring.ai.privacy.enabled=true`로 설정한 뒤에는 최소 하나의 분석기가
구성되어 있어야 하며, 그렇지 않으면 애플리케이션 시작이 실패합니다.

### 선택 사항: Regex 일치 결과 검증

형식이 일치한 뒤 체크섬이나 업무 규칙을 추가로 확인해야 하는 경우 Regex
규칙에 애플리케이션이 제공하는 검증기를 연결할 수 있습니다.

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

같은 `validator-id`를 반환하는 `RegexPiiMatchValidator`를 제공합니다.

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

여기서 `CustomerIds.hasValidChecksum(...)`은 예시로 든 애플리케이션 자체
검증 로직이며 라이브러리가 제공하는 메서드는 아닙니다. 정규식에 일치한 값 중
검증기가 `true`를 반환한 값만 개인정보 탐지 결과로 인정됩니다.
`RegexPiiMatchValidator`는 여러 요청에서 동시에 사용될 수 있으므로 동시 호출에
안전해야 합니다. 검증 대상 값 자체가 개인정보일 수 있으므로 로그에 남기거나
장기간 보관하지 마세요.

`validator-id`, `capture-group`, 시작 시 설정 검증과 분석기 실패 정책의 자세한
내용은 [설정과 사용법](configuration.md#regex-분석기)을 참고하세요.

## 3. ChatClient 보호

개인정보 보호와 분석기를 활성화하는 것만으로 모든 `ChatClient`가 자동으로
보호되지는 않습니다.

보호할 각 `ChatClient.Builder`에 스타터가 제공하는
`PrivacyChatClientConfigurer`를 적용합니다.

```java
@Bean
ChatClient privacyChatClient(
        ChatClient.Builder builder,
        PrivacyChatClientConfigurer privacyConfigurer
) {
    return privacyConfigurer.configure(builder).build();
}
```

구성한 `ChatClient`는 평소와 동일하게 사용합니다.

```java
String response = privacyChatClient.prompt()
        .user("Employee EMP-1234 requested customer CUST-123456.")
        .call()
        .content();
```

모델 호출 전에 탐지된 값은 요청 범위의 불투명 토큰으로 대체됩니다.
개념적으로 모델에는 다음과 같은 내용이 전달됩니다.

```text
Employee [[PII_EMPLOYEE_ID_<opaque>]] requested customer
[[PII_CUSTOMER_ID_<opaque>]].
```

원본 값은 모델에 전달되지 않으며, 토큰과 원본 값의 매핑은 요청별
`PrivacySession`을 통해 관리됩니다. 애플리케이션 로직은 불투명 토큰의 구체적인
형식에 의존하지 않아야 합니다.

`ChatModel`을 직접 호출하는 경로는 이 자동 보호 경계 밖에 있습니다.

모델 제공자의 로그에 의존하지 않고 모델에 실제로 보이는 내용을 확인하려면
결정론적으로 동작하는
[Privacy Boundary Inspector](sample.md)를 실행하세요.

## 4. 로컬 도구와 MCP 경계 보호

도구 원본 공개 정책은 기본적으로 거부 방식입니다. 도구를 등록하는 것만으로
원본 PII에 대한 접근 권한이 생기지는 않습니다.

각 도구에는 필요한 엔티티 유형의 원본 값만 공개하도록 설정합니다. 예를 들면
다음과 같습니다.

```yaml
spring:
  ai:
    privacy:
      tools:
        disclosures:
          customerLookup:
            - CUSTOMER_ID
```

로컬 도구와 MCP 도구 모두 `tools.disclosures`에 설정한 도구 이름은 대소문자를
구분하며 실제 `ToolDefinition.name()`과 정확히 일치해야 합니다. 위 정책에서는
`customerLookup`이 원본 `CUSTOMER_ID`를 받을 수 있고, 그 외 탐지된 엔티티
유형은 계속 보호됩니다.

### 로컬 ToolCallback

기존 Spring AI `ToolCallback`은 보호된 `ChatClient`에 등록하기 전에
`PrivacyToolCallbackFactory`로 감쌉니다.

```java
ToolCallback protectedCustomerLookup =
        privacyToolCallbackFactory.wrap(customerLookupToolCallback);
```

여기서 `customerLookupToolCallback`은 애플리케이션에 이미 존재하는 Spring AI
`ToolCallback`입니다.

감싼 `ToolCallback`도 일반적인 Spring AI 방식으로 등록할 수 있습니다.

```java
ChatClient toolClient = privacyConfigurer.configure(
        ChatClient.builder(chatModel)
                .defaultTools(protectedCustomerLookup)
).build();
```

도구 결과에서 탐지된 값은 모델에 다시 전달되거나 애플리케이션으로 직접
반환되기 전에 다시 보호됩니다.

### MCP와 동적 ToolCallbackProvider

MCP 통합에서는 발견된 도구가 `ToolCallbackProvider`를 통해 동적으로
제공되는 경우가 많습니다. 도구 목록이 실행 중에 바뀔 수 있다면 현재 도구 목록만
한 번 가져와 각각 감싸는 대신 `ToolCallbackProvider` 자체를
`wrapProvider(...)`로 감쌉니다.

```java
ToolCallbackProvider protectedMcpTools =
        privacyToolCallbackFactory.wrapProvider(mcpToolCallbackProvider);
```

감싼 `ToolCallbackProvider`는 일반적인 방식으로 등록합니다.

```java
ChatClient mcpClient = privacyConfigurer.configure(builder)
        .defaultTools(protectedMcpTools)
        .build();
```

MCP 도구 제공자가 이름에 접두사를 추가하는 경우에는 `tools.disclosures`에
접두사가 포함된 최종 도구 이름을 설정하세요.

`ToolCallbackProvider` 자체를 `wrapProvider(...)`로 감싸면 이후 도구 목록이
변경되어도 새로 반환되는 도구에 개인정보 보호가 계속 적용됩니다.

`ToolCallingManager`나 `ToolCallbackResolver`를 직접 구성한 별도 도구 호출
경로에는 개인정보 보호가 자동으로 적용되지 않습니다. 이러한 경로를 사용하는 경우에는
별도의 연동이 필요합니다.

선택적 원본 공개와 도구 결과 재보호가 실제 로컬 Streamable HTTP MCP
왕복에서도 적용되는 과정은
[샘플 / 데모 가이드](sample.md#mcp)를 참고하세요.

## 5. Presidio로 다양한 PII 유형 탐지

Presidio는 오픈소스 PII 탐지·비식별화 프레임워크입니다. 애플리케이션 고유 형식뿐 아니라 다양한 PII 유형을 탐지하려면 외부 Presidio
Analyzer 서비스를 연동하는 Presidio 스타터를 사용합니다.

Presidio 스타터에는 기본 Privacy Guardrails 스타터가 이미 포함되어 있으므로,
Presidio를 사용할 때는 기본 스타터를 별도로 추가할 필요가 없습니다. 다른 분석기도
함께 사용할 때만 해당 분석기 스타터를 추가하세요.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.2.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-presidio-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

Presidio를 활성화하고 `analyzer-url`을 지정합니다.

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

이 저장소를 복제한 경우 포함된 고정 버전의 로컬 Presidio 서비스를 다음
명령으로 실행할 수 있습니다.

```bash
docker compose -f samples/presidio/docker-compose.yml up -d --wait
```

어떤 분석기를 사용하더라도 `ChatClient` 보호에는 동일한
`PrivacyChatClientConfigurer`와 모델·도구 경계가 적용됩니다.

Regex와 Presidio를 함께 활성화할 수도 있습니다. 기본 `UNION` 모드에서는 구성한
분석기를 모두 실행한 뒤 탐지 결과를 합칩니다. 기본 실패 정책인 `REQUIRE_ALL`에서는
구성한 분석기 중 하나라도 실패하면 해당 요청도 실패합니다. 여러 분석기를 함께
사용할 때의 선택 방식과 실패 처리 방법은
[설정과 사용법](configuration.md#탐지와-해석)을 참고하세요.

## 6. JVM 내부 탐지에 OpenNLP 사용

호환되는 OpenNLP 모델을 애플리케이션 JVM 내부에서 사용해 탐지하려면
OpenNLP 스타터를 사용합니다.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.2.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-opennlp-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

`PERSON` 탐지를 위한 최소 구성은 다음과 같이 작성할 수 있습니다.

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
```

OpenNLP 모델 바이너리는 이 프로젝트에 포함되어 있지 않습니다.
애플리케이션이 모델 파일을 직접 관리하며 대상 환경에 맞게 모델 출처,
토크나이저 호환성 및 탐지 품질을 검증해야 합니다.

`tokenizer-model`은 선택 사항이며, 지정하지 않으면 OpenNLP의
`SimpleTokenizer`를 사용합니다.

재현 가능한 OpenNLP 스모크 테스트 구성은
[전체 샘플 가이드](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/samples/spring-ai-demo/README.ko.md)를
참고하세요.

## 7. 선택 사항: 최종 응답 보호

입력 및 도구 경계를 보호하더라도 최종 응답 검사가 자동으로 활성화되지는
않습니다.

애플리케이션에 반환되는 응답에도 최종 개인정보 검사가 필요하면 출력 보호
(output protection)를 활성화합니다.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      output:
        enabled: true
        action: tokenize
```

지원하는 출력 동작은 다음과 같습니다.

- `TOKENIZE`: 탐지된 개인정보를 요청 범위의 불투명 토큰으로 바꿉니다.
- `REDACT`: 탐지된 개인정보를 원문으로 복원할 수 없는 유형 마커로 대체합니다.
- `BLOCK`: 개인정보가 탐지되면 응답 전달을 중단하고
  `PrivacyOutputBlockedException`을 발생시킵니다.

스트리밍 API 자체는 계속 사용할 수 있지만, 출력 보호를 활성화하면 응답 조각을
생성되는 즉시 애플리케이션에 전달하지 않습니다. 라이브러리가 전체 응답을 먼저
모아 개인정보를 검사한 뒤 보호된 결과를 전달합니다. 따라서 여러 응답 조각에
나뉜 개인정보도 탐지할 수 있지만, 모델 응답이 생성되는 즉시 전달되는 실시간
스트리밍은 사용할 수 없습니다.

출력 정책과 `response-inspection` 제한의 자세한 내용은
[설정과 사용법](configuration.md#출력-정책과-스트리밍)을 참고하세요.

## 8. 사용자 정의 분석기

Regex, Presidio 또는 OpenNLP가 적합하지 않은 경우 애플리케이션이 사용자
정의 `PiiAnalyzer` Spring Bean을 제공할 수 있습니다.

사용자 정의 분석기도 Regex, Presidio, OpenNLP와 동일한 탐지·해석 흐름을 거쳐
모델, 도구 및 선택적 출력 보호 경계에 적용됩니다.

사용자 정의 `PiiAnalyzer`는 여러 요청에서 동시에 호출될 수 있으므로 동시 호출에
안전(thread-safe)하고 재진입 가능(reentrant)하게 구현해야 합니다. 외부 서비스
호출처럼 대기 시간이 발생하는 작업에는 적절한 제한 시간을 두고, 메모리 등 자원
사용량도 자체적으로 제한해야 합니다.

분석기 선택, 분석기 ID(provider ID), 엔티티 별칭, 신뢰도 하한 및 실패 정책은
[설정과 사용법](configuration.md#탐지와-해석)을 참고하세요.

## 보호 경계 참고 사항

보호된 `ChatClient`는 지원되는 메시지 내용을 모델에 전달하기 전에 보호합니다.
여기에는 모델로 전달되는 메모리와 RAG 컨텍스트도 포함됩니다.
하지만 채팅 메모리 저장소, 벡터 저장소, 데이터베이스, 로그 또는 트레이스에 이미
저장된 PII 자체를 자동으로 변경하지는 않습니다.

`ChatModel` 직접 호출과 구성된 `ChatClient` 경계 밖의 사용자 정의 실행
경로에는 보호가 자동으로 적용되지 않습니다.

## 다음 단계

- 전체 설정 속성과 API 참고 문서는 [설정과 사용법](configuration.md)을
  참고하세요.
- 개인정보 원문이나 페이로드를 노출하지 않고 경계 처리 결과만 관측하려면
  [개인정보 보호 런타임 관측](configuration.md#개인정보-보호-런타임-관측)을 참고하세요.
- 로컬 도구, RAG 및 Streamable HTTP MCP의 실제 실행 근거는
  [샘플 / 데모 가이드](sample.md)를 참고하세요.
- 모델, 도구, 세션 및 요청 수명주기 경계는
  [아키텍처](architecture.md)를 참고하세요.
- 재현 가능한 개인정보 보호 경계 검증 매트릭스와 벤치마크는
  [평가 및 벤치마크](evaluation.md)를 참고하세요.
- 운영 환경에 적용하기 전 [위협 모델](threat-model.md)을 검토하세요.

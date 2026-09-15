# 시작하기

[English](../getting-started.md) | **한국어**

<!-- i18n-source: docs/getting-started.md -->
<!-- i18n-source-sha256: c5ae0465da8b6da145353de4cff5005940c3d66c8f7f0a353195fb00a1017012 -->

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

외부 모델 API 키 없이 로컬 모델과 고정 예제로 보호 동작을 확인하려면
[샘플 / 데모 가이드](sample.md)를 참고하세요.

## 사전 요구 사항

현재 코드는 다음 환경에서 검증됩니다.

- Java 17
- Spring AI 2.0.1
- Spring Boot 4.1.1

## 1. 개인정보 보호 스타터 선택

사용할 분석기에 맞는 스타터를 선택합니다. 각 링크에서 해당 스타터의 Gradle·Maven
의존성을 확인할 수 있습니다.

| 스타터 | 용도 |
| --- | --- |
| [기본](#2-regex로-빠르게-시작) | 직접 정의한 Regex 규칙 또는 사용자 정의 분석기 |
| [Presidio](#5-presidio로-다양한-pii-유형-탐지) | 외부 Presidio 서비스를 연동해 다양한 유형의 개인정보를 탐지합니다. |
| [OpenNLP](#6-jvm-내부-탐지에-opennlp-사용) | 직접 준비한 OpenNLP 모델로 애플리케이션 내부에서 개인정보를 탐지합니다. 외부 분석 서비스가 필요하지 않습니다. |

Presidio와 OpenNLP 스타터에는 기본 스타터가 이미 포함되어 있습니다.
함께 사용하는 Privacy Guardrails 모듈은 같은 버전을 사용하세요.
스타터 의존성을 추가하는 것만으로 개인정보 보호나 분석기가 자동으로 활성화되지는
않습니다.

도구 권한 검사는 별도의 Spring Security 스타터를 사용합니다. 단독으로 사용하거나
개인정보 보호 스타터와 함께 사용할 수 있습니다. 의존성과 설정 방법은
[Spring Security 도구 권한](security.md)을 참고하세요.

## 2. Regex로 빠르게 시작

외부 분석기 서비스 없이 개인정보 보호 경계를 가장 쉽게 확인하려면 내장
Regex 분석기를 사용할 수 있습니다.

기본 스타터를 추가합니다.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-spring-boot-starter:0.3.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-spring-boot-starter</artifactId>
    <version>0.3.0</version>
</dependency>
```

Regex를 활성화하고 애플리케이션 전용 식별자 규칙을 정의합니다.

```yaml
spring:
  ai:
    privacy:
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

`PiiAnalyzer` Bean이 있으면 스타터가 `PrivacyService`와
`PrivacyChatClientConfigurer`를 제공합니다.

형식이 일치한 뒤 체크섬이나 업무 규칙을 추가로 확인해야 한다면
[사용자 정의 Regex 검증기](configuration.md#regex-분석기)를 연결할 수 있습니다.

## 3. ChatClient 보호

분석기를 구성하는 것만으로 모든 `ChatClient`가 자동으로
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

모델 호출 전에 탐지된 개인정보는 원문을 알 수 없는 대체 문자열(토큰)로 바뀝니다.
아래는 모델에 전달되는 내용을 보여주는 예시입니다. `<opaque>` 부분은 실제 요청에서
생성되는 값이며 요청마다 달라집니다.

```text
Employee [[PII_EMPLOYEE_ID_<opaque>]] requested customer
[[PII_CUSTOMER_ID_<opaque>]].
```

위 예시에서 탐지된 사번과 고객번호는 원문으로 모델에 전달되지 않습니다.
토큰과 원문의 대응 관계는 라이브러리가 요청별로 관리합니다. 애플리케이션에서는
토큰의 내부 문자열을 해석하거나 특정 형식에 의존하지 마세요.

`ChatModel`을 직접 호출하는 경로는 이 자동 보호 경계 밖에 있습니다.

모델에 전달되는 입력을 직접 확인하려면 고정 예제를 사용하는
[Privacy Boundary Inspector](sample.md)를 실행하세요.

## 4. 로컬 도구와 MCP 경계 보호

도구에 개인정보 보호를 적용하면, 탐지된 값은 기본적으로 토큰으로 전달됩니다.
고객 조회에 고객번호가 필요한 경우처럼 도구가 원문을 받아야 한다면,
`tools.disclosures`에 도구 이름과 공개할 개인정보 유형을 지정하세요.

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
구분하며 실제 `ToolDefinition.name()`과 정확히 일치해야 합니다. 위 설정을 적용하면
`customerLookup`에 고객번호(`CUSTOMER_ID`)만 원문으로 전달하고, 그 외에
탐지된 개인정보는 토큰으로 전달합니다.

### 로컬 ToolCallback

기존 Spring AI `ToolCallback`은 보호된 `ChatClient`에 등록하기 전에
`PrivacyToolCallbackFactory`로 감쌉니다.

```java
ToolCallback protectedCustomerLookup =
        privacyToolCallbackFactory.wrap(customerLookupToolCallback);
```

여기서 `customerLookupToolCallback`은 애플리케이션에 이미 존재하는 Spring AI
`ToolCallback`입니다.

감싼 `ToolCallback`을 클라이언트의 `defaultTools(...)`에 등록합니다.

```java
ChatClient toolClient = privacyConfigurer.configure(
        ChatClient.builder(chatModel)
                .defaultTools(protectedCustomerLookup)
).build();
```

도구 결과에서 탐지된 값은 모델에 다시 전달되거나 애플리케이션으로 직접
반환되기 전에 다시 보호됩니다.

### MCP와 동적 ToolCallbackProvider

MCP에서 제공하는 도구 목록은 `ToolCallbackProvider`로 등록할 수 있습니다.
`wrapProvider(...)`로 제공자 자체를 감싸면, 이후 요청에서 새로 제공되는 도구에도
개인정보 보호가 적용됩니다. 아래의 `mcpToolCallbackProvider`는 애플리케이션의
MCP 연동에서 제공받은 `ToolCallbackProvider`입니다.

```java
ToolCallbackProvider protectedMcpTools =
        privacyToolCallbackFactory.wrapProvider(mcpToolCallbackProvider);
```

감싼 `ToolCallbackProvider`를 클라이언트의 `defaultTools(...)`에 등록합니다.

```java
ChatClient mcpClient = privacyConfigurer.configure(builder)
        .defaultTools(protectedMcpTools)
        .build();
```

MCP 도구 제공자가 이름에 접두사를 추가하는 경우에는 `tools.disclosures`에
접두사가 포함된 최종 도구 이름을 설정하세요.

`ToolCallingManager`나 `ToolCallbackResolver`를 직접 구성한 별도 도구 호출
경로에는 개인정보 보호가 자동으로 적용되지 않습니다. 이러한 경로를 사용하는 경우에는
별도의 연동이 필요합니다.

선택적 원본 공개와 도구 결과 재보호가 실제 로컬 Streamable HTTP MCP
왕복에서도 적용되는 과정은
[샘플 / 데모 가이드](sample.md#mcp)를 참고하세요.

### Spring Security 도구 권한

현재 사용자의 권한에 따라 모델에 공개할 도구와 실행 가능한 도구를 제한하려면
Spring Security 스타터를 추가하고, 도구 권한 정책을
`AuthorizationManager<ToolAuthorizationContext>` Bean으로 등록합니다.
사용할 기능에 따라 다음 Factory Bean으로 `ChatClient`를 생성하세요.

- **도구 권한 검사만 사용:** `ToolAuthorizationChatClientFactory`를 사용합니다.
  개인정보 보호 스타터나 분석기는 필요하지 않습니다.
- **개인정보 보호와 함께 사용:** 개인정보 보호 스타터와 분석기를 구성한 뒤
  `PrivacySecurityChatClientFactory`를 사용합니다. 앞 단계에서 개인정보 보호를
  구성했다면 이 Factory로 클라이언트를 생성하세요.

두 Factory 모두 `builder(chatModel).build()`로 클라이언트를 생성합니다.
두 기능을 함께 사용하면 도구 실행 직전에 권한을 다시 확인한 뒤, 해당 도구에 허용된
개인정보만 원문으로 복원합니다.

스타터 의존성과 권한 정책, 클라이언트 구성 예시는
[Spring Security 도구 권한](security.md)을 참고하세요.

## 5. Presidio로 다양한 PII 유형 탐지

Presidio는 오픈소스 PII 탐지·비식별화 프레임워크입니다. 애플리케이션 고유 형식뿐 아니라 다양한 PII 유형을 탐지하려면 외부 Presidio
Analyzer 서비스를 연동하는 Presidio 스타터를 사용합니다.

Presidio 스타터에는 기본 Privacy Guardrails 스타터가 이미 포함되어 있으므로,
Presidio를 사용할 때는 기본 스타터를 별도로 추가할 필요가 없습니다. 다른 분석기도
함께 사용할 때만 해당 분석기 스타터를 추가하세요.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.3.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-presidio-spring-boot-starter</artifactId>
    <version>0.3.0</version>
</dependency>
```

Presidio를 활성화하고 `analyzer-url`을 지정합니다.

```yaml
spring:
  ai:
    privacy:
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

Presidio를 사용할 때도 [ChatClient 보호](#3-chatclient-보호)의
`PrivacyChatClientConfigurer` 구성이 필요합니다. 앞에서 이미 적용했다면
클라이언트를 다시 구성할 필요는 없습니다.

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
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.3.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-opennlp-spring-boot-starter</artifactId>
    <version>0.3.0</version>
</dependency>
```

`PERSON` 탐지를 위한 최소 구성은 다음과 같이 작성할 수 있습니다.

```yaml
spring:
  ai:
    privacy:
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

자세한 내용은 [출력 정책과 스트리밍](configuration.md#출력-정책과-스트리밍)과
[입력·응답 처리 제한](configuration.md#입력응답-처리-제한)을 참고하세요.

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
[설정과 사용법](configuration.md#탐지와-해석)을 참고하세요. 구현 시 지켜야 할 조건과
여러 텍스트의 분석 방법은 [사용자 정의 분석기](configuration.md#사용자-정의-분석기)를
참고하세요.

## 보호 경계 참고 사항

보호된 `ChatClient`는 지원되는 메시지 내용을 모델에 전달하기 전에 보호합니다.
여기에는 모델로 전달되는 메모리와 RAG 컨텍스트도 포함됩니다.
하지만 채팅 메모리 저장소, 벡터 저장소, 데이터베이스, 로그 또는 트레이스에 이미
저장된 PII 자체를 자동으로 변경하지는 않습니다.

## 다음 단계

- 전체 설정 속성과 API 참고 문서는 [설정과 사용법](configuration.md)을
  참고하세요.
- 개인정보 원문이나 페이로드를 노출하지 않고 경계 처리 결과만 관측하려면
  [개인정보 보호 런타임 관측](configuration.md#개인정보-보호-런타임-관측)을 참고하세요.
- 로컬 도구, RAG 및 Streamable HTTP MCP 시나리오에서 보호 동작을 확인하려면
  [샘플 / 데모 가이드](sample.md)를 참고하세요.
- 모델, 도구, 세션 및 요청 수명주기 경계는
  [아키텍처](architecture.md)를 참고하세요.
- 재현 가능한 개인정보 보호 경계 검증 매트릭스와 벤치마크는
  [평가 및 벤치마크](evaluation.md)를 참고하세요.
- 운영 환경에 적용하기 전 [위협 모델](threat-model.md)을 검토하세요.

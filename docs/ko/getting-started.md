---
description: >-
  기존 Spring AI 앱에 개인정보 보호, 도구 권한, 콘텐츠 검사를 설정하고 적용합니다.
---

# 시작하기

[English](../getting-started.md) | **한국어**

<!-- i18n-source: docs/getting-started.md -->
<!-- i18n-source-sha256: b673d07fc223a3c6a588bae1ded04010317db7a5f0700991f1ed4636fdee9671 -->

`GuardrailsChatClientFactory`로 `ChatClient`를 생성하고,
`GuardrailsPolicy`에서 애플리케이션에 필요한 기능을 선택합니다.

## 사전 요구 사항

Java 17 이상, Spring AI 2.0.0 이상, Spring Boot 4.0.0 이상을 사용합니다.
`ChatModel` 빈이 있는 Spring AI 애플리케이션에서 시작합니다.

<a id="add-the-client-starter"></a>

## 클라이언트 스타터 추가

모든 Guardrails 모듈을 같은 버전으로 맞추세요.

`build.gradle`에 추가합니다.

```gradle
repositories {
    mavenCentral()
}

dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-chat-client-spring-boot-starter:0.4.0"
}
```

Maven은 기본으로 Central을 사용합니다. `pom.xml`의 `dependencies`에 추가합니다.

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-chat-client-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

## 사용할 기능 선택

아래에서 필요한 기능을 설정한 뒤 클라이언트 정책에서 선택합니다.
한 가지 기능만 사용하거나 여러 기능을 조합할 수 있습니다.

| 기능 | 정책에서 선택 |
| --- | --- |
| [개인정보 보호](#privacy-protection) | `privacy()` |
| [도구 권한](#tool-authorization) | `toolAuthorization(true)` |
| [콘텐츠 검사](#content-inspection) | `inspection()` |

## 선택한 기능 설정

<a id="privacy-protection"></a>

### 개인정보 보호

개인정보 보호는 탐지된 개인정보를 모델에 전달하기 전에 토큰화하고,
도구별 원문 공개 범위를 제어하며 도구 결과를 다시 보호합니다.
애플리케이션에서 다루는 데이터에 맞춰 분석기를 선택합니다.

| 분석기 | 탐지 방식 |
| --- | --- |
| [Presidio](privacy.md#presidio-분석기) | Presidio 서비스를 통한 개인정보 탐지 |
| [OpenNLP](privacy.md#opennlp-분석기-jvm-전용-선택-사항) | 애플리케이션 JVM 내부의 모델로 개체명 인식 |
| [로컬 규칙](privacy.md#로컬-규칙과-프리셋) | 내장 규칙 또는 애플리케이션에서 정의한 패턴·검증 로직 |

[여러 분석기를 조합](privacy.md#탐지와-해석)할 수도 있습니다.
다른 탐지 모델이나 서비스를 연결하려면 `PiiAnalyzer`를 구현한
[사용자 정의 분석기](privacy.md#사용자-정의-분석기)를 등록합니다.

#### 내장 프리셋 구성

`src/main/resources/application.yml`에서 로컬 규칙의 내장 프리셋을 선택합니다.

<a id="enable-built-in-presets"></a>

```yaml
spring:
  ai:
    privacy:
      rules:
        enabled: true
        presets: [COMMON, KR]
```

`COMMON`은 이메일·카드번호, `KR`은 한국 휴대전화번호·주민등록번호의 지원 형식을 탐지합니다.
[내장 규칙의 지원 범위](privacy.md#내장-규칙)를 확인하세요.

<a id="tool-authorization"></a>

### 도구 권한

클라이언트 스타터와 함께 Security 스타터를 추가합니다.

Gradle:

```gradle
implementation "io.github.ultramancode:spring-ai-privacy-guardrails-spring-security-spring-boot-starter:0.4.0"
```

Maven:

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-spring-security-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

애플리케이션의 `@Configuration` 클래스에 권한 관리자를 등록합니다.
다음 예시는 `TOOL_USE` 권한이 있는 사용자에게만 등록된 도구를 허용합니다.

```java
@Bean
AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
    return AuthorityAuthorizationManager.hasAuthority("TOOL_USE");
}
```

도구 권한 검사는 애플리케이션의 Spring Security 설정에서 제공하는 요청의 `Authentication`을 사용합니다.
도구마다 다른 권한을 적용하려면 [권한 관리자 클래스](security.md#권한-정책-정의)를 구현합니다.

<a id="content-inspection"></a>

### 콘텐츠 검사

콘텐츠 검사는 모델 입력과 선택적 최종 출력을 검사합니다.
검사 정책은 발견한 내용을 바탕으로 처리를 허용하거나 차단합니다.
배포 환경과 검사 목적에 맞춰 검사기를 선택합니다.

| 검사기 | 검사 방식 |
| --- | --- |
| [로컬 ONNX](inspection.md#onnx-기반-로컬-콘텐츠-검사) | 애플리케이션 JVM 내부의 모델로 텍스트 분류 |
| [HTTP](inspection.md#http-guard-모델과-개인정보-처리) | HTTP로 제공되는 검사 모델 호출 |
| [규칙](#규칙-검사기-구성) | 명시적인 문구와 패턴 검사 |

애플리케이션 전용 검사 로직은 `ContentInspector`를 구현한
[사용자 정의 검사기](inspection.md#사용자-정의-검사기)로 등록합니다.

#### 규칙 검사기 구성

클라이언트 스타터와 함께 규칙 모듈을 추가합니다.

Gradle:

```gradle
implementation "io.github.ultramancode:spring-ai-privacy-guardrails-inspection-rules:0.4.0"
```

Maven:

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-inspection-rules</artifactId>
    <version>0.4.0</version>
</dependency>
```

애플리케이션의 `@Configuration` 클래스에 검사기를 등록합니다.
다음 예시는 대소문자를 구분하여 `ignore previous instructions` 문구가 포함된 텍스트를 차단합니다.

```java
@Bean
ContentInspector applicationRules() {
    InspectionRule blockedPhrase = InspectionRule.literal(
            "blocked-phrase", InspectionFinding.Category.PROMPT_INJECTION, "ignore previous instructions");
    return new RuleBasedContentInspector("application-rules", List.of(blockedPhrase));
}
```

<a id="create-a-protected-client"></a>

## 클라이언트 생성

설정한 기능을 `GuardrailsPolicy`에서 선택하고 `GuardrailsChatClientFactory`로
`ChatClient` 빈을 생성합니다. 다음은 세 기능을 조합한 예시입니다.
사용할 기능의 선택만 남기세요.

```java
@Configuration(proxyBeanMethods = false)
public class GuardrailsConfiguration {
    @Bean
    ChatClient chatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
        GuardrailsPolicy policy = GuardrailsPolicy.builder()
                .privacy()
                .toolAuthorization(true)
                .inspection()
                .build();
        return guardrailsChatClientFactory.builder(chatModel, policy)
                .build();
    }
}
```

예를 들어 콘텐츠 검사만 필요한 클라이언트는
`GuardrailsPolicy.builder().inspection().build()`를 사용합니다.

애플리케이션의 도구는 빌더의 `defaultTools(...)`나 요청의 `tools(...)`로 등록합니다.
`@Tool` 메서드가 있는 객체, `ToolCallback`, MCP `ToolCallbackProvider`에 선택한 정책이 적용됩니다.
도구에 개인정보 원문을 허용하려면 [원문 공개 정책](privacy.md#도구별-원문-공개)을 설정합니다.

분석기 설정, 도구 구현과 클라이언트 등록을 함께 살펴보려면
[고객 조회 샘플](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/v0.4.0/samples/spring-ai-demo/README.ko.md#샘플-코드-읽기)을 참고하세요.

<a id="call-and-check"></a>

## 클라이언트 호출

애플리케이션 서비스에서 클라이언트를 주입받아 호출합니다.

```java
@Service
public class ChatService {
    private final ChatClient chatClient;

    public ChatService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String answer(String message) {
        return this.chatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
```

## 선택한 기능 확인

이 페이지의 예제 설정을 다음과 같이 확인합니다.

| 기능 | 확인 방법 |
| --- | --- |
| 개인정보 보호 | `COMMON`·`KR` 프리셋을 적용하고 `alice@example.com`과 `010-2345-6789`를 입력합니다. 모델 입력에서 두 값이 불투명 토큰으로 치환됩니다. |
| 도구 권한 | 예제 권한 정책을 적용하고, 인증된 사용자 중 `TOOL_USE` 권한이 있는 사용자와 없는 사용자를 비교합니다. 권한이 있는 사용자에게만 등록한 도구의 공개·실행이 허용됩니다. |
| 콘텐츠 검사 | 규칙 예제에 지정한 차단 문구를 입력합니다. 해당 규칙에 따라 모델 호출 전에 `InspectionBlockedException`이 발생합니다. |

개인정보 보호 예제를 적용하면 이메일이 다음과 같이 토큰화됩니다.
토큰의 난수 부분은 `<opaque>`로 줄여 표시했습니다.

```text
입력:
Contact alice@example.com.

모델 입력:
Contact [PII_EMAIL_ADDRESS_1:<opaque>].
```

## 다음 단계

| 목적 | 가이드 |
| --- | --- |
| 사용자 정의 개인정보 탐지, 도구별 원문 공개, 최종 응답 보호 | [개인정보 보호](privacy.md) |
| 도구별 권한, Tool Search, 비동기 인증 정보 전달 | [도구 권한](security.md) |
| ONNX·HTTP 검사 모델, 출력 검사, 검사 정책 | [콘텐츠 검사](inspection.md) |
| 공통 정책과 클라이언트 사용자 정의 | [설정](configuration.md) |
| 예상과 다른 동작 진단 | [문제 해결](troubleshooting.md) |
| Local Tool, RAG, MCP, Security, Inspection 시나리오 | [샘플 가이드](sample.md) |

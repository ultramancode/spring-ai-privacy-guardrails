---
description: >-
  의존성, 공통 서비스와 ChatClient별 보호 정책을 구성합니다.
---

# 설정

[English](../configuration.md) | **한국어**

<!-- i18n-source: docs/configuration.md -->
<!-- i18n-source-sha256: 7f997e81c3436b3a02867ed357a88fa4b24f7475b84a5c3b0a2087ce7efbf7e3 -->

`GuardrailsChatClientFactory`로 클라이언트마다 개인정보 보호, 콘텐츠 검사,
도구 권한 검사를 선택합니다. 개인정보 보호를 선택하면 Spring AI를 통해 등록한
도구를 보호합니다. 요청별 도구와 MCP 도구에도 같은 정책을 적용합니다.

## 의존성과 구성 요소

Spring AI 모델 연동 모듈과 함께 클라이언트 스타터를 추가합니다.

```gradle
implementation "io.github.ultramancode:spring-ai-privacy-guardrails-chat-client-spring-boot-starter:0.4.0"
```

Guardrails 모듈은 같은 버전을 사용하세요. 이 스타터는 개인정보 보호와 검사 연동을
포함하지만 Spring Security, ONNX Runtime, Presidio와 검사 백엔드는 포함하지 않습니다.

추가 분석기로 개인정보를 탐지하려면 [Presidio](privacy.md#presidio-분석기)나
[OpenNLP](privacy.md#opennlp-분석기) 스타터를 추가합니다.

| 선택한 기능 | 필수 구성 요소 | 설정 |
| --- | --- | --- |
| 개인정보 보호 | `PrivacyService` | [분석기](privacy.md)를 구성합니다. |
| 콘텐츠 검사 | `InspectionService` | `ContentInspector` 또는 직접 구성한 서비스를 등록합니다. [콘텐츠 검사](inspection.md)를 참고하세요. |
| 도구 권한 검사 | 권한 연동 | [Security 스타터](security.md)와 `AuthorizationManager<ToolAuthorizationContext>` 빈을 추가합니다. |

필요한 구성 요소를 준비한 뒤 `GuardrailsPolicy`에서 사용할 기능을 선택합니다.

## 클라이언트별 정책

`GuardrailsPolicy`에서 사용할 기능과 출력 처리, 도구 원문 공개 범위를 선택합니다.
분석기는 스타터의 설정 프로퍼티로 구성합니다. HTTP·ONNX 검사기는 빈을 만들 때
모델 경로와 연결 설정을 전달합니다.

### 한 기능으로 시작

애플리케이션의 `@Configuration` 클래스에서 클라이언트에 개인정보 보호를 적용합니다.

```java
@Bean
ChatClient chatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
    GuardrailsPolicy policy = GuardrailsPolicy.builder()
            .privacy()
            .build();
    return guardrailsChatClientFactory.builder(chatModel, policy)
            .build();
}
```

서비스에서 호출하는 방법은 [클라이언트 예제](getting-started.md#call-and-check)를 참고하세요. 콘텐츠 검사만 필요하면
`GuardrailsPolicy.builder().inspection().build()`, 도구 권한만 필요하면
`GuardrailsPolicy.builder().toolAuthorization(true).build()`를 사용합니다. 필요한 구성 요소는
[검사 설정](inspection.md#입력-검사-설정)이나 [도구 권한 설정](security.md#spring-boot-스타터-추가)을 참고하세요.

### 기능과 출력 정책 조합

이 `GuardrailsConfiguration`은 개인정보 보호, 콘텐츠 검사와 도구 권한 검사를 함께 사용합니다.
예시의 `sendEmailToolCallback`과 `mcpToolCallbackProvider`는 앱에서 준비한 도구와 MCP 제공자입니다.

```java
@Configuration(proxyBeanMethods = false)
public class GuardrailsConfiguration {
    @Bean
    ToolDisclosurePolicy toolDisclosurePolicy() {
        return ToolDisclosurePolicy.allowEntityTypesByToolName(
                Map.of("sendEmail", List.of("EMAIL_ADDRESS")));
    }

    @Bean
    GuardrailsPolicy guardrailsPolicy(ToolDisclosurePolicy toolDisclosurePolicy) {
        GuardrailsPolicy.Privacy privacySettings = GuardrailsPolicy.Privacy.builder()
                .toolDisclosurePolicy(toolDisclosurePolicy)
                .outputProtection().redact()
                .build();
        GuardrailsPolicy.Inspection inspectionSettings = GuardrailsPolicy.Inspection.builder()
                .finalOutputInspectionEnabled(true)
                .build();
        return GuardrailsPolicy.builder()
                .privacy(privacySettings)
                .inspection(inspectionSettings)
                .toolAuthorization(true)
                .build();
    }

    @Bean
    ChatClient chatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory,
            GuardrailsPolicy guardrailsPolicy, ToolCallback sendEmailToolCallback,
            ToolCallbackProvider mcpToolCallbackProvider) {
        return guardrailsChatClientFactory.builder(chatModel, guardrailsPolicy)
                .defaultTools(sendEmailToolCallback, mcpToolCallbackProvider)
                .build();
    }
}
```

이 설정은 `sendEmail`에만 이메일 주소 원문을 공개하고 최종 출력의 개인정보를 가립니다.
모델 요청과 최종 응답을 모두 검사하며, 도구 접근에는 애플리케이션의 권한 정책을 적용합니다.

애플리케이션 로직은 [원문 공개 정책 클래스](privacy.md#custom-tool-disclosure-policy)나
[권한 검사 클래스](security.md#권한-정책-정의)로 분리할 수 있습니다.

원래 콜백, 도구 어노테이션이 있는 객체 또는 제공자를 등록합니다.
이 Factory에 전달하기 전에 `PrivacyToolCallbackFactory`로 감싸지 마세요.

제공자는 요청마다 해석하므로 이후 MCP 도구 목록 변경도 반영합니다.
`chatClient.prompt().tools(...)`로 전달한 도구도 같은 보호를 받습니다.

허용 목록에 없는 도구·유형 조합에는 원문 대신 불투명 토큰을 전달합니다.

도구 실행 권한과 원문 공개 범위는 별도로 결정합니다.

`privacy()`만 선택하면 모델 요청과 도구를 보호합니다. `inspection()`만 선택하면
모델 요청을 검사합니다. 최종 출력 보호와 검사는 별도로 활성화합니다.
출력만 검사하려면 다음과 같이 설정합니다.

```java
.inspection(GuardrailsPolicy.Inspection.builder()
        .modelRequestInspectionEnabled(false)
        .finalOutputInspectionEnabled(true)
        .build())
```

각 설정 빌더는 기본값에서 시작합니다. 완성한 설정 객체를 `privacy(...)`나
`inspection(...)`에 전달하면 해당 기능의 설정을 교체합니다. 일부 값만 바꾸고
나머지를 유지하려면 설정 객체의 `mutate()`를 사용합니다.

## 정책 재사용

공통 `guardrailsPolicy` 빈을 주입받아 여러 클라이언트에서 재사용할 수 있습니다.
`mutate()`로 정책의 설정값을 가진 새 빌더를 만듭니다. 빌더를 변경해도 원래 정책은 유지됩니다.

같은 기능을 유지하면서 최종 출력 보호와 검사를 끄는 예시입니다.

```java
GuardrailsPolicy policy = guardrailsPolicy.mutate()
        .privacy(guardrailsPolicy.privacy().mutate().outputProtection().disable().build())
        .inspection(guardrailsPolicy.inspection().mutate().finalOutputInspectionEnabled(false).build())
        .build();

ChatClient chatClient = guardrailsChatClientFactory.builder(chatModel, policy)
        .build();
```

콘텐츠 검사와 도구 권한 검사 없이 개인정보 보호만 사용하는 예시입니다.

```java
GuardrailsPolicy policy = guardrailsPolicy.mutate()
        .inspection(false)
        .toolAuthorization(false)
        .build();

ChatClient chatClient = guardrailsChatClientFactory.builder(chatModel, policy)
        .build();
```

`privacy(false)`와 `inspection(false)`는 해당 기능 전체를 제외합니다.
정책은 기능을 하나 이상 선택해야 합니다.

`chatClientBuilder.clone()`과 `chatClient.mutate()`는 이미 구성한 보호를 유지합니다.
보호 범위를 바꾸려면 정책을 수정하고 Factory로 새 클라이언트를 만드세요.

## Spring Boot 기본 정책

공통 기본값이 필요하면 `GuardrailsPolicy` 빈을 등록합니다.

```java
@Bean
GuardrailsPolicy guardrailsPolicy() {
    return GuardrailsPolicy.builder()
            .privacy()
            .build();
}

@Bean
ChatClient chatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
    return guardrailsChatClientFactory.builder(chatModel)
            .build();
}
```

`builder(chatModel)`은 Factory의 기본 정책을 사용합니다. `builder(chatModel, policy)`는
해당 호출에 전달한 정책만 사용합니다. Factory에 기본 정책이 없으면 정책을 직접 전달하세요.

공통 클라이언트 기본값은 `ChatClientBuilderCustomizer` 빈으로 설정할 수 있습니다.
Factory는 `ChatClientCustomizer` 빈을 먼저 적용한 뒤 `ChatClientBuilderCustomizer` 빈을
적용합니다. 각 타입 안에서는 Spring의 빈 정렬 순서를 따릅니다.

스타터는 `GuardrailsChatClientFactory`와 설정된 `GuardrailsChatClientFactory.Builder`를 제공합니다.
스타터의 서비스 연결과 기본값을 변경하려면 빌더를 주입받아 사용합니다.
입력·출력 서비스 지정 방법은 [입력·출력 검사 서비스 설정](inspection.md#입력과-출력에-서로-다른-서비스-지정)을,
팩토리를 직접 만드는 방법은 [Spring Boot 없이 구성](#spring-boot-없이-구성)을 참고하세요.

## Advisor 구성

도구 실행 순서를 바꾸거나 Tool Search를 사용하려면 도구 Advisor 빌더를 전달합니다.

```java
ChatClient chatClient = guardrailsChatClientFactory.builder(chatModel, guardrailsPolicy,
        ToolCallingAdvisor.builder().advisorOrder(100))
    .defaultTools(sendEmailToolCallback)
    .build();
```

Factory는 빌더를 복사하면서 Tool Search를 포함한 Advisor 타입을 유지합니다.
클라이언트에 다른 도구 Advisor를 별도로 등록하지 마세요.

메모리나 RAG 같은 일반 Advisor는 `defaultAdvisors(...)`나 요청별 `advisors(...)`로
등록합니다. 모델 요청의 보호 경계 밖에서 추가하거나 변경한 내용은 보호되지 않을 수 있습니다.

## Spring Boot 없이 구성

`spring-ai-privacy-guardrails-chat-client`에 의존하고 서비스를 전달합니다.

```java
GuardrailsChatClientFactory guardrailsChatClientFactory = GuardrailsChatClientFactory.builder()
        .privacyService(privacyService)
        .inputInspectionService(inspectionService)
        .defaultPolicy(GuardrailsPolicy.builder()
                .privacy()
                .inspection()
                .build())
        .build();
```

최종 출력 검사에는 `outputInspectionService(...)`로 서비스를 지정합니다.
실행할 검사 방향은 정책에서 선택합니다. 자세한 설정은
[콘텐츠 검사](inspection.md#입력과-출력에-서로-다른-서비스-지정)를 참고하세요.

권한 검사에는 Security 모듈을 추가하고 `SpringSecurityToolBoundary`와
`ToolAuthorizationChatClientFactory`를 구성합니다. 통합 Factory 빌더에
`.toolAuthorization(authorizationFactory::prepareToolAuthorization)`를 전달하세요.

`GuardrailsChatClientFactory.builder()`의 `observationRegistry(...)`로 관측을,
`clientCustomizer(...)`로 클라이언트 사용자 정의 설정을 구성합니다.

## 실행과 진단

모델 호출 전에는 선택한 기능에 따라 개인정보 처리, 도구 정의 권한 검사,
콘텐츠 검사 순서로 실행합니다. 도구 권한을 확인한 뒤 허용한 원문만 복원합니다.
개인정보 보호를 선택하면 도구 결과도 모델이나 애플리케이션에 전달하기 전에 보호합니다.
콘텐츠 검사는 새로 생성된 도구 인자를 실행 직전에 검사하지 않습니다.

개인정보 출력 보호와 출력 검사는 스트리밍 응답을 끝까지 모으므로 전달이 늦어집니다.
정책의 `privacy.responseLimits(...)`와 `inspection.maxOutputFrames(...)`,
`outputStreamTimeout(...)`을 [검사 한도](inspection.md#검사-범위와-한도)와 함께 설정하세요.
수집 중 오류, 시간 초과 또는 취소가 발생하면 모은 출력을 전달하지 않습니다.
최종 출력 차단은 이미 실행한 도구의 부작용을 되돌리지 않습니다.

`InspectionObserver`로 검사 보고서와 실패를 기록할 수 있습니다. 콜백에는
검사 단계와 call 또는 stream 실행 여부도 전달됩니다.
[검사 결과 기록](inspection.md#검사-결과-기록)을 참고하세요.

`ChatModel`을 직접 호출하면 이 클라이언트를 거치지 않습니다.
탐지 범위는 구성한 분석기와 검사기에 따라 달라집니다.
[검사 범위와 한도](inspection.md#검사-범위와-한도)와 [위협 모델](threat-model.md)을 참고하세요.

## 직접 조립하는 고급 연동

Spring AI 빌더에 기능을 직접 구성하려면 `PrivacyToolCallbackFactory`로 도구를 감싸고
`PrivacyChatClientConfigurer`로 개인정보 보호를 적용합니다.
여러 기능의 Configurer는 `ModelRequestBoundaryConfigurer.compose(...)`로 합쳐
빌더에 한 번 적용합니다.

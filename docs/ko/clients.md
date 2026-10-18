---
description: >-
  하나의 ChatClient Factory로 개인정보 보호, 콘텐츠 검사, 도구 권한 검사를 선택합니다.
---

# 클라이언트 정책

[English](../clients.md) | **한국어**

<!-- i18n-source: docs/clients.md -->
<!-- i18n-source-sha256: a2263e33f995d46881bf1acdfb6d6b47115e873a9b63dc5b2654f00d92b629a6 -->

`GuardrailsChatClientFactory`로 클라이언트마다 개인정보 보호, 콘텐츠 검사,
도구 권한 검사를 선택합니다. Factory는 선택한 기능을 조립하고 Spring AI를 통해
등록한 도구를 보호합니다. 요청별 도구와 MCP 도구에도 같은 정책을 적용합니다.

## 의존성과 구성 요소

Spring AI 모델 연동 모듈과 함께 클라이언트 스타터를 추가합니다.

```gradle
implementation "io.github.ultramancode:spring-ai-privacy-guardrails-client-spring-boot-starter:0.4.0-SNAPSHOT"
```

Guardrails 모듈은 같은 버전을 사용하세요. 이 스타터는 개인정보 보호와 검사 연동을
포함하지만 Spring Security, ONNX Runtime, Presidio와 검사 백엔드는 포함하지 않습니다.

| 선택한 기능 | 필수 구성 요소 | 설정 |
| --- | --- | --- |
| 개인정보 보호 | `PrivacyService` | [Regex 또는 다른 분석기](getting-started.md)를 구성합니다. |
| 콘텐츠 검사 | `InspectionService` | `ContentInspector` 또는 직접 구성한 서비스를 등록합니다. [콘텐츠 검사](inspection.md)를 참고하세요. |
| 도구 권한 검사 | 권한 연동 | [Security 스타터](security.md)와 `AuthorizationManager<ToolAuthorizationContext>` 빈을 추가합니다. |

구성 요소가 있다는 이유만으로 기능을 활성화하지 않습니다. 선택한 기능의 필수
구성 요소가 없으면 클라이언트 빌더를 생성할 때 오류가 발생합니다.

## Spring Boot 기본 정책

사용할 기능을 명시적으로 선택합니다. 세 선택 항목의 기본값은 모두 `false`입니다.

```yaml
spring:
  ai:
    guardrails:
      privacy: true
      inspection: true
      tool-authorization: true
    privacy:
      tools:
        disclosures:
          customerLookup: [CUSTOMER_ID]
      output:
        enabled: true
        action: REDACT
    inspection:
      output:
        enabled: true
```

이 예제는 위 표의 세 구성 요소가 모두 필요합니다. 개인정보 보호는 모델 요청과
도구에 적용합니다. 고객 ID 원문은 `customerLookup`에만 공개하고, 최종 출력에서
탐지한 개인정보는 가립니다. 콘텐츠 검사는 모든 모델 요청과 최종 출력에 적용합니다.
도구 권한 검사는 애플리케이션의 현재 인증 정보와 권한 정책을 사용합니다.

```java
@Bean
ChatClient supportClient(ChatModel chatModel, GuardrailsChatClientFactory guardrails,
        ToolCallback customerLookup, ToolCallbackProvider mcpTools) {
    return guardrails.builder(chatModel)
            .defaultTools(customerLookup, mcpTools)
            .build();
}
```

원래 콜백, 도구 어노테이션이 있는 객체 또는 제공자를 등록합니다.
이 Factory에 전달하기 전에 `PrivacyToolCallbackFactory`로 감싸지 마세요.
제공자는 요청마다 해석하므로 이후 MCP 도구 목록 변경도 반영합니다.
`client.prompt().tools(...)`로 전달한 도구도 같은 보호를 받습니다.
허용 목록에 없는 도구·유형 조합에는 원문 대신 불투명 토큰을 전달합니다.

도구 실행 권한과 원문 공개 범위는 별도로 결정합니다.
도구 권한 검사를 활성화해도 업무 권한 규칙을 대신 만들어 주지는 않습니다.

위의 `output` 설정을 생략하면 개인정보 보호는 모델 요청과 도구에,
콘텐츠 검사는 모델 요청에만 적용합니다. 최종 출력 보호와 검사는 별도로 활성화합니다.

## 클라이언트별 명시적 정책

클라이언트마다 보호 범위가 다르면 Java 정책을 전달합니다.

```java
GuardrailsPolicy supportPolicy = GuardrailsPolicy.builder()
        .privacy(privacy -> privacy
                .toolDisclosurePolicy(ToolDisclosurePolicy.byToolName(
                        Map.of("customerLookup", List.of("CUSTOMER_ID"))))
                .outputAction(PrivacyOutputAction.REDACT))
        .inspection(inspection -> inspection.applicationOutput(true))
        .toolAuthorization(true)
        .build();

ChatClient client = guardrails.builder(chatModel, supportPolicy)
        .defaultTools(customerLookup, mcpTools)
        .build();
```

`builder(chatModel)`은 기본 정책을 사용합니다. `builder(chatModel, policy)`는
원문 공개와 출력 설정까지 전달받은 정책만 사용하며 Boot 기본 정책을 합치지 않습니다.
탐지 모델이나 검사 서버 연결 같은 엔진 설정은 각 서비스에서 관리합니다.

`GuardrailsPolicy` 빈을 등록하면 속성 기반 기본 정책을 대체할 수 있습니다.
기본 정책을 하나로 정할 수 없으면 명시적 정책을 전달하세요.
정책은 기능을 하나 이상 선택해야 합니다. 서로 다른 클라이언트는 서비스를 공유해도
상대의 정책이나 세션을 변경하지 않습니다. `builder.clone()`과 `client.mutate()`는
구성한 보호를 유지합니다.

출력만 검사하려면
`.inspection(inspection -> inspection.modelRequests(false).applicationOutput(true))`를 선택합니다.

## 사용자 정의 도구 Advisor와 Boot 없는 구성

도구 실행 순서를 바꾸거나 Tool Search를 사용하려면 도구 Advisor 빌더를 전달합니다.

```java
ChatClient client = guardrails.builder(chatModel, supportPolicy,
        ToolCallingAdvisor.builder().advisorOrder(100))
    .defaultTools(customerLookup)
    .build();
```

Factory는 빌더를 복사하면서 하위 타입을 유지합니다. 사용자 정의 빌더는 Spring AI의
`copy()`, `toolCallingManager(...)`, `build()` 계약을 지켜야 합니다.
클라이언트에 다른 도구 Advisor를 별도로 등록하지 마세요.

Boot 없이 사용할 때는 `spring-ai-privacy-guardrails-client`에 의존하고 서비스를 전달합니다.

```java
GuardrailsChatClientFactory guardrails = GuardrailsChatClientFactory.builder()
        .privacyService(privacyService)
        .inspectionService(inspectionService)
        .defaultPolicy(GuardrailsPolicy.builder().privacy().inspection().build())
        .build();
```

권한 검사에는 Security 모듈을 추가하고 `SpringSecurityToolBoundary`와
`ToolAuthorizationChatClientFactory`를 구성합니다. 통합 Factory 빌더에
`.toolAuthorization(authorizationFactory::builder)`를 전달하세요.
관측 설정과 클라이언트 Customizer는 `GuardrailsChatClientFactory`에 지정합니다.

## 실행과 진단

모델 호출 전에는 선택한 기능에 따라 개인정보 처리, 도구 정의 권한 검사,
콘텐츠 검사 순서로 실행합니다. 도구 권한을 확인한 뒤 허용한 원문만 복원합니다.
도구 결과는 모델이나 애플리케이션에 전달하기 전에 보호합니다.
콘텐츠 검사는 새로 생성된 도구 인자를 실행 직전에 검사하지 않습니다.

개인정보 출력 보호와 출력 검사는 스트리밍 응답을 끝까지 모으므로 전달이 늦어집니다.
개인정보의 `response-inspection` 한도와 검사의 `output.max-frames`,
`output.stream-timeout`을 [검사 한도](inspection.md#검사-범위와-한도)와 함께 설정하세요.
수집 중 오류, 시간 초과 또는 취소가 발생하면 모은 출력을 전달하지 않습니다.
최종 출력 차단은 이미 실행한 도구의 부작용을 되돌리지 않습니다.

`InspectionObserver`로 검사 보고서와 실패를 기록할 수 있습니다. 콜백에는
검사 경계와 call 또는 stream 실행 여부도 전달됩니다.
[검사 결과 기록](inspection.md#검사-결과-기록)을 참고하세요.

`ChatModel`을 직접 호출하면 이 클라이언트를 거치지 않습니다.
탐지 범위는 구성한 분석기와 검사기에 따라 달라집니다.
[검사 범위와 한도](inspection.md#검사-범위와-한도)와 [위협 모델](threat-model.md)을 참고하세요.

사용자 정의 조립에는 [직접 조립하는 고급 연동](configuration.md#직접-조립하는-고급-연동)의
개별 Configurer를 Spring AI 빌더에 직접 적용하세요. Factory로 만든 클라이언트에는
선택한 기능이 이미 구성되어 있습니다.

모델 API 키 없이 처음 실행하려면 [로컬 데모](sample.md)를 사용하세요.
ChatClient 없는 검사는 [단독 검사 예제](inspection.md#chatclient-없이-검사)를 참고하세요.

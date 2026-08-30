# Spring Security 도구 권한 부여

[English](../security.md) | **한국어**

<!-- i18n-source: docs/security.md -->
<!-- i18n-source-sha256: de85b1fced6a0185bdaeb334e696168f6b1aabd8f95b5a50b11cb7cee88cb270 -->

선택적 Spring Security 통합은 현재 `Authentication`이 Spring AI 도구를 발견하거나
실행할 수 있는지 통제합니다. 이 기능은 개인정보 보호 경계를 보완하며, 개인정보 탐지나
도구별 원문 값 공개를 대체하지 않습니다.

현재 사용자의 권한에 따라 도구 목록과 실행 여부를 달리해야 할 때 사용하세요. 사용자별
도구 권한 부여가 필요하지 않은 애플리케이션은 Spring Security를 추가하지 않고 기존처럼
`PrivacyChatClientConfigurer`를 사용할 수 있습니다.

## 보장 범위

지원 경로에서는 다음 시점에 권한을 확인합니다.

| 확인 시점 | 동작 |
| --- | --- |
| 모델 공개 | 권한 정책에 `ToolAuthorizationPhase.DEFINITION`을 전달합니다. 거부된 도구 정의는 모델에 제공하는 도구 목록에서 제외합니다. |
| 모델이 요청한 호출 | 모델이 숨겨진 도구 이름을 생성하거나 resolver fallback이 해당 이름을 찾을 수 있어도, 모델에 공개하지 않은 도구는 거부합니다. |
| 실행 묶음 | 같은 응답에서 요청한 모든 도구의 권한을 확인한 뒤에야 첫 번째 도구를 실행합니다. |
| 콜백 호출 | 각 비즈니스 콜백을 호출하기 직전이자 `PrivacyToolCallbackFactory`가 허용된 개인정보 원문을 복원하기 전에 다시 권한을 확인합니다. |
| 도구 결과 | 기존 개인정보 보호 경계가 결과를 모델이나 애플리케이션에 반환하기 전에 다시 보호합니다. |

`ToolAuthorizationContext`에는 `ToolDefinition`과 현재 권한 확인 단계만 들어갑니다.
도구 인자와 요청의 개인정보는 포함하지 않습니다. 현재 `Authentication`은 Spring
Security의 표준 `AuthorizationManager` 계약을 통해 전달됩니다.

권한 부여와 개인정보 원문 공개는 서로 다른 질문에 답합니다.

- `AuthorizationManager<ToolAuthorizationContext>`는 현재 사용자가 도구를 발견하거나
  실행할 수 있는지 결정합니다.
- `tools.disclosures`로 구성하는 `ToolDisclosurePolicy`는 권한이 허용된 도구가 어떤
  개인정보 엔티티 유형을 원문으로 받을 수 있는지 결정합니다.

도구는 권한 확인을 통과한 뒤에만 개인정보 보호 래퍼가 허용된 원문 값을 복원할 수
있습니다. 도구 실행 권한을 허용해도 모든 개인정보 유형이 자동으로 공개되지는 않습니다.

## Spring Boot 스타터 추가

선택적 스타터는 `0.3.0`부터 제공됩니다.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-spring-security-spring-boot-starter:0.3.0"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-spring-security-spring-boot-starter</artifactId>
    <version>0.3.0</version>
</dependency>
```

Security 스타터에는 기본 Privacy Guardrails 스타터가 포함됩니다. Spring Security
의존성으로는 `spring-security-core`만 추가합니다. 로그인 화면을 추가하거나 사용자를
인증하지 않으며, JWT 발급, OAuth 구성, resource server 기능도 제공하지 않습니다.
인증은 계속 애플리케이션의 책임이며, 보호할 요청에 도구 콜백이 포함되어 있다면 요청
시작 시 `Authentication`을 사용할 수 있도록 구성해야 합니다.

기존 애플리케이션을 업그레이드할 때는 별도로 선언한 기본 스타터를 제거하고, 분석기
스타터를 포함해 함께 사용하는 모든 Privacy Guardrails artifact를 버전 `0.3.0`으로
맞추세요.

분석기별 스타터가 필요하면 별도로 추가하세요. 예를 들어 Presidio 구성은 Security
스타터와 Presidio 스타터를 함께 사용합니다. 내장 정규식(Regex) 분석기는 Security
스타터에 포함된 기본 스타터를 통해 사용할 수 있습니다.

개인정보 보호와 선택적 권한 부여 경계를 모두 활성화합니다.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      security:
        enabled: true
```

분석기를 하나 이상 설정하거나 `PiiAnalyzer` Bean도 제공해야 합니다.
`spring.ai.privacy.enabled=true` 없이 Security만 활성화하면 애플리케이션 시작이
실패합니다.

## 권한 정책 정의

`AuthorizationManager<ToolAuthorizationContext>` Bean을 하나 제공합니다. Spring
Security의 일반적인 확장 계약을 사용하므로 도구 권한 정책은 애플리케이션이 관리합니다.

```java
@Bean
AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
    return (authentication, context) -> {
        Authentication current = authentication.get();
        boolean supportUser = current != null
                && current.isAuthenticated()
                && current.getAuthorities().stream()
                        .anyMatch(authority ->
                                authority.getAuthority().equals("ROLE_SUPPORT"));

        boolean granted = switch (context.toolDefinition().name()) {
            case "customerLookup" -> supportUser;
            default -> false;
        };
        return new AuthorizationDecision(granted);
    };
}
```

권한 정책은 모델에 제공할 도구 목록을 구성할 때와 모델이 요청한 도구를 실행할 때
적용됩니다. 단계별 규칙이 다르면 `context.phase()`를 확인할 수 있습니다. `null`이나
거부 결과를 반환하면 정의 확인 단계에서는 도구를 숨기고, 실행 단계에서는 호출을
거부합니다.

모든 도구를 의도적으로 허용하려면 다음 Bean을 명시적으로 제공할 수 있습니다.

```java
@Bean
AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
    return (authentication, context) -> new AuthorizationDecision(true);
}
```

모두 허용 정책도 경계 검증 자체는 유지하지만 사용자별 제한을 제공하지는 않습니다.
도구 콜백이 포함된 요청이 경계에 들어올 때 `Authentication`은 여전히 필요합니다.

## ChatClient 구성

`PrivacyChatClientConfigurer`를 별도로 적용하는 대신
`PrivacySecurityChatClientConfigurer`를 사용하세요. 결합 configurer는 기존
개인정보 보호 Advisor와 Spring Security context를 캡처하는 요청 Advisor를 함께
설치합니다.

```java
@Bean
ChatClient securedChatClient(
        ChatClient.Builder builder,
        PrivacySecurityChatClientConfigurer securityConfigurer
) {
    return securityConfigurer.configure(builder).build();
}
```

도구 콜백은 계속 `PrivacyToolCallbackFactory`로 감싸고, 해당 도구에 필요한 엔티티
유형만 `tools.disclosures`에 설정합니다.

```java
ToolCallback protectedCustomerLookup =
        privacyToolCallbackFactory.wrap(customerLookupToolCallback);
```

```yaml
spring:
  ai:
    privacy:
      tools:
        disclosures:
          customerLookup:
            - CUSTOMER_ID
```

같은 builder에 두 configurer를 모두 적용하지 마세요. 도구 콜백이 없는
`ChatClient`는 개인정보 보호 전용 configurer를 계속 사용할 수 있습니다. 도구 콜백이
있는 `ChatClient`는 스타터가 보안 `ToolCallingManager`를 애플리케이션 전역에
설치하므로 결합 configurer를 사용해야 합니다. 애플리케이션이 별도의 raw 또는
사용자 정의 manager에 명시적으로 연결한 실행 경로만 예외입니다.

## ToolCallingManager 선택

일반적인 Spring AI 자동 구성에서는 스타터가 자동 구성된 단일
`DefaultToolCallingManager`를 감싸고, 보호된 decorator를 primary
`ToolCallingManager`로 등록한 뒤 Spring이 이를 선택했는지 확인합니다. 스타터가 대체
manager를 직접 만들지는 않습니다. 기본 manager가 없거나 기본 후보가 두 개 이상이면
애플리케이션 시작이 실패합니다.

따라서 특정 upstream Bean 이름에 의존하지 않으면서 Spring AI ChatModel과
`ToolCallingAdvisor` 자동 구성이 같은 보호 manager를 사용합니다. Spring AI의
resolver fallback 활성화 여부와 관계없이 보호가 적용되며, 모델에 공개하지 않은 도구는
delegate가 찾거나 실행하기 전에 거부됩니다.

### 사용자 정의 ToolCallingManager

공개 인터페이스만으로는 임의의 사용자 정의 manager가 콜백을 어떻게 찾고 실행하는지
증명할 수 없으므로 자동으로 안전하다고 간주하지 않습니다. 다음처럼 경계를 명시적으로
제공하세요.

```java
@Bean
SpringSecurityToolBoundary springSecurityToolBoundary(
        @Qualifier("customToolCallingManager") ToolCallingManager delegate,
        AuthorizationManager<ToolAuthorizationContext> authorizationManager
) {
    return SpringSecurityToolBoundary.builder(delegate, authorizationManager)
            .build();
}
```

delegate는 보호된 prompt에 전달된 콜백을 사용해 도구 호출을 실행해야 합니다. primary
보호 manager 대신 원본 delegate를 직접 주입해 호출하는 경로는 이 경계의 보호 범위에
포함되지 않습니다.

Spring Boot 스타터 없이 `spring-ai-privacy-guardrails-spring-security`를 직접
사용한다면 `boundary.toolCallingManager()`와 `boundary.advisor()`를 모두 설치해야
합니다. manager와 advisor가 하나의 요청 registry를 공유하므로 둘 중 하나만 사용하면
경계가 완성되지 않습니다.

## Tool Search

Spring AI Tool Search는 도구 정의를 인덱싱하고, 모델이 요청 처리 중 필요한 비즈니스
도구를 더 작은 목록으로 검색할 수 있도록 제어 도구를 공개합니다. Security 스타터가 Tool
Search를 추가하거나 활성화하지는 않지만, 애플리케이션이 구성한
`ToolSearchToolCallingAdvisor`를 다음 규칙으로 지원합니다.

- 정의 권한이 허용된 비즈니스 도구만 인덱스에 추가합니다.
- Tool Search 검색 질의도 개인정보 보호 상태를 유지합니다.
- Spring AI가 사용하는 예약 이름과 요청별 세션 표시가 모두 있는 제어 콜백만 요청 캡처
  이후에 추가할 수 있습니다.
- 한 번 허용한 제어 콜백은 다른 객체로 교체할 수 없습니다.
- 검색으로 선택된 비즈니스 콜백은 최초 권한 경계에서 캡처한 것과 같은 콜백이어야 하며,
  정의 권한 확인을 통과해야 합니다.

그 밖의 요청 캡처 이후 콜백 추가·교체는 거부합니다. 콜백 제거와 순서 변경은 허용 집합을
넓히지 않으므로 허용합니다. Tool Search 제어 콜백은 비즈니스 도구가 아니므로
애플리케이션의 도구 권한 정책에는 전달하지 않습니다.

## Blocking, Reactive와 비동기 context

blocking 호출에서는 보호할 요청이 경계에 들어올 때 요청 Advisor가 설정된
`SecurityContextHolderStrategy`에서 `Authentication`을 캡처합니다. streaming
호출에서는 Reactor Context의 `SecurityContext` 항목을 우선합니다. 이 항목이 없을 때만
thread-local context를 사용하며, 항목은 있지만 비어 있으면 인증 누락으로 보고
거부합니다.

캡처한 뒤에는 `Authentication`을 요청별 내부 registry에 보관하고 Spring AI tool
context에는 불투명 handle만 넣습니다. 이후 도구 실행은 이 handle로 요청 상태를
찾으므로 실행 스레드의 thread-local 또는 Reactor context에 의존하지 않습니다.

요청 Advisor가 실행되기 전에 애플리케이션이 `ChatClient` 호출 자체를 다른 executor로
옮긴다면 Spring Security context도 해당 executor로 전파해야 합니다. 예를 들어 blocking
executor와 virtual thread executor에서는 Spring Security의
`DelegatingSecurityContextExecutorService`를 사용할 수 있습니다.
도구 콜백이 포함된 요청에 `Authentication`이 없으면 거부합니다.

캡처된 `Authentication`은 요청의 사용자를 나타냅니다. 실행 시점에도 권한 정책을 다시
호출하지만, 장시간 실행되는 요청 중 발생한 권한 철회까지 반영하려면 객체에 캡처된
authority만 확인하지 말고 애플리케이션 정책이 현재 외부 상태를 다시 조회해야 합니다.

권한 세션은 blocking 호출의 정상 완료·실패와 stream의 완료·실패·취소 후 제거합니다.
끝나지도 않고 취소되지도 않는 stream은 애플리케이션이 구독을 끝낼 때까지 요청 세션을
유지합니다.

## 호환성

| 구성 요소 | 지원 기준 |
| --- | --- |
| Java | 17 이상 |
| Spring AI | `2.0.x`의 `2.0.0` 이상, 신규 사용은 `2.0.1` 권장 |
| Spring Boot | `4.x`의 `4.0.0` 이상 |
| Spring Security | `7.0.0` 이상. Spring Boot `4.1.1`의 기본 관리 버전은 `7.1.1` |

이 통합은 Spring AI와 Spring Security의 공개 인터페이스를 사용하며 reflection이나
Spring AI private API를 요구하지 않습니다.

이 기능은 도구 권한을 제어하지만 사용자별 개인정보 공개 범위를 제어하지는 않습니다.
또한 임베딩 생성이나 `VectorStore` 저장 전에 필요한 수집 단계 보호를 대체하지 않으며,
구성한 경계 밖의 애플리케이션 자체 실행 경로도 보호하지 않습니다.

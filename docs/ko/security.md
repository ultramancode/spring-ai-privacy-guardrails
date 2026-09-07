# Spring Security 도구 권한

[English](../security.md) | **한국어**

<!-- i18n-source: docs/security.md -->
<!-- i18n-source-sha256: 972ed48c0446bc994555a37ad8bf2853ba843cff6c3b4bc861bae3917d9a03d0 -->

Spring Security 연동은 현재 사용자(`Authentication`)의 권한에 따라 모델에 보여 줄
도구와 실제로 실행할 수 있는 도구를 제한합니다. 도구 권한 검사만 사용하거나 개인정보
보호 기능과 함께 적용할 수 있습니다.

두 기능을 함께 사용하면 Spring Security는 도구의 공개·실행 권한을 확인하고, 개인정보
보호 기능은 개인정보를 탐지해 각 도구에 전달할 원문의 범위를 제어합니다.

## 보장 범위

이 연동을 적용한 도구 호출 경로에서는 다음 단계마다 권한을 확인합니다.

| 단계 | 보장 |
| --- | --- |
| 도구 목록 제공 전 | 권한 정책을 확인하고, 허용되지 않은 도구는 모델에 제공할 목록에서 제외합니다. |
| 모델의 도구 요청 시 | 모델이 목록에 없던 도구를 이름으로 요청하더라도 실행하지 않습니다. |
| 여러 도구 요청 시 | 한 응답에서 여러 도구를 요청한 경우, 요청된 도구 전체의 권한을 확인한 후 실행을 시작합니다. |
| 도구 실행 직전 | 각 도구의 권한을 다시 확인합니다. 개인정보 보호도 함께 적용했다면, 허용된 원문을 복원하기 전에 확인합니다. |

도구 권한과 개인정보 원문 공개 범위는 서로 독립적으로 설정합니다.

- `AuthorizationManager<ToolAuthorizationContext>`는 현재 사용자의 권한에 따라 모델에
  보여 줄 도구와 실행을 허용할 도구를 결정합니다.
- `tools.disclosures`는 권한이 허용된 도구에 어떤 유형의 개인정보를 원문으로 전달할지
  결정합니다.

도구 실행 권한이 있어도 개인정보 원문이 자동으로 전달되지는 않습니다. 권한 정책이
도구 실행을 허용하고 `tools.disclosures`에 해당 개인정보 유형을 설정한 경우에만 원문을
전달합니다.

## Spring Boot 스타터 추가

Spring Security 스타터는 `0.3.0`부터 제공합니다.

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

Security 스타터는 기본 Privacy Guardrails 스타터 없이도 사용할 수 있습니다. 도구 권한
검사는 애플리케이션의 기존 Spring Security 구성이 제공하는 `Authentication`을 사용하므로,
도구를 사용하는 요청이 시작될 때 해당 인증 정보를 사용할 수 있어야 합니다.

### 도구 권한 검사만 사용

다음 설정을 활성화합니다.

```yaml
spring:
  ai:
    privacy:
      security:
        enabled: true
```

이 구성에는 개인정보 분석기나 `spring.ai.privacy.enabled=true`가 필요하지 않습니다.

### 개인정보 보호와 함께 사용

기본 Privacy Guardrails 스타터 또는 이를 포함하는 분석기 스타터를 추가하고, 사용하는
모든 Privacy Guardrails 모듈의 버전을 `0.3.0`으로 맞추세요. 그런 다음 두 기능을 모두
활성화합니다.

```yaml
spring:
  ai:
    privacy:
      enabled: true
      security:
        enabled: true
```

개인정보 보호를 함께 사용하려면 Regex, Presidio, OpenNLP 또는 사용자 정의
`PiiAnalyzer` 중 하나를 구성해야 합니다.

## 권한 정책 정의

도구 권한 정책을 `AuthorizationManager<ToolAuthorizationContext>` Bean으로 등록합니다.
`AuthorizationManager`는 Spring Security의 표준 확장 인터페이스입니다.

`ToolAuthorizationContext`는 검사할 도구의 명세(`ToolDefinition`)와 권한 확인 단계를
제공합니다. 도구 인자와 요청 원문은 포함하지 않습니다.

다음은 `ROLE_SUPPORT` 권한이 있는 사용자에게 `customerLookup` 도구만 허용하는 예시입니다.
다른 도구는 모두 거부합니다.

```java
@Bean
AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
    return (authentication, context) -> {
        Authentication currentAuthentication = authentication.get();
        if (currentAuthentication == null || !currentAuthentication.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }

        boolean hasSupportRole = currentAuthentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPPORT"));

        boolean granted = switch (context.toolDefinition().name()) {
            case "customerLookup" -> hasSupportRole;
            default -> false;
        };
        return new AuthorizationDecision(granted);
    };
}
```

단계별로 다른 규칙이 필요하면 `context.phase()`를 사용하세요. 모델에 제공할 도구 목록을
구성할 때는 `ToolAuthorizationPhase.DEFINITION`, 도구 실행 권한을 확인할 때는
`ToolAuthorizationPhase.EXECUTION`입니다.

정책이 `null`이나 거부 결과를 반환하면 도구 목록 구성 단계에서는 해당 도구를 제외하고,
실행 단계에서는 호출을 거부합니다.

다음은 모든 도구를 허용하는 예시입니다.

```java
@Bean
AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
    return (authentication, context) -> new AuthorizationDecision(true);
}
```

도구 권한 연동은 요청의 사용자 정보를 확보한 뒤 권한 정책을 평가합니다.
따라서 모든 도구를 허용하는 경우에도 `Authentication`이 필요합니다.

## ChatClient 구성

Security 스타터의 기본 구성에서는 도구를 사용하는 각 `ChatClient`에 아래 구성 중
하나를 적용해야 합니다. 적용하지 않으면 도구 호출이 거부됩니다. 도구가 없는
`ChatClient`에는 권한 검사 구성이 필요하지 않습니다.

### 도구 권한 검사만 적용

도구 권한 검사만 사용할 때는 다음과 같이 `ChatClient`를 구성합니다.

```java
@Bean
ChatClient authorizedToolClient(
        ChatClient.Builder builder,
        ToolAuthorizationChatClientConfigurer authorizationConfigurer
) {
    return authorizationConfigurer.configure(builder).build();
}
```

### 개인정보 보호와 함께 적용

개인정보 보호도 활성화했다면 `PrivacySecurityChatClientConfigurer`를 사용하세요.
도구 권한 검사와 개인정보 보호를 함께 적용합니다.

```java
@Bean
ChatClient securedChatClient(
        ChatClient.Builder builder,
        PrivacySecurityChatClientConfigurer privacySecurityConfigurer
) {
    return privacySecurityConfigurer.configure(builder).build();
}
```

`PrivacySecurityChatClientConfigurer`를 적용했다면 같은 builder에 개인정보 보호 전용
또는 권한 검사 전용 configurer를 추가로 적용하지 마세요.

개인정보 보호를 함께 사용할 때는 `PrivacyToolCallbackFactory`로 도구 콜백을 감쌉니다.
도구에 원문으로 전달할 개인정보 유형만 `tools.disclosures`에 설정합니다.

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

## ToolCallingManager 선택

스타터는 Spring AI ChatModel과 자동 구성된 도구 호출 Advisor가 사용할
`ToolCallingManager`에 권한 검사를 적용하고, 이를 primary manager로 등록합니다.
기본 구성에서는 Spring AI의 `DefaultToolCallingManager`가 정확히 하나 있어야 하며,
없거나 후보가 여러 개이면 애플리케이션 시작이 실패합니다.

Spring AI의 이름 기반 도구 조회가 활성화되어 있어도 거부된 도구는 사용할 수 없습니다.

Security 스타터를 활성화한 상태에서 개인정보 보호만 적용하는 별도 도구 경로가
필요하다면, 권한 검사가 적용되지 않은 `ToolCallingManager`를 해당 경로에 명시적으로
연결하세요. 이 경로에는 이 연동의 도구 권한 검사가 적용되지 않습니다.

### 사용자 정의 ToolCallingManager

사용자 정의 `ToolCallingManager`에는 다음처럼 `SpringSecurityToolBoundary`를 명시적으로
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

위임 대상 `ToolCallingManager`는 실행용 프롬프트에 전달된 콜백을 사용해 도구를 실행해야
합니다. 권한 검사가 적용된 primary `ToolCallingManager` 대신 위임 대상을 직접 주입해
호출하는 경로는 이 경계의 보호 범위에 포함되지 않습니다.

Spring Boot 스타터 없이 `spring-ai-privacy-guardrails-spring-security`를 직접
사용한다면 `boundary.toolCallingManager()`와
`boundary.toolAuthorizationAdvisor()`를 함께 설치해야 합니다.

## Tool Search

Tool Search는 모델이 필요한 도구를 검색해 선택하는 Spring AI의 선택 기능입니다.
애플리케이션에서 `ToolSearchToolCallingAdvisor`를 사용한다면 권한 경계의
`ToolCallingManager`를
명시적으로 지정하세요. builder는 기본적으로 별도의 `ToolCallingManager`를 생성합니다.

```java
@Bean
ToolSearchToolCallingAdvisor toolSearchToolCallingAdvisor(
        SpringSecurityToolBoundary boundary,
        ToolIndex toolIndex
) {
    return ToolSearchToolCallingAdvisor.builder()
            .toolIndex(toolIndex)
            .toolCallingManager(boundary.toolCallingManager())
            .build();
}
```

권한 검사 configurer를 적용한 builder에 이 Advisor도 등록하세요. 예를 들어
Tool Search와 개인정보 보호를 함께 사용하려면 다음처럼 구성합니다.

```java
@Bean
ChatClient toolSearchClient(
        ChatClient.Builder builder,
        PrivacySecurityChatClientConfigurer privacySecurityConfigurer,
        ToolSearchToolCallingAdvisor toolSearchAdvisor
) {
    return privacySecurityConfigurer.configure(builder)
            .defaultAdvisors(toolSearchAdvisor)
            .build();
}
```

권한 검사만 필요하면 `ToolAuthorizationChatClientConfigurer`를 사용하세요.
ChatModel과 Tool Search Advisor에는 같은 경계의 `ToolCallingManager`를 연결해야 합니다.
스타터는 자동 구성된 ChatModel에 이 `ToolCallingManager`를 주입합니다. 직접 만드는 모델과
도구 호출 Advisor에는 명시적으로 연결해야 합니다.

이 구성에서는 허용된 비즈니스 도구 정의만 인덱스에 넣고, 실행 전에 권한을 다시
확인합니다. 개인정보 보호도 구성했다면 검색 인자에서 탐지된 개인정보를 검색 실행
전에 토큰화합니다.

Tool Search에 권한 검사가 없는 원본 `ToolCallingManager`를 연결하면, 이후 모델이나 실행
단계에서 요청이 거부되더라도 그 전에 권한 없는 도구 정의가 인덱스에 들어갈 수 있습니다.
실행을 차단해도 이미 인덱스에 전달한 정보까지 되돌릴 수는 없습니다.

Tool Search에서는 Spring AI의 제어 콜백과 권한이 허용된 비즈니스 콜백만 사용할 수
있습니다. 요청 도중 예상하지 않은 콜백이 추가되거나 교체되면 실행을 거부합니다.
애플리케이션과 확장 코드에 대한 신뢰 범위는
[위협 모델](threat-model.md)을 참고하세요.

## 동기·스트리밍·비동기 호출의 인증 정보

동기 호출에서는 보호할 요청이 시작될 때 설정된 `SecurityContextHolderStrategy`에서
`Authentication`을 가져옵니다. 스트리밍 호출에서는 Reactor의 `SecurityContext`를
우선합니다. 리액티브 보안 컨텍스트가 없을 때만 스레드의 보안 컨텍스트를 사용하며,
명시적으로 비어 있는 리액티브 컨텍스트는 인증되지 않은 상태로 처리합니다.

가져온 `Authentication`은 다른 스레드에서 실행되는 도구 호출을 포함해 요청 전체에서
사용합니다.

보호할 요청이 시작되기 전에 애플리케이션이 `ChatClient` 호출 자체를 다른 executor로
옮긴다면 Spring Security 보안 컨텍스트도 해당 executor로 전달해야 합니다. 예를 들어
동기 호출이나 가상 스레드에 사용하는 executor에는 Spring Security의
`DelegatingSecurityContextExecutorService`를 사용할 수 있습니다.
도구 콜백이 포함된 요청에 `Authentication`이 없으면 거부합니다.

`Authentication`은 요청 시작 시 사용자의 인증 정보를 나타냅니다. 실행 시점에도 권한
정책을 다시 확인하지만, 장시간 실행되는 요청 중 권한이 철회된 사실까지 반영하려면
정책에서 객체에 저장된 권한뿐 아니라 현재 권한 상태도 조회해야 합니다.

라이브러리는 동기 호출의 정상 완료·실패 또는 스트림의 완료·실패·취소 시 요청의
권한 상태를 정리합니다.

## 호환성

| 구성 요소 | 지원 기준 |
| --- | --- |
| Java | 17 이상 |
| Spring AI | `2.0.x`의 `2.0.0` 이상, 신규 사용은 `2.0.1` 권장 |
| Spring Boot | `4.x`의 `4.0.0` 이상 |
| Spring Security | `7.x`의 `7.0.0` 이상. Spring Boot `4.1.1`의 기본 관리 버전은 `7.1.1` |

호환성 검증은 Spring AI `2.0.1` / Boot `4.1.1` / Security `7.1.1` 조합과 최소 버전인
Spring AI `2.0.0` / Boot `4.0.0` / Security `7.0.0` 조합에서 수행합니다.
이 검증이 앞으로 나올 모든 버전의 호환성을 보장하는 것은 아닙니다.

이 통합은 Spring AI와 Spring Security의 공개 인터페이스를 사용하며 리플렉션이나
Spring AI의 비공개 API를 요구하지 않습니다.

이 기능은 도구 권한을 제어하지만 사용자별 개인정보 공개 범위를 제어하지는 않습니다.
또한 임베딩 생성이나 `VectorStore` 저장 전에 필요한 수집 단계 보호를 대체하지 않으며,
구성한 경계 밖의 애플리케이션 자체 실행 경로도 보호하지 않습니다.

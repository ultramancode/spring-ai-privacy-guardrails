# Spring Security 도구 권한

[English](../security.md) | **한국어**

<!-- i18n-source: docs/security.md -->
<!-- i18n-source-sha256: b85afa8601ebf8bc9bc721b03c6b148a8d876ace7384aa184001ef9d2ce5b0d7 -->

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
| 모델의 도구 요청 | 모델이 목록에 없던 도구를 이름으로 요청하더라도 실행하지 않습니다. |
| 여러 도구 요청 시 | 한 응답에서 여러 도구를 요청한 경우, 요청된 도구 전체의 권한을 확인한 후 실행을 시작합니다. |
| 도구 실행 직전 | 각 도구의 권한을 다시 확인합니다. 개인정보 보호도 함께 적용했다면, 허용된 원문을 복원하기 전에 확인합니다. |

개인정보 보호와 함께 사용할 때는 도구 권한과 개인정보 원문 공개 범위를 각각 설정합니다.

- `AuthorizationManager<ToolAuthorizationContext>`는 현재 사용자의 권한에 따라 모델에
  보여 줄 도구와 실행을 허용할 도구를 결정합니다.
- `tools.disclosures`는 권한이 허용된 도구에 어떤 유형의 개인정보를 원문으로 전달할지
  결정합니다.

도구 실행 권한이 있어도 개인정보 원문이 자동으로 전달되지는 않습니다. 권한 정책이
도구 실행을 허용하고 `tools.disclosures`에 해당 개인정보 유형을 설정한 경우에만 원문을
전달합니다.

## Spring Boot 스타터 추가

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

[권한 정책 정의](#권한-정책-정의)의 예시처럼
`AuthorizationManager<ToolAuthorizationContext>` Bean을 등록합니다.
그러면 스타터가 도구 권한 검사를 적용할 클라이언트를 만들 수 있도록
`ToolAuthorizationChatClientFactory`를 제공합니다. 개인정보 분석기는 필요하지 않습니다.

### 개인정보 보호와 함께 사용

기본 Privacy Guardrails 스타터 또는 이를 포함하는 분석기 스타터를 추가하고, 사용하는
모든 Privacy Guardrails 모듈의 버전을 동일하게 맞추세요.
[시작하기](getting-started.md)를 참고해 Regex, Presidio, OpenNLP 또는 사용자 정의
`PiiAnalyzer`를 구성합니다. 개인정보 보호와 도구 권한 검사가 모두 구성되면
스타터가 `PrivacySecurityChatClientFactory`도 제공합니다.

## 권한 정책 정의

도구 권한 정책을 `AuthorizationManager<ToolAuthorizationContext>` Bean으로 등록합니다.
`AuthorizationManager`는 Spring Security의 표준 확장 인터페이스입니다.

권한 정책은 `Authentication`에서 사용자 정보를, `ToolAuthorizationContext`에서
도구의 명세(`ToolDefinition`)와 권한 확인 단계를 받습니다. 도구에 입력하는 실제 값과
요청 원문은 권한 정책에 전달하지 않습니다. 예를 들어 사용자가 `customerLookup` 도구를
사용할 수 있는지는 판단할 수 있지만, 조회할 실제 `customerId` 값은 받지 않습니다.
특정 고객의 조회 권한처럼 인자값을 확인해야 하는 검사는 도구나 애플리케이션 서비스에서
구현하세요.

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

의존성을 추가하고 권한 정책 Bean을 등록한 뒤, 아래 예시처럼 Factory로 `ChatClient`를
생성하세요. 도구 권한 검사만 적용하거나 개인정보 보호를 함께 적용할 수 있습니다.

개인정보 보호만 필요한 클라이언트는 `PrivacyChatClientConfigurer`를 사용하면 됩니다.

아래 예시의 `customerLookupToolCallback`은 애플리케이션이 Bean으로 등록한 고객 조회
도구입니다. `defaultTools(...)`로 등록하면 해당 클라이언트의 요청에서 사용할 수 있습니다.

### 도구 권한 검사만 적용

도구 권한 검사만 사용할 때는 다음과 같이 `ChatClient`를 구성합니다.

```java
@Bean
ChatClient authorizedToolClient(
        ChatModel chatModel,
        ToolAuthorizationChatClientFactory authorizationFactory,
        ToolCallback customerLookupToolCallback
) {
    return authorizationFactory.builder(chatModel)
            .defaultTools(customerLookupToolCallback)
            .build();
}
```

### 개인정보 보호와 함께 적용

개인정보 보호도 구성했다면 `PrivacySecurityChatClientFactory`를 사용하세요.
도구 권한 검사와 개인정보 보호를 함께 적용합니다.

```java
@Bean
ChatClient securedChatClient(
        ChatModel chatModel,
        PrivacySecurityChatClientFactory privacySecurityFactory,
        PrivacyToolCallbackFactory privacyToolCallbackFactory,
        ToolCallback customerLookupToolCallback
) {
    ToolCallback protectedCustomerLookup =
            privacyToolCallbackFactory.wrap(customerLookupToolCallback);

    return privacySecurityFactory.builder(chatModel)
            .defaultTools(protectedCustomerLookup)
            .build();
}
```

통합 Factory는 개인정보 보호도 구성합니다. 반환된 builder에
`PrivacyChatClientConfigurer`를 다시 적용하지 마세요.

예시처럼 개인정보 보호를 적용할 도구는 `PrivacyToolCallbackFactory`로 감싼 뒤 등록합니다.
도구에 원문으로 전달할 개인정보 유형은 `tools.disclosures`에 설정합니다. 다음 설정은
`customerLookup` 도구에 고객번호(`CUSTOMER_ID`)만 원문으로 전달합니다.

```yaml
spring:
  ai:
    privacy:
      tools:
        disclosures:
          customerLookup:
            - CUSTOMER_ID
```

## 도구 Advisor 구성

도구 호출 Advisor는 모델이 요청한 도구를 실행하고 그 결과를 모델에 전달하는 역할을
합니다. `ToolCallingAdvisor`와 도구 검색용 `ToolSearchToolCallingAdvisor`가 여기에
해당하며, Spring AI의 `ToolAdvisor`를 구현합니다.

Factory로 생성한 클라이언트에도 대화 메모리나 RAG 같은 일반 Advisor를
`defaultAdvisors(...)`나 요청의 `advisors(...)`로 추가할 수 있습니다. 개인정보 보호도
함께 사용한다면 [Advisor 순서](configuration.md#사용자-정의-advisor-순서)를 참고해
추가한 데이터가 보호 검사를 거치도록 구성하세요.

도구 호출 Advisor는 Factory가 권한 검사를 연결해 구성합니다. 도구를 사용하는
클라이언트에서는 다음 규칙을 따르세요.

- Spring AI의 도구 호출 Advisor 자동 등록을 유지하세요.
  `spring.ai.chat.client.tool-calling.enabled`는 기본값이 `true`입니다.
  요청별로 `AdvisorParams.toolCallingAdvisorAutoRegister(false)`를 지정해서도 안 됩니다.
- `defaultAdvisors(...)`나 요청의 `advisors(...)`로 별도의 도구 호출 Advisor
  (`ToolAdvisor` 구현체)를 등록하면 거부됩니다. 도구 호출 방식을 바꾸려면 아래처럼
  builder를 Factory에 전달하세요.

### 사용자 정의 도구 Advisor

사용할 도구 호출 Advisor의 builder를 `factory.builder(chatModel, toolAdvisorBuilder)`에
전달하세요. Factory가 도구 권한 검사를 연결해 클라이언트를 구성합니다.

다음은 도구 권한 검사만 사용하는 클라이언트에서 도구 호출 Advisor의 실행 순서를
`100`으로 지정하는 예시입니다.

```java
@Bean
ChatClient customToolAdvisorClient(
        ChatModel chatModel,
        ToolAuthorizationChatClientFactory authorizationFactory,
        ToolCallback customerLookupToolCallback
) {
    ToolCallingAdvisor.Builder<?> toolAdvisorBuilder =
            ToolCallingAdvisor.builder().advisorOrder(100);

    return authorizationFactory.builder(chatModel, toolAdvisorBuilder)
            .defaultTools(customerLookupToolCallback)
            .build();
}
```

`100`은 예시값이며, 실제 실행 순서는 함께 사용하는 다른 Advisor에 맞춰 정하세요.
보호 구성과 맞지 않는 실행 순서는 거부됩니다.

Factory는 `ToolCallingAdvisor.Builder<?>`와 그 하위 builder를 지원합니다.
도구 검색용 builder를 전달하는 방법은 [Tool Search 예시](#tool-search)를 참고하세요.

**Advisor나 builder를 직접 구현할 때의 조건**

Factory는 전달받은 builder를 복사한 뒤, 복사본에 권한 검사용 `ToolCallingManager`를
설정해 Advisor를 만듭니다. 직접 작성한 Advisor나 builder도 이 과정을 따를 수 있도록
다음 조건을 지켜야 합니다.

- `copy()`로 복사한 builder도 같은 종류의 Advisor를 만들 수 있어야 하며,
  기존 설정값을 유지해야 합니다.
- `build()`로 만든 Advisor는 도구 권한 검사가 적용되도록 Factory가
  `toolCallingManager(...)`로 설정한 `ToolCallingManager`를 사용해야 합니다.
- 도구 호출 Advisor에는 `PriorityOrdered`를 구현하지 마세요. 권한 검사보다 먼저
  도구 호출을 시작할 수 있어 Factory가 거부합니다. 실행 순서는 builder의
  `advisorOrder(...)`로 지정하세요.

아래는 `ToolCallingAdvisor`를 상속한 최소 예시입니다. 도구 호출에는 기본 동작을
사용하며, builder를 복사하고 Advisor를 만드는 부분을 구현합니다.

```java
final class CustomToolCallingAdvisor extends ToolCallingAdvisor {

    private CustomToolCallingAdvisor(ToolCallingManager manager,
            ToolExecutionEligibilityChecker checker, int order, boolean history) {
        super(manager, checker, order, history);
    }

    static final class Builder extends ToolCallingAdvisor.Builder<Builder> {

        @Override
        public Builder copy() {
            return (Builder) super.copy();
        }

        @Override
        protected Builder newCopy() {
            return new Builder();
        }

        @Override
        public CustomToolCallingAdvisor build() {
            ToolCallingManager manager = getToolCallingManager();
            return new CustomToolCallingAdvisor(
                    manager,
                    getToolExecutionEligibilityChecker(),
                    getAdvisorOrder(),
                    isConversationHistoryEnabled()
            );
        }
    }
}
```

`super.copy()`는 부모 클래스인 `ToolCallingAdvisor.Builder`의 `copy()`를 뜻합니다.
세 메서드의 역할은 다음과 같습니다.

| 메서드 | 이 예시에서 하는 일 |
| --- | --- |
| `copy()` | 부모의 `copy()`에 복사를 맡기고, 결과를 이 예시의 `Builder` 타입으로 반환합니다. |
| `newCopy()` | 부모의 `copy()`가 호출합니다. 설정을 복사해 넣을 새 `Builder`를 만듭니다. |
| `build()` | `getToolCallingManager()`로 Factory가 설정한 `ToolCallingManager`를 읽어, 나머지 설정과 함께 `CustomToolCallingAdvisor` 생성자에 전달합니다. |

Factory가 builder에 권한 검사용 `ToolCallingManager`를 설정하면, 직접 작성한
`build()`는 `getToolCallingManager()`로 그 값을 받아 Advisor 생성자에 넘깁니다. 위 코드의
`manager` 변수가 그 값입니다.

생성자는 `super(manager, checker, order, history)`의 첫 번째 인자로 같은
`ToolCallingManager`를 부모 `ToolCallingAdvisor`에 전달합니다. 나머지 세 인자는 도구
실행 조건, Advisor 실행 순서, 대화 기록 사용 여부입니다. 부모 Advisor는 전달받은
`ToolCallingManager`로 도구를 호출합니다.

`ToolCallingManager`, 실행 조건, 실행 순서, 대화 기록 설정은 부모의 `copy()`가
새 builder에 복사합니다.

builder에 별도 설정 필드를 추가했다면, `copy()`에서 `super.copy()`로 얻은 복사본에
해당 필드의 값을 옮긴 뒤 반환하세요. `newCopy()`는 위 예시처럼 새 builder를 생성하는
역할을 맡습니다.

클라이언트를 만들 때는 이 builder를 Factory에 전달합니다.

```java
ChatClient client = authorizationFactory
        .builder(chatModel, new CustomToolCallingAdvisor.Builder().advisorOrder(100))
        .defaultTools(customerLookupToolCallback)
        .build();
```

## ToolCallingManager 선택

`ToolCallingManager`는 도구의 실제 실행을 담당합니다. 스타터의 기본 구성은
도구 권한 정책 Bean이 있을 때 Spring AI의 `DefaultToolCallingManager` Bean을
사용합니다.

기본 구성에서 이 Bean이 없거나 후보가 여러 개이면 애플리케이션 시작이 실패합니다.
사용할 `ToolCallingManager`를 직접 지정하려면 아래처럼 `SpringSecurityToolBoundary`
Bean을 제공하세요.

### 사용자 정의 ToolCallingManager

`ToolCallback`은 도구를 호출하는 객체입니다. 라이브러리는 실행을 위임할 때
애플리케이션 도구의 콜백을 권한 재검사용 콜백으로 감싸서, 사용자 정의
`ToolCallingManager`가 받는 `Prompt`의 도구 옵션에 넣습니다. 이 콜백은 권한을 확인한
뒤 기존 콜백을 호출합니다.

개인정보 보호를 함께 사용하면 `PrivacyToolCallbackFactory`로 감싼 콜백 바깥에
권한 재검사용 콜백이 자동으로 추가됩니다. 사용자 정의 `ToolCallingManager`는
`Prompt`로 전달받은 가장 바깥의 콜백을 호출해야 합니다.

다음 예시는 Spring AI의 기본 구현인 `DefaultToolCallingManager`에 실행을 위임합니다.
전달받은 `Prompt`와 모델 응답을 그대로 넘기면, 이 구현이 `Prompt`의 콜백을 사용해
실행하므로 권한 재검사가 유지됩니다.

```java
@Bean
SpringSecurityToolBoundary springSecurityToolBoundary(
        ToolCallingManager delegate,
        AuthorizationManager<ToolAuthorizationContext> authorizationManager
) {
    ToolCallingManager customManager = new ToolCallingManager() {
        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
            return delegate.resolveToolDefinitions(options);
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
            return delegate.executeToolCalls(prompt, response);
        }
    };

    return SpringSecurityToolBoundary.builder(customManager, authorizationManager)
            .build();
}
```

예시는 Spring AI의 `DefaultToolCallingManager` Bean을 `ToolCallingManager` 타입의
`delegate` 인자로 받습니다. 이미 작성한 사용자 정의 `ToolCallingManager`가 있다면
`customManager` 자리에 해당 객체를 전달하세요.

이 예시에서는 `delegate.executeToolCalls(prompt, response)` 안에서 콜백을 찾고,
도구 실행에 필요한 부가 정보인 `ToolContext`도 함께 전달합니다. 따라서 위임하는
코드에서 콜백 선택이나 컨텍스트 전달을 별도로 구현할 필요가 없습니다.

직접 작성한 `ToolCallingManager`도 `Prompt`에 담긴 도구 콜백을 통해 실행해야 합니다.
이 콜백이 도구 실행 직전에 권한을 다시 확인하므로, 원래 도구를 별도로 호출하면
이 검사가 빠집니다. 개인정보 보호 등 도구 실행에 필요한 정보를 유지하도록
컨텍스트도 함께 전달하세요.

스타터의 Factory는 위에서 등록한 `SpringSecurityToolBoundary` Bean을 사용해
클라이언트를 구성합니다. [ChatClient 구성](#chatclient-구성) 예시처럼 Factory로
클라이언트를 생성하고, 이 클라이언트를 통해 도구를 호출하세요.

## Spring Boot 스타터 없이 구성

`spring-ai-privacy-guardrails-spring-security`를 직접 사용한다면
`SpringSecurityToolBoundary`를 만들고, 여기서 제공하는 Advisor와 `ToolCallingManager`를
`ChatClient`에 연결하세요.

아래 예시는 준비한 모델, 권한 정책과 고객 조회 도구로 **도구 권한 검사만 적용하는**
클라이언트를 만듭니다. 스타터 없이 직접 구성할 때는 도구 호출 Advisor도
`defaultAdvisors(...)`에 등록합니다.

```java
ChatClient createAuthorizedClient(
        ChatModel chatModel,
        AuthorizationManager<ToolAuthorizationContext> authorizationManager,
        ToolCallback customerLookupToolCallback
) {
    SpringSecurityToolBoundary boundary = SpringSecurityToolBoundary.builder(
            ToolCallingManager.builder().build(), authorizationManager
    ).build();

    ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
            .toolCallingManager(boundary.toolCallingManager())
            .build();

    return ChatClient.builder(chatModel)
            .defaultAdvisors(
                    boundary.toolAuthorizationAdvisor(),
                    toolCallingAdvisor,
                    boundary.toolDefinitionAuthorizationAdvisor()
            )
            .defaultTools(customerLookupToolCallback)
            .build();
}
```

이 예시는 각 Advisor의 기본 실행 순서를 사용합니다. 다른 Advisor도 함께 구성한다면
다음 순서 조건을 지키세요.

- 도구 목록을 변경하는 Advisor는 `boundary.toolDefinitionAuthorizationAdvisor()`보다
  먼저 실행되도록 순서를 지정하세요. 사용자 정의 Advisor에서 콜백을 추가·교체하는 작업은
  그보다 앞선
  `boundary.toolAuthorizationAdvisor()`가 요청의 도구 목록을 확보하기 전에 끝내야 합니다.
- 개인정보 보호 Advisor도 직접 조합한다면, 모델에 전달할 내용을 보호하는
  `PrivacyModelBoundaryAdvisor` 다음에 도구 정의의 권한 검사가 실행되어야 합니다.
  두 Advisor는 기본 실행 순서 값이 같으므로, `defaultAdvisors(...)`에
  `PrivacyModelBoundaryAdvisor`를 먼저 등록하세요.

스타터의 Factory는 이 구성을 자동으로 연결합니다.
[ChatClient 구성](#chatclient-구성)에서 사용 예시를 확인할 수 있습니다.

## Tool Search

Tool Search는 모델이 필요한 도구를 검색해 선택하는 Spring AI의 선택 기능입니다.

`org.springframework.ai:spring-ai-tool-search-advisor`를 추가하고, 버전은 애플리케이션의
Spring AI BOM에 맞추세요.

다음은 도구 권한 검사, Tool Search와 개인정보 보호를 함께 적용하는 예시입니다.
도구 검색 Advisor의 builder를 Factory에 전달합니다. `ToolIndex`는 애플리케이션이
Bean으로 제공하는 도구 검색 인덱스입니다.

```java
@Bean
ChatClient toolSearchClient(
        ChatModel chatModel,
        PrivacySecurityChatClientFactory privacySecurityFactory,
        ToolIndex toolIndex
) {
    return privacySecurityFactory.builder(chatModel,
                    ToolSearchToolCallingAdvisor.builder().toolIndex(toolIndex))
            .build();
}
```

도구 권한 검사만 적용하려면 바로 위 코드에서 주입받는
`PrivacySecurityChatClientFactory privacySecurityFactory`를
`ToolAuthorizationChatClientFactory authorizationFactory`로 바꾸세요.
반환문도 `authorizationFactory.builder(...)`를 호출하도록 바꿉니다.
`builder(...)`에 전달하는 모델과 도구 검색 Advisor의 builder는 동일합니다.

도구는 클라이언트를 만들 때 `defaultTools(...)`로 등록하거나, 각 요청의 `tools(...)`로
등록합니다. 개인정보 보호를 함께 적용할 때는 `PrivacyToolCallbackFactory.wrap(...)`로
감싼 도구 콜백을 등록하세요.

Tool Search에는 요청마다 대화 ID도 필요합니다. 아래는 개인정보 보호를 함께 사용하는
클라이언트에 도구를 등록하고, 애플리케이션의 `conversationId`를 전달하는 예시입니다.

```java
ToolCallback protectedCustomerLookup =
        privacyToolCallbackFactory.wrap(customerLookupToolCallback);

String response = toolSearchClient.prompt()
        .user("Find customer CUST-123456.")
        .tools(protectedCustomerLookup)
        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
        .call()
        .content();
```

이 구성에서는 요청에 등록된 도구 중 권한이 허용된 도구만 검색 대상에 포함하고,
선택된 도구의 실행 직전에 권한을 다시 확인합니다. 개인정보 보호도 함께 사용하면
검색어에서 탐지된 개인정보를 검색 실행 전에 토큰으로 바꿉니다.

검색을 수행하는 도구 자체는 라이브러리가 허용하며, 애플리케이션의 권한 정책은 검색
대상 도구에 적용합니다. 요청 도중 도구를 교체하거나 지원하지 않는 방식으로 추가하면
실행을 거부합니다.

Tool Search를 직접 구성하면서 권한 검사가 없는 `ToolCallingManager`를 연결하면,
허용되지 않은 도구도 검색 대상에 포함될 수 있습니다. 직접 구성할 때의 주의 사항은
[위협 모델](threat-model.md)을 참고하세요.

## 동기·스트리밍·비동기 호출의 인증 정보

요청 처리 도중 도구가 다른 스레드에서 실행되어도, 권한 정책에는 요청 시작 시 확인한
사용자의 인증 정보(`Authentication`)를 전달합니다.

- **동기 호출:** 요청이 시작될 때 Spring Security 보안 컨텍스트에서 인증 정보를
  가져옵니다.
- **스트리밍:** Reactor의 보안 컨텍스트를 우선 사용합니다. 이 컨텍스트가 없을 때만
  호출 스레드의 인증 정보를 사용합니다. Reactor에 보안 컨텍스트가 등록되어 있지만
  인증 정보가 비어 있으면 요청을 거부합니다.
- **비동기 호출:** `ChatClient` 호출 자체를 다른 스레드에서 시작한다면 Spring Security의
  인증 정보도 함께 전달해야 합니다. 작업을 실행하는 executor에
  `DelegatingSecurityContextExecutorService`를 사용하면 인증 정보를 전달할 수 있습니다.
  가상 스레드를 사용하는 경우에도 같은 조건이 적용됩니다.

도구가 등록된 요청에 `Authentication`이 없으면 요청을 거부합니다.

`Authentication`은 요청 시작 시 사용자의 인증 정보를 나타냅니다. 실행 시점에도 권한
정책을 다시 확인하지만, 장시간 실행되는 요청 중 권한이 철회된 사실까지 반영하려면
정책에서 객체에 저장된 권한뿐 아니라 현재 권한 상태도 조회해야 합니다.

라이브러리는 동기 호출의 정상 완료·실패 또는 스트림의 완료·실패·취소 시 요청의
권한 상태를 정리합니다.

## 호환성

| 구성 요소 | 지원 계열 |
| --- | --- |
| Java | 17 이상 |
| Spring AI | `2.0.x` |
| Spring Boot | `4.x` |
| Spring Security | `7.x` |

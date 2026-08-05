# Spring AI Privacy Guardrails

[English](README.md) | [한국어](README.ko.md)

<!-- i18n-source: README.md -->
<!-- i18n-source-sha256: 60117c58be543ccbfafad6c207b97f8673580ab39c8b0609300dfe05ae7e504c -->

<p align="center">
  <img src="docs/images/hero.svg" alt="Spring AI Privacy Guardrails 실행 경계" width="100%">
</p>

탐지된 개인정보를 모델에 보내지 않습니다. 각 신뢰 도구에는 필요한
정보만 공개합니다. 모든 도구 결과는 도구 경계를 벗어나기 전에 다시
보호합니다.

Spring AI Privacy Guardrails는 Spring에 독립적인 개인정보 보호 `core`와
운영 환경을 고려한 Spring AI 통합을 결합하여 채팅, RAG, 메모리, 도구 호출과
출력 경계를 보호합니다. 교체 가능한 분석기가 민감한 범위(span)를 찾으면 이
프로젝트가 그 탐지 근거를 요청 단위 정책 집행으로 전환합니다.

## 왜 필요한가

탐지기는 **어떤 텍스트가 민감한지** 답합니다. 하지만 Spring AI
애플리케이션은 여전히 **원래 값이 어디까지 이동할 수 있는지** 결정해야
합니다.

이 프로젝트는 그동안 빠져 있던 실행 경계를 제공합니다.

```text
user + memory + RAG
        ↓
detect → resolve → request-scoped opaque tokens → model
                                            ↓
                         allow listed entity types per tool
                                            ↓
                         retokenize result → model/output
                                            ↓
                                   remove request mapping
```

## Starter 선택

대부분의 애플리케이션은 주 진입점 starter 하나를 선언합니다.

| 사용 사례 | 선언할 artifact |
| --- | --- |
| Presidio를 사용하는 일반 개인정보 보호(권장) | `spring-ai-privacy-guardrails-presidio-spring-boot-starter` |
| Regex 규칙 또는 사용자 정의 분석기만 사용 | `spring-ai-privacy-guardrails-spring-boot-starter` |
| JVM 전용 환경에서 기존 호환 OpenNLP 모델 사용 | `spring-ai-privacy-guardrails-opennlp-spring-boot-starter` |

Presidio와 OpenNLP starter에는 base starter, `core`, Spring AI 통합, Spring Boot
기본 구성이 이미 포함됩니다. 두 provider starter 중 하나를 사용할 때 base
starter를 함께 선언하지 마세요. Base starter에는 의도적으로 Presidio와
OpenNLP provider가 포함되지 않습니다.

provider starter 의존성만 추가한다고 개인정보 보호 기반 기능이나 Presidio 또는
OpenNLP 분석기가 자동으로 켜지지는 않습니다. 전역 개인정보 보호 설정과 사용할
분석기를 `application.yml`에서 명시적으로 활성화하고
[설정 문서](docs/ko/configuration.md#배포-artifact)에 따라 설정하세요.

## 빠른 시작

> **첫 릴리즈 전 안내:** `0.1.0`은 아직 Maven Central에서 받을 수 없습니다. 첫
> 릴리즈 전에는 이 저장소를 복제해 포함된 샘플을 실행하거나 소스에서 직접
> 빌드하세요. 아래 의존성 좌표는 첫 릴리즈 예정 좌표이며 배포 전에는 사용할 수
> 없습니다.

외부 서비스 없는 첫 실행에서는 base starter와 애플리케이션 전용 식별자를 위한
작은 Regex 규칙을 사용합니다.

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-spring-boot-starter:0.1.0"
}
```

```yaml
spring:
  ai:
    privacy:
      enabled: true
      output:
        enabled: true
        action: tokenize
      regex:
        enabled: true
        rules:
          - entity-type: EMPLOYEE_ID
            pattern: "\\bEMP-\\d{4}\\b"
            score: 0.90
```

보호가 필요한 `ChatClient`에 starter가 관리하는 경계를 명시적으로 적용합니다.

```java
@Bean
ChatClient privacyChatClient(
        ChatClient.Builder builder,
        PrivacyChatClientConfigurer privacyConfigurer
) {
    return privacyConfigurer.configure(builder).build();
}
```

`PrivacyChatClientConfigurer`는 필수 advisor 묶음을 한 번 설치하고 다른
`ChatClient`에는 영향을
주지 않습니다. 보호된 builder의 `clone()`과 보호된 클라이언트의 `mutate()`는
이미 그 묶음을 복사하므로 다시 설정하면 수명주기 경계가 중복되어 모델 실행
전에 실패합니다.

`PiiAnalyzer` 없이 개인정보 보호를 활성화하면 애플리케이션 시작이 실패합니다.
분석기는 기본 `UNION` 모드로 함께 구성할 수 있고, `REQUIRE_ALL`은 설정된 분석기
중 하나라도 실패하면 원문이나 provider 오류 세부 정보가 없는 안전한 예외로
차단합니다. 이 advisor 묶음은 설정된 `ChatClient` 호출만 보호하므로 `ChatModel`
직접 호출은 애플리케이션이 별도로 보호해야 합니다. 고급 구성, 정책, 직접 생성한
builder, 권한 범위가 지정된 도구와 테스트 지원은
[설정 문서](docs/ko/configuration.md)를 참고하세요.

## 권한 범위가 지정된 도구

`PrivacyToolCallbackFactory`로 감싼 도구는 기본 거부입니다. 대소문자를 구분하는
정확한 도구 이름마다 필요한 정규 엔티티 유형만 설정합니다.

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
@Bean
ToolCallback customerLookup(
        PrivacyToolCallbackFactory toolCallbackFactory,
        CustomerLookupTool delegate
) {
    return toolCallbackFactory.wrap(delegate);
}
```

MCP 또는 다른 `ToolCallbackProvider`를 사용할 때는 콜백을 배열로 고정하지 말고
명시적으로 선택한 클라이언트에서 provider를 감쌉니다.

```java
@Bean
ChatClient privacyMcpChatClient(
        ChatClient.Builder builder,
        PrivacyChatClientConfigurer privacyConfigurer,
        PrivacyToolCallbackFactory toolCallbackFactory,
        ToolCallbackProvider mcpTools
) {
    return privacyConfigurer.configure(builder)
            .defaultTools(toolCallbackFactory.wrapProvider(mcpTools))
            .build();
}
```

한 클라이언트에 여러 동적 provider가 있다면 등록 전에 하나로 합칩니다.

```java
toolCallbackFactory.wrapProviders(mcpTools, localToolProvider)
```

각 provider는 원본 콜백을 반환해야 합니다. 합쳐진 provider는 요청마다 한 번
갱신하고 provider 순서를 보존하며 중복 이름을 거부합니다. 등록만으로 원문 공개
권한이 생기지는 않습니다. 대소문자를 구분하는 정확한 최종 도구 이름에 설정한
엔티티 유형만 복원하며, 도구 결과의 새로운 개인정보는 다음 모델 호출 전에
재토큰화합니다. provider 갱신, 메타데이터, 실패 처리와 MCP prefix 계약은
[설정 문서](docs/ko/configuration.md#도구-원문-공개)를 참고하세요.

감싸지 않은 콜백과 provider는 명시적으로 이 계약의 범위 밖입니다. 감싼 콜백 또는
provider는 같은 개인정보 보호 starter로 설정한 `ChatClient`에만 등록하세요.
보호되지 않은 일반 클라이언트에는 원본 콜백 또는 provider를 등록해야 합니다.
활성 개인정보 보호 세션 없이 wrapper를 호출하면 위임 대상이 실행되기 전에
거부됩니다.

## 탐지와 정책 집행의 차이

<p align="center">
  <img src="docs/images/execution-boundary.svg" alt="탐지 provider, 개인정보 보호 core, Spring AI 실행 통합" width="100%">
</p>

| 계층 | 책임 | Spring 의존성 |
| --- | --- | --- |
| 분석기 provider | 유형, 원문 위치와 신뢰도 반환 | 없음 |
| 개인정보 보호 `core` | 정규화, 필터링, 중첩 해석과 토큰 세션 소유 | 없음 |
| Spring AI 통합 | 모델, 도구, 출력, 수명주기 경계 집행 | Spring AI |

provider와 모듈의 역할 구분은 [아키텍처](docs/ko/architecture.md)를 참고하세요.

## 집행하는 항목

- `ChatClient` 요청마다 하나의 불투명 `PrivacySession`을 사용합니다.
- 메모리와 RAG advisor 이후, 모델로 전달되기 직전에 최종 보호를 적용합니다.
- 한 요청 안에서는 안정적인 식별성을 유지하고 요청이 바뀌면 무작위
  이름 공간을 사용합니다.
- 명시적인 provider 실패 정책과 개인정보 보호 우선의 중첩 해석을
  적용합니다.
- 와일드카드 권한 없이 정확한 도구와 엔티티 유형 범위로 원문 공개를
  제한합니다.
- JSON 도구 인자를 손실 없이 보호하며, 지수형 숫자
  개인정보는 허용된 도구 안에서만 원래 숫자 타입으로 복원합니다.
- 일반 도구 결과는 모델에 돌아가기 전에 재토큰화하고, 개발자가 활성화한
  `returnDirect` 결과는 애플리케이션에 반환하기 전에 보호합니다.
- 출력 보호가 활성화되면 스트리밍 API는 지원하지만, 보호된 텍스트를 토큰
  단위로 즉시 전달하는 대신 완전한 논리 응답을 버퍼링하고 검증한 뒤
  재생합니다.
- 성공, 오류, 취소와 스트림 종료 시 정리합니다.
- 분석기와 개인정보 보호 경계에서 발생한 실패는 민감한 정보를 제거합니다. 권한
  있는 위임 대상 도구의 예외는 원형대로 전파하여 재시도, 대체 처리와 진단 정보를
  호스트 애플리케이션이 제어하게 합니다.

`returnDirect`는 도구 호출 흐름만 제어하며 라이브러리는 각 위임 대상 도구의
설정을 그대로 보존합니다. 두 설정의 콜백을 함께 등록할 수 있습니다. Spring AI는
한 응답에서 실제로 선택한 콜백이 모두 `returnDirect = true`일 때만 즉시 반환하고,
그렇지 않으면 토큰화된 결과로 모델 호출 흐름을 계속합니다. 모든 도구 결과를 먼저
재토큰화하므로 출력 보호가 꺼져 있어도 `returnDirect` 결과에는 `TOKENIZE` 보호가
남습니다.

`output.enabled`는 최종 모델 출력 검사만 제어하며 기본값은 `false`입니다.
비활성화하면 입력, 모델, 도구 보호와 증분 스트리밍은 유지되지만 최종 모델
출력은 이 라이브러리의 검사 경계 밖입니다. 활성화하면 각 논리 응답을 버퍼링하고
`TOKENIZE`, `REDACT` 또는 `BLOCK`을 적용한 뒤 재생합니다.

일반 빌드는 모델, 도구, 출력과 수명주기 경계에 대한 집중 단위 테스트와 통합
테스트를 실행합니다.

## 실행 가능한 Inspector

샘플에는 결과가 결정적인 로컬 `ChatModel`이 포함되어 있으므로 클라우드 자격
증명이 필요하지 않습니다.

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:run
```

`http://127.0.0.1:8080`을 열어 샘플 전용 **Privacy Boundary Inspector**를
실행하세요. 분석기의 탐지 근거, 실제로 토큰화된 모델 입력, 모델이 생성한
도구 인자, CRM 도구 내부에서만 공개된 단 하나의 엔티티 유형, 결과
재토큰화, `activeSessionsAfterCall = 0`을 확인할 수 있습니다.

<p align="center">
  <img src="docs/images/privacy-boundary-inspector-demo.gif" alt="모델의 원문 개인정보 0건, 범위가 지정된 도구 공개 1건, 호출 후 활성 세션 0개를 보여주는 Privacy Boundary Inspector" width="960">
</p>

데모에 설정한 탐지 규칙 밖의 텍스트는 결과가 결정적인 모델이 변경하지 않고
반환할 수 있습니다. Inspector는 토큰 매핑을 노출하지 않습니다. API 예제, 선택형
Docker Presidio와 JVM 전용 OpenNLP 구성, 로컬 Streamable HTTP MCP 왕복 테스트는
[샘플 가이드](samples/spring-ai-demo/README.md)에 설명되어 있습니다. 같은 가이드에는
실제 모델 endpoint를 대상으로 동기식 호출, 스트리밍, 도구 호출 흐름과
`returnDirect` 경계를 검증하는 선택형 OpenAI 호환 실제 연동 harness도 있습니다.
일반 저장소 검증은 이 harness를 컴파일하지만 클라우드 요청은 실행하지 않습니다.

## Artifact와 모듈

아래 표에는 starter가 아닌 런타임 구성 요소 네 개와 별도로 배포하는 테스트 지원
모듈이 있습니다. 위에 나열한 provider starter는 해당 런타임 모듈을 전이
의존성으로 가져오며, 테스트 지원은 애플리케이션의 테스트 범위에 별도로
추가합니다.

| Artifact | 목적 |
| --- | --- |
| `spring-ai-privacy-guardrails-core` | 분석기 SPI, 해석, 세션, Regex와 토큰화 |
| `spring-ai-privacy-guardrails-spring-ai` | Advisor와 권한 범위가 지정된 도구 wrapper |
| `spring-ai-privacy-guardrails-presidio` | Presidio Analyzer HTTP 어댑터 |
| `spring-ai-privacy-guardrails-opennlp` | 사용자 제공 OpenNLP 모델용 JVM 전용 어댑터 |
| `spring-ai-privacy-guardrails-test` | 선택형 모델/도구 probe와 AssertJ 검증문, `testImplementation`으로 추가 |

이 모듈들은 서로 다른 정책 구현이 아니라 배포 artifact 경계입니다. `core`는
탐지 근거 해석과 토큰 식별 정보를 소유하고, Spring AI 모듈은 모델과 도구 실행
경계를 소유합니다. 빌드는 소스 모듈과 배포 메타데이터에서 동일한 의존성 그래프를
검증합니다.

저장소 전용 벤치마크 모듈은 라이브러리 artifact로 배포되지 않습니다. 재현 가능한
JMH 구성과 해석 규칙은 [평가 문서](docs/ko/evaluation.md#저장소-벤치마크)에 설명되어
있습니다.

## 호환성과 상태

아직 공개 버전을 릴리스하지 않았습니다. 최초 공개 릴리스 전까지 API와 설정은
호환성용 중간 계층이나 마이그레이션 경로 없이 변경될 수 있습니다.

| 구성 요소 | 검증한 버전 |
| --- | --- |
| Java 기준 버전 | 21 |
| Java 호환성 CI | 25 |
| Spring AI | 2.0.0 |
| Spring Boot | 4.1.0 |
| Gradle wrapper | 9.6.1 |

CI는 Java 21과 25에서 전체 테스트를 실행합니다.

## 보안 경계

이 라이브러리는 Spring AI 실행 경로에서 우발적으로 개인정보가 공개될 위험을
줄이지만, 완전한 DLP 시스템이나 법률 준수 보장은 아닙니다.

인증, 인가, 전송 보안, 로그 정제, 영구 `ChatMemory`·벡터 저장소·데이터베이스
보호, 보존 정책, 탐지기 보정, 지원하지 않는 provider/애플리케이션 메타데이터와
텍스트가 아닌 미디어는 애플리케이션이 책임져야 합니다. 분석기 서비스는 인증되고
암호화된 네트워크 경계 안에 배치해야 합니다.

운영 환경에서 사용하기 전에 [보안 정책](SECURITY.md),
[위협 모델](docs/ko/threat-model.md), [아키텍처](docs/ko/architecture.md)를
읽어보세요.

## 빌드와 검증

```bash
./gradlew --no-daemon clean check
```

이 명령은 테스트와 저장소의 모듈·문서 검사를 실행합니다.
[평가 문서](docs/ko/evaluation.md)는 합성 Regex 기준선과 그 한계를
설명합니다.

## 기여하기

[기여 가이드](CONTRIBUTING.md)를 참고하세요. 모든 기여는 Apache License
2.0으로 제공됩니다.

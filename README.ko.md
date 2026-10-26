# Spring AI Privacy Guardrails

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ultramancode/spring-ai-privacy-guardrails-chat-client-spring-boot-starter?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.ultramancode/spring-ai-privacy-guardrails-chat-client-spring-boot-starter)
[![CI](https://github.com/ultramancode/spring-ai-privacy-guardrails/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ultramancode/spring-ai-privacy-guardrails/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[English](README.md) | [한국어](README.ko.md) | [문서](https://ultramancode.github.io/spring-ai-privacy-guardrails/ko/)

<!-- i18n-source: README.md -->
<!-- i18n-source-sha256: 14fc4f09926e1ce5b18ae195d9dc3a42bcec2ffc43f34bab22e8beb921ccf2d7 -->

**모델에 전달할 개인정보를 토큰으로 바꾸고, 도구에는 필요한 원문만 공개합니다. 도구 결과는 다시 보호합니다.**

| 원하는 동작 | 사용할 기능 |
| --- | --- |
| 모델에는 탐지된 개인정보를 감추고 도구별로 원문 공개 범위를 지정 | [Privacy](docs/ko/privacy.md) |
| 현재 사용자의 권한에 따라 모델에 공개할 도구와 실행할 도구를 제한 | [Tool Authorization](docs/ko/security.md) |
| 규칙·로컬 ONNX·HTTP 검사 모델로 요청과 응답을 점검 | [Content Inspection](docs/ko/inspection.md) |

세 기능은 독립적으로 선택하거나 함께 사용할 수 있습니다.

<p align="center">
  <a href="docs/images/hero.svg"><img src="docs/images/hero.svg" alt="개인정보 토큰화와 도구별 원문 공개 흐름, 선택 가능한 도구 권한과 콘텐츠 검사" width="100%"></a>
</p>

**외부 소개**

- **Spring 공식 블로그** — [This Week in Spring](https://spring.io/blog/2026/08/18/this-week-in-spring-august-18-2026/)에 소개
- **Spring AI Community** — [Awesome Spring AI의 Extensions and Forks](https://github.com/spring-ai-community/awesome-spring-ai#extensions-and-forks)에 소개

<br>

## 애플리케이션에 적용

Java 17 이상, Spring AI 2.0.0 이상, Spring Boot 4.0.0 이상을 사용합니다.
[저장소 설정과 Maven 안내](docs/ko/getting-started.md#add-the-client-starter)를 확인하세요.

클라이언트 스타터를 추가합니다.

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-chat-client-spring-boot-starter:0.4.0"
}
```

이 개인정보 보호 예제에서는 이메일을 탐지하도록 `COMMON` 프리셋을 활성화합니다.
`src/main/resources/application.yml`에 다음 설정을 추가합니다.

```yaml
spring:
  ai:
    privacy:
      rules:
        enabled: true
        presets: [COMMON]
```

애플리케이션의 `@Configuration` 클래스에서 `GuardrailsChatClientFactory`로 클라이언트를 생성합니다.
정책에서 개인정보 보호를 선택합니다.

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

서비스에서 이 빈을 주입받아 호출합니다.

```java
String response = chatClient.prompt()
        .user("Contact alice@example.com.")
        .call()
        .content();
```

모델에는 다음 입력이 전달됩니다. 토큰의 난수 부분은 `<opaque>`로 줄여 표시했습니다.

```text
Contact [PII_EMAIL_ADDRESS_1:<opaque>].
```

애플리케이션에 필요한 기능을 설정합니다.

| 기능 설정 | 정책에서 선택 |
| --- | --- |
| [개인정보 보호](docs/ko/getting-started.md#privacy-protection) | `privacy()` |
| [도구 권한](docs/ko/getting-started.md#tool-authorization) | `toolAuthorization(true)` |
| [콘텐츠 검사](docs/ko/getting-started.md#content-inspection) | `inspection()` |

기능 조합, 도구 등록과 동작 확인은 [시작하기](docs/ko/getting-started.md)를 참고하세요.

## 도구별 원문 공개

모델에는 탐지된 값을 불투명 토큰으로 전달합니다. 도구에는 정책이 허용한 유형만 실행 직전에
원문으로 복원하고 결과를 다시 보호합니다.

`chatClient` 빈에서 정책의 개인정보 보호 설정을 변경합니다.

```java
ToolDisclosurePolicy toolDisclosurePolicy = ToolDisclosurePolicy.allowEntityTypesByToolName(
        Map.of("sendEmail", List.of("EMAIL_ADDRESS")));
GuardrailsPolicy.Privacy privacySettings = GuardrailsPolicy.Privacy.builder()
        .toolDisclosurePolicy(toolDisclosurePolicy)
        .build();
policy = policy.mutate()
        .privacy(privacySettings)
        .build();
```

이 정책은 앱의 `sendEmail` 도구에 `EMAIL_ADDRESS` 원문만 전달하도록 허용합니다.
다른 엔티티 유형과 도구에는 불투명 토큰을 유지합니다.

[도구 등록과 MCP 연동](docs/ko/privacy.md#도구별-원문-공개)을 참고하세요.
사용자 정의 공개 로직은 [별도 정책 클래스](docs/ko/privacy.md#custom-tool-disclosure-policy)로 구현할 수 있습니다.

## 필요한 기능 추가

| 목적 | 다음 단계 |
| --- | --- |
| 사번·고객번호 등 자체 식별자 탐지 | [Java 규칙과 프리셋](docs/ko/privacy.md#로컬-규칙과-프리셋) |
| 더 넓은 탐지 범위 또는 NER 모델 사용 | [Presidio·OpenNLP 선택](docs/ko/privacy.md#choose-an-analyzer) |
| 사용자별 도구 공개·실행 권한 | [Spring Security 최소 설정](docs/ko/security.md#spring-boot-스타터-추가) |
| 입력 검사와 선택적 출력 검사 | [콘텐츠 검사 최소 설정](docs/ko/inspection.md#입력-검사-설정) |
| 최종 응답의 개인정보 토큰화·마스킹·차단 | [출력 보호](docs/ko/privacy.md#출력-정책과-스트리밍) |
| 예상과 다른 동작 진단 | [문제 해결](docs/ko/troubleshooting.md) |

## 샘플

샘플 애플리케이션은 고정된 예제로 Local Tool, RAG, MCP, Security, Inspection의 동작을 보여줍니다.
화면과 실행 방법은 [샘플 가이드](docs/ko/sample.md)를 참고하세요.

<p align="center">
  <a href="docs/images/privacy-boundary-inspector-demo-ko.gif"><img src="docs/images/privacy-boundary-inspector-demo-ko.gif" alt="Local Tool·RAG·MCP·Security·Inspection을 보여주는 Inspector" width="960"></a>
</p>

<p align="center">
  <strong>데모 영상:</strong>
  <a href="https://youtu.be/vir-x78e9j8">한국어</a> ·
  <a href="https://youtu.be/IeeA5ogIX_I">English</a>
</p>

## 호환성

| 구성 요소 | 최소 지원 버전 |
| --- | --- |
| Java | 17 |
| Spring AI | 2.0.0 |
| Spring Boot | 4.0.0 |
| Spring Security | 7.0.0 |

Java, Spring AI, Spring Boot, Spring Security의 새 버전이 출시되면
호환성을 지속적으로 검증하고 지원합니다.
Boot 스타터의 Spring Security 버전은 Spring Boot BOM이 선택합니다.

## 상세 문서

- [시작하기](docs/ko/getting-started.md): 공통 설정과 개인정보 보호·도구 권한·콘텐츠 검사 시작 안내
- [설정](docs/ko/configuration.md): 의존성, 클라이언트 생성과 정책 재사용
- [개인정보 보호](docs/ko/privacy.md): 분석기, 토큰화, 도구 원문 공개, 출력 보호와 처리 한도
- [Spring Security 도구 권한](docs/ko/security.md): 도구 권한 정책, 고급 도구
  구성, Tool Search와 보안 컨텍스트 전달
- [콘텐츠 검사](docs/ko/inspection.md): 선택적 규칙, 로컬 ONNX 분류 모델과 HTTP 검사 모델
- [아키텍처](docs/ko/architecture.md): 모듈과 모델·도구·세션 실행 흐름
- [위협 모델](docs/ko/threat-model.md): 보호 대상, 신뢰 경계, 통제, 한계와 별도 관리 영역
- [평가와 벤치마크](docs/ko/evaluation.md): 검증 항목과 해석 범위
- [샘플 가이드](docs/ko/sample.md): Inspector 시나리오, 런타임 엔드포인트와 각
  화면의 검증 근거
- [전체 샘플 가이드](samples/spring-ai-demo/README.ko.md): 선택적 분석기와 실제 모델 연동
  예제

## 빌드와 검증

```bash
./gradlew --no-daemon clean check
```

이 명령은 테스트를 실행하고 저장소의 모듈 규칙을 검사합니다.
번역 동기화는 `./gradlew verifyDocTranslations`로 별도 확인할 수 있습니다. 데모 분석기 회귀 테스트와
JMH 벤치마크는 [평가 문서](docs/ko/evaluation.md)를 참고하세요.

## 기여하기

[기여 가이드](CONTRIBUTING.md)를 참고하세요. 모든 기여는 Apache License 2.0으로
제공됩니다.

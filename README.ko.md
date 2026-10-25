# Spring AI Privacy Guardrails

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ultramancode/spring-ai-privacy-guardrails-spring-boot-starter?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.ultramancode/spring-ai-privacy-guardrails-spring-boot-starter)
[![CI](https://github.com/ultramancode/spring-ai-privacy-guardrails/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ultramancode/spring-ai-privacy-guardrails/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[English](README.md) | [한국어](README.ko.md) | [문서](https://ultramancode.github.io/spring-ai-privacy-guardrails/ko/)

<!-- i18n-source: README.md -->
<!-- i18n-source-sha256: ca80ea83116ae915602929bf0c647b504c382b6f9874a0e2b2f3c5dad9d35bf8 -->

**모델에 전달할 개인정보를 토큰으로 바꾸고, 도구에는 필요한 원문만 공개합니다. 도구 결과는 다시 보호합니다.**

**[API 키 없이 데모 실행](docs/ko/sample.md#run-the-inspector)** · **[기존 Spring AI 앱에 적용](#애플리케이션에-적용)**

| 원하는 동작 | 사용할 기능 |
| --- | --- |
| 모델에는 탐지된 개인정보를 감추고 도구별로 원문 공개 범위를 지정 | [Privacy](docs/ko/privacy.md) |
| 현재 사용자의 권한에 따라 모델에 공개할 도구와 실행할 도구를 제한 | [Tool Authorization](docs/ko/security.md) |
| 규칙·로컬 ONNX·HTTP 검사 모델로 요청과 응답을 점검 | [Content Inspection](docs/ko/inspection.md) |

세 기능은 독립적으로 선택하거나 함께 사용할 수 있습니다. 분석기는 민감정보를 식별하고,
이 라이브러리는 지원되는 Spring AI 호출 경로에서 탐지 결과와 정책을 적용합니다.

<p align="center">
  <a href="docs/images/hero.svg"><img src="docs/images/hero.svg" alt="개인정보 탐지, 모델 입력 토큰화와 도구별 원문 공개 흐름" width="100%"></a>
</p>

[큰 그림 보기](docs/images/hero.svg) · **데모 영상:** [한국어](https://youtu.be/vir-x78e9j8) · [English](https://youtu.be/IeeA5ogIX_I)

**외부 소개:** [Spring 공식 블로그](https://spring.io/blog/2026/08/18/this-week-in-spring-august-18-2026/) · [Awesome Spring AI](https://github.com/spring-ai-community/awesome-spring-ai#extensions-and-forks)

## 샘플 실행

기본 샘플은 로컬 모델과 고정 예제로 보호 흐름을 보여줍니다. 외부 서비스 계정이나 모델
API 키가 필요하지 않습니다. JDK 17을 설치하고 [저장소 준비 안내](docs/ko/sample.md#run-the-inspector)에 따라
저장소 루트에서 실행하세요.

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:run
```

`http://127.0.0.1:8080`의 **Local Tool**에서 모델 원문 노출 `0/4`, 허용 원문 복원 `1/1`,
도구 결과 재보호와 `PASS`를 확인하세요. 이 수치는 고정 예제의 확인 결과입니다.
RAG·MCP·Security·Inspection의 관찰 항목은 [샘플 가이드](docs/ko/sample.md)에 있습니다.

<p align="center">
  <img src="docs/images/privacy-boundary-inspector-demo-ko.gif" alt="모델에는 토큰, 허용된 도구에는 고객번호 원문을 전달하는 Inspector" width="960">
</p>

## 애플리케이션에 적용

Java 17, Spring AI 2.0.0, Spring Boot 4.0.0 이상이며, 모델 연결이 구성된 `ChatModel`이
있는 앱을 전제로 합니다. 아래 `0.4.0-SNAPSHOT`은 개발 버전입니다.
[로컬 SNAPSHOT 준비와 Maven 설정](docs/ko/getting-started.md#add-the-client-starter)을 확인하세요.

클라이언트 스타터를 추가합니다.

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-chat-client-spring-boot-starter:0.4.0-SNAPSHOT"
}
```

`src/main/resources/application.yml`에서 내장 프리셋을 선택합니다. 사용자 정의 규칙 Bean은 필요하지 않습니다.

```yaml
spring:
  ai:
    privacy:
      rules:
        enabled: true
        presets: [COMMON, KR]
```

`COMMON`은 이메일·카드번호, `KR`은 한국 휴대전화번호·주민등록번호의 지원 형식을 탐지합니다.
이름·주소 전반을 탐지하는 기능은 아닙니다. [지원 범위와 다른 분석기 선택](docs/ko/getting-started.md#choose-an-analyzer)을 참고하세요.

애플리케이션의 `@Configuration` 클래스에서 보호된 클라이언트를 생성합니다.
의존성과 분석기 설정만으로 기존 클라이언트가 모두 보호되지는 않습니다.

```java
@Bean
ChatClient privacyChatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
    GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder().privacy().build();
    return guardrailsChatClientFactory.builder(chatModel, guardrailsPolicy).build();
}
```

서비스에서 `@Qualifier("privacyChatClient")`로 이 빈을 주입받아 호출합니다.
전체 import와 클래스는 [시작하기](docs/ko/getting-started.md#create-a-protected-client)에 있습니다.

```java
String response = privacyChatClient.prompt()
        .user("Contact alice@example.com or 010-2345-6789.")
        .call()
        .content();
```

**모델에 전달되는 입력**은 다음과 같습니다. `<opaque>`는 요청별 난수의 자리 표시자입니다.

```text
Contact [PII_EMAIL_ADDRESS_1:<opaque>] or [PII_PHONE_NUMBER_1:<opaque>].
```

`response`는 모델이 생성한 답변이므로 위 입력과 같다고 보장하지 않습니다.
[모델 입력 확인](docs/ko/getting-started.md#call-and-check) 방법으로 보호 여부를 확인하세요.
`ChatModel` 직접 호출은 이 클라이언트의 보호 범위에 포함되지 않습니다.

## 도구별 원문 공개

모델에는 탐지된 값을 불투명 토큰으로 전달합니다. 도구에는 정책이 허용한 유형만 실행 직전에
원문으로 복원하고 결과를 다시 보호합니다. 도구 등록만으로 원문 공개가 허용되지는 않습니다.

고객 조회를 구성하려면 **고객번호 탐지 규칙 → 규칙 선택 → 도구 등록 → 원문 공개 정책**이 필요합니다.
`CUSTOMER_ID`는 내장 프리셋에 없으므로 [완전한 고객 조회 예제](docs/ko/getting-started.md#local-and-mcp-tools)를 따라 설정하세요.
같은 안내에서 MCP 제공자와 최종 도구 이름을 사용하는 방법도 확인할 수 있습니다.

## 필요한 기능 추가

| 목적 | 다음 단계 |
| --- | --- |
| 사번·고객번호 등 자체 식별자 탐지 | [Java 규칙과 프리셋](docs/ko/privacy.md#로컬-규칙과-프리셋) |
| 더 넓은 탐지 범위 또는 NER 모델 사용 | [Presidio·OpenNLP 선택](docs/ko/getting-started.md#choose-an-analyzer) |
| 사용자별 도구 공개·실행 권한 | [Spring Security 최소 설정](docs/ko/security.md#spring-boot-스타터-추가) |
| 입력 검사와 선택적 출력 검사 | [콘텐츠 검사 최소 설정](docs/ko/inspection.md#입력-검사-설정) |
| 최종 응답의 개인정보 토큰화·마스킹·차단 | [출력 보호](docs/ko/getting-started.md#protect-final-responses) |
| 예상과 다른 동작 진단 | [문제 해결](docs/ko/troubleshooting.md) |

출력 보호·검사를 켜면 스트리밍 응답은 전체 결과를 검사한 뒤 전달됩니다.
모듈 구성은 [아키텍처](docs/ko/architecture.md), 처리 이벤트 관측은
[런타임 관측](docs/ko/privacy.md#개인정보-보호-런타임-관측)을 참고하세요.

## 호환성과 상태

| 구성 요소 | 최소 지원 버전 |
| --- | --- |
| Java | 17 |
| Spring AI | 2.0.0 |
| Spring Boot | 4.0.0 |
| Spring Security | 7.0.0 |

Spring AI 2.0.x, Spring Boot 4.x와 Spring Security 7.x를 사용합니다.
Boot 스타터의 Security 버전은 Spring Boot BOM이 선택합니다.

구조화된 JSON을 Presidio로 분석하려면 Presidio Analyzer 2.2.361 이상을 사용하세요.

## 상세 문서

- [시작하기](docs/ko/getting-started.md): 단계별 스타터 선택과 개인정보 보호 경계 설정
- [설정](docs/ko/configuration.md): 의존성, 클라이언트 생성과 정책 재사용
- [개인정보 보호](docs/ko/privacy.md): 분석기, 토큰화, 도구 원문 공개, 출력 보호와 처리 한도
- [Spring Security 도구 권한](docs/ko/security.md): 도구 권한 정책, 고급 도구
  구성, Tool Search와 보안 컨텍스트 전달
- [콘텐츠 검사](docs/ko/inspection.md): 선택적 규칙, 로컬 ONNX 분류 모델과 HTTP 검사 모델
- [아키텍처](docs/ko/architecture.md): 모듈과 모델·도구·세션 실행 흐름
- [위협 모델](docs/ko/threat-model.md): 보호 대상, 신뢰 경계, 통제, 한계와 별도 관리 영역
- [평가와 벤치마크](docs/ko/evaluation.md): 검증 항목과 해석 범위
- [샘플 / 데모 가이드](docs/ko/sample.md): Inspector 시나리오, 런타임 엔드포인트와 각
  화면의 검증 근거
- [전체 샘플 가이드](samples/spring-ai-demo/README.ko.md): 선택적 분석기와 실제 모델 연동
  예제

## 보안 경계

이 라이브러리는 지원되는 Spring AI 실행 경로에서 우발적으로 개인정보가 공개될 위험을
줄이지만, 완전한 DLP 시스템이나 법률 준수 보장은 아닙니다.

애플리케이션은 다음 영역을 별도로 관리해야 합니다.

- 인증, 권한 정책 설계, 애플리케이션 자체 실행 경로와 로깅 정책
- 저장된 `ChatMemory`·벡터 저장소·데이터베이스의 접근 제어 및 데이터 보존 정책
- 운영 환경에 맞는 분석기 품질 검증과 조정
- 라이브러리가 명시적으로 지원하는 추론 텍스트 외의 응답 메타데이터와 비텍스트 미디어
  보호
- 원격 분석기의 인증 및 전송 암호화

운영 환경에서 사용하기 전에 [보안 정책](SECURITY.md)과
[위협 모델](docs/ko/threat-model.md)을 확인하세요.

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

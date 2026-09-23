---
description: >-
  규칙과 HTTP guard 모델로 모델에 전달할 텍스트를 검사합니다.
  콘텐츠 판단을 개인정보 처리 및 도구 권한 검사와 함께 구성하는 방법을 설명합니다.
---

# 콘텐츠 검사

[English](../inspection.md) | **한국어**

<!-- i18n-source: docs/inspection.md -->
<!-- i18n-source-sha256: 21d092a0b6f9f23e347b70ee5d56085f455583eaac1ad9caade4a6a668f6f71f -->

콘텐츠 검사는 0.4.0 개발 버전에서 명시적으로 활성화하는 기능입니다. 도구 루프의 후속 호출을 포함해 매 업무 모델 호출 전에 텍스트를 평가합니다. 개인정보 처리나 도구 권한 검사를 대체하지 않으며, 검사가 완료됐다는 사실이 콘텐츠의 안전성을 보장하지는 않습니다.

## 모듈과 책임

| 모듈 접미사 (`spring-ai-privacy-guardrails-…`) | 책임 |
| --- | --- |
| `inspection-core` | Spring에 의존하지 않는 요청, 결과, 정책, 실행 계약 |
| `inspection-rules` | 문자열 및 RE2/J 규칙 매칭 |
| `inspection-openai-compatible` | 명시적으로 선택한 출력 프로토콜에 따라 별도 HTTP 클라이언트로 guard 모델 호출 |
| `inspection-spring-ai` | `ModelRequestBoundary`에서 지원하는 텍스트 추출 및 클라이언트별 검사 적용 |
| `inspection-spring-boot-starter` | 명시적 활성화와 빈 연결; 모델 백엔드를 자동으로 선택하지 않음 |

Inspector는 findings를 생성하고 완료 여부를 보고합니다. `InspectionService`는 결과를 검증하고 콘텐츠 정책 및 운영 실패 처리 정책을 적용합니다. Spring AI 통합 계층은 업무 모델 실행 전에 결정을 적용합니다. 공통 경계에서는 configurer 등록 순서와 관계없이 개인정보 처리, 도구 정의 권한 검사, 콘텐츠 검사 순으로 실행합니다.

## 클라이언트 명시적 구성

Inspection starter와 사용할 inspector 모듈을 추가합니다. `ContentInspector` 빈을 하나 이상 정의하고 검사를 활성화합니다.

```yaml
spring:
  ai:
    inspection:
      enabled: true
      max-segments: 64
      max-characters: 131072
      timeout: 10s
      failure-policy: FAIL_CLOSED
```

기본값이 `false`인 `enabled`를 제외하면 위 값들이 기본값입니다. Inspector 없이 활성화하면 애플리케이션 시작이 실패합니다. Inspector는 Spring 빈 순서(`@Order`)대로, `InspectionService`를 직접 생성하면 전달한 목록 순서대로 실행합니다. 차단 결정이 나면 이후 inspector는 실행하지 않습니다.

제공된 `InspectionChatClientConfigurer`를 선택한 클라이언트에 적용합니다. 개인정보 보호를 함께 사용한다면 두 configurer를 조합합니다.

```java
ChatClient client = ModelRequestBoundaryConfigurer.compose(
        privacyConfigurer, inspectionConfigurer)
    .configure(ChatClient.builder(chatModel))
    .build();
```

개인정보 보호와 권한 검사를 함께 사용한다면 `privacySecurityFactory.builderWithBoundary(chatModel, inspectionConfigurer).build()`를 사용합니다. 개인정보 보호 없이 권한 검사만 사용한다면 `ToolAuthorizationChatClientFactory.builderWithBoundary`를 사용합니다. 관리되는 도구 등록 방법은 [도구 권한](security.md)을 참고하세요. Inspection은 공유 `ChatModel`을 변경하거나 모든 클라이언트를 전역 구성하지 않습니다.

## HTTP guard 모델과 개인정보 처리

HTTP 모듈은 업무 `ChatClient`의 advisor 체인 외부에서 별도의 비스트리밍 HTTP 클라이언트를 사용합니다. 배포한 guard 모델과 맞는 프로토콜을 선택해야 합니다. OpenAI 호환 전송 형식을 지원한다는 사실만으로 guard 출력까지 호환되는 것은 아닙니다.

```java
ContentInspector inspector = OpenAiCompatibleContentInspector.kanana(
    "primary-guard",
    OpenAiCompatibleInspectionConfig.kanana(
        URI.create("http://127.0.0.1:8000/v1/chat/completions")));
```

`kanana`는 지원하는 Kanana prompt-guard 라벨을 파싱합니다. `jsonGuard`는 엄격한 `SAFE`/`UNSAFE` JSON 판정을 요구합니다. Endpoint는 chat-completions의 전체 URL입니다. API 키, 요청 타임아웃, 응답 바이트 한도는 `OpenAiCompatibleInspectionConfig`의 명시적 설정입니다. 리다이렉트는 사용하지 않습니다. 각 텍스트 segment마다 HTTP 요청을 한 번 보내며, 공통 segment 수 한도와 공유 deadline이 적용됩니다.

각 `ContentSegment`에는 해당 텍스트의 `PrivacyProcessingStatus`가 있습니다.

| 상태 | 의미 |
| --- | --- |
| `UNKNOWN` | 개인정보 처리가 완료됐는지 알 수 없음 |
| `UNPROCESSED` | 개인정보 처리를 적용하지 않았음을 호출자가 알고 있음 |
| `PROCESSED` | 설정된 개인정보 처리가 완료됨. 모든 개인정보를 탐지했다는 보증은 아님 |

HTTP inspector는 기본적으로 모든 segment가 `PROCESSED`일 것을 요구합니다. `UNKNOWN`이나 `UNPROCESSED` 콘텐츠를 전송하려면 `allowUnprocessedContent`를 명시적으로 설정해야 합니다. 서비스는 어떤 inspector도 실행하기 전에 처리 요구 조건을 검사하며, HTTP inspector도 직접 호출될 때 이를 검사합니다.

Starter는 개인정보 보호 통합을 사용할 수 있으면 그 처리 상태를 사용합니다. Boot 없이 사용한다면 `PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(request)`를 확인하는 `PrivacyProcessingStatusResolver`를 inspection configurer의 네 인자 생성자에 전달합니다. 반환값이 `true`이면 `PROCESSED`, `false`이면 `UNKNOWN`으로 매핑합니다. 마커가 없다고 처리가 전혀 없었다고 단정할 수는 없습니다. 기본 resolver인 `PrivacyProcessingStatusResolver.unknown()`은 `UNKNOWN`을 반환합니다. 사용자 정의 resolver는 신뢰하는 애플리케이션 코드이며, 현재 요청에서 추출하는 모든 텍스트의 상태를 제공합니다. 해당 텍스트에 대해 설정된 개인정보 처리가 완료되지 않았다면 처리 완료 표시를 붙이지 마세요.

## 결과, 정책, 실패

`inspectorId`는 모델 계열이나 프로토콜이 아닌, 구성된 인스턴스를 식별합니다. 서비스 안에서 ID는 고유해야 합니다. 이름을 받는 HTTP 팩토리를 사용하면 같은 구현의 두 인스턴스에 서로 다른 endpoint나 애플리케이션 정책을 적용할 수 있습니다.

`InspectionPolicy.evaluate(inspectorId, findings)`는 운영 실패 전에 수집한 유효한 부분 증거를 포함한 불변 findings를 받습니다. 반환값은 `ALLOW` 또는 `BLOCK`입니다. 기본 정책은 finding이 하나라도 있으면 차단합니다. 점수는 해당 inspector 안에서 해석하며 모델별 탐지 임계값은 provider가 담당합니다. 서로 다른 모델의 점수를 평균 내지 마세요.

`InspectionResult`는 완료 상태, `completedSegmentIds`, findings, 실패 코드를 구분합니다. `completedSegmentIds`에는 검사를 끝낸 segment ID만 포함하며, 해당 segment가 안전하다는 의미는 아닙니다. Finding은 검사가 덜 끝난 segment에서도 나올 수 있습니다. `COMPLETED`는 요청의 모든 segment를 끝까지 검사했을 때만 사용할 수 있습니다. 작업을 끝내지 못한 inspector는 부분 증거를 유지하면서 `FAILED`를 보고해야 합니다. Null 결과, 요청에 없는 segment ID, 실제 coverage와 다른 완료 주장은 SPI 계약 위반이며 `INVALID_RESULT`로 처리합니다.

| 실패 | 서비스 동작 |
| --- | --- |
| `TIMEOUT`, `TRANSPORT_ERROR`, `HTTP_ERROR`, `MODEL_ERROR`, `INVALID_RESPONSE`, `INCOMPLETE` | `FAIL_CLOSED`는 차단합니다. 명시적 `FAIL_OPEN`도 보존된 findings에 대해 콘텐츠 정책이 허용해야만 통과시킬 수 있습니다. |
| `CANCELLED`, `LIMIT_EXCEEDED`, `DISCLOSURE_DENIED`, `CONFIGURATION`, `UNSUPPORTED_CONTENT`, `INVALID_RESULT` | `InspectionException`을 던집니다. 콘텐츠 정책이나 `FAIL_OPEN`으로 허용할 수 없습니다. |

Findings를 정책에 전달하기 전에 결과를 검증합니다. 잘못된 segment 연결 정보는 버리고, 이미 보고된 강제 차단 실패 코드는 유지합니다. 타임아웃이 난 결과를 검사 완료로 바꾸지 않습니다. 취소 시 스레드 인터럽트 플래그를 유지합니다. Provider가 부분 결과를 반환할 수 있으면 실패 전에 수집한 findings도 보존합니다.

`InspectionReport`에는 정규화한 결과가 실행 순서대로 담깁니다. 앞선 차단으로 생략된 inspector는 결과 목록에 없습니다. `allowedAfterFailure()`로 실패 후 명시적 허용과 검사 완료를 구분할 수 있습니다. 서비스의 강제 차단 예외는 `report()`로 그때까지의 결과를 제공합니다. 입력 추출이나 요청 생성 단계에서 실패했다면 집계 보고서가 없습니다. Spring AI 경계에서 보고서의 결정이 `BLOCK`이면 `InspectionBlockedException`을 던져 업무 모델 호출을 막습니다.

## 관측

Boot에서 보고서와 강제 차단 실패를 모두 관측하려면 `InspectionObserver` 빈을 등록합니다. Boot 없이 사용한다면 inspection configurer에 전달합니다. 보고서만 관측할 때는 람다로 충분하며, 강제 차단 실패도 관측하려면 `onFailure`를 구현합니다.

```java
@Bean
InspectionObserver inspectionObserver() {
    return new InspectionObserver() {
        @Override
        public void onInspection(InspectionReport report) {
            auditDecision(report);
        }

        @Override
        public void onFailure(InspectionException failure) {
            auditFailure(failure.failure(), failure.report());
        }
    };
}
```

위 audit 메서드는 애플리케이션에서 구현하는 훅입니다. 일반 콘텐츠 차단은 `onInspection`으로, 강제 차단에 해당하는 검사 실패는 `onFailure`로 전달합니다. 콜백은 실행 흐름 안에서 동기 호출되며 여러 요청이나 스트리밍 worker에서 동시에 실행될 수 있으므로 신속하게 반환해야 합니다. Observer의 런타임 예외는 검사 결정을 바꾸지 않으며, 치명적인 JVM 오류는 숨기지 않습니다. 보고서에는 프롬프트나 응답 원문 대신 진단용 식별자와 findings가 들어갑니다. 사용자 정의 ID나 finding 코드에도 사용자 콘텐츠를 담지 마세요.

## 검사 범위와 한도

`ContentSegment`는 findings와 검사 완료 여부를 개별 ID로 추적하는 논리적인 텍스트 단위입니다. Spring AI 어댑터는 지원하는 system, user, assistant 메시지의 텍스트마다 segment 하나를 만들고, 도구 응답 메시지 안에서는 각 응답 본문마다 하나를 만듭니다. Role별로 합치지 않고 순서를 유지합니다. User 메시지 세 개는 segment 세 개가 되고, 응답 두 개가 담긴 도구 응답 메시지는 segment 두 개가 됩니다. Role은 텍스트를 담은 메시지의 역할이며 원래 출처나 신뢰도를 보증하지 않습니다. 기본 inspector는 대화 전체를 해석하지 않고 이 텍스트 단위들을 각각 평가합니다.

모델 출력, 도구 호출 인자, 도구 정의는 이 텍스트 검사의 범위에 포함되지 않습니다. 미디어, 사용자 정의 메시지 타입, structured-output 요청 모드는 검사 완료로 보고하지 않고 미지원 오류로 거부합니다.

공통 한도는 segment 수, Java UTF-16 코드 단위로 계산한 텍스트 총길이, 공유 검사 deadline입니다. 모델에 전달할 요청마다 적용하므로 도구 루프의 후속 호출에는 새 한도가 적용됩니다. 사용자 정의 동기 inspector는 deadline과 인터럽트에 협조해야 합니다. 인터페이스가 임의의 애플리케이션 코드를 강제 종료할 수는 없습니다.

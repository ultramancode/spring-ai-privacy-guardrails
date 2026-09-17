# 콘텐츠 검사

[English](../inspection.md) | **한국어**

<!-- i18n-source: docs/inspection.md -->
<!-- i18n-source-sha256: 7e981b179fb917586bc148602f739534491afb6c0fd2501c597a38447dd2f3d0 -->

콘텐츠 검사는 규칙, 로컬 ONNX 모델 또는 OpenAI-compatible endpoint를 사용해
매 모델 호출 전 텍스트를 검사합니다. 단독으로 사용하거나 Privacy 보호 및 도구 권한
검사와 함께 사용할 수 있습니다.

## 모듈

아티팩트 공통 접두사는 `spring-ai-privacy-guardrails-`입니다.

| 접미사 | 역할 |
| --- | --- |
| `inspection-core` | 순수 Java 요청, 결과, 정책 및 순차 실행 |
| `inspection-rules` | 문자열 및 선형 시간 RE2 규칙 |
| `inspection-onnx` | 로컬 ONNX 검사 및 Prompt Guard 2 어댑터 |
| `inspection-openai-compatible` | 전용 HTTP 전송 및 내부 모델 프로토콜 |
| `inspection-spring-ai` | 명시적인 ChatClient 단위 검사 연동 |
| `inspection-spring-boot-starter` | 선택적 공통 설정 및 Privacy 연동 |

필요한 구현 모듈을 선택하고 `ContentInspector` 빈을 등록한 뒤, 검사할 ChatClient에
configurer를 적용하세요. 스타터는 공통 설정을 제공하며, 구현체를 자동으로 선택하거나
다른 클라이언트를 변경하지 않습니다.

## 기본 사용

아래 타입은 `io.github.ultramancode.springai.privacy.inspection` 아래
`core`, `rules`, `springai` 패키지에 있습니다.

```java
var rules = new RuleBasedContentInspector(List.of(
    InspectionRule.literal("override-example", "ignore previous instructions")));
var service = new InspectionService(List.of(rules));
var inspection = new InspectionChatClientConfigurer(service);
ChatClient client = inspection.configure(ChatClient.builder(model)).build();
```

위 규칙은 API 연결 예시입니다. 애플리케이션의 검사 정책에 맞는 규칙을 제공하세요.

Boot에서는 `ContentInspector` 빈을 등록하고 다음 설정으로 활성화하세요.

```yaml
spring:
  ai:
    inspection:
      enabled: true
      failure-policy: BLOCK
      timeout: 10s
      max-segments: 64
      max-characters: 131072
      max-chunks: 256
```

주입받은 `InspectionChatClientConfigurer`를 선택한 builder에 적용하세요.
구현체 없이 활성화하면 시작에 실패합니다. `InspectionPolicy`, `InspectionService`
또는 configurer 빈을 제공해 기본 구성을 교체할 수 있습니다.
Configurer 생성자에 observer를 지정하면 요청·응답 본문 없이 `InspectionReport`만
전달받습니다. Observer의 예외는 검사 결정을 변경하지 않습니다.

## Privacy·도구 권한과 결합

실행 순서는 최종 Privacy 처리 → 도구 정의 권한 검사 → 콘텐츠 검사 → 업무 모델입니다.
사용하지 않는 경계는 생략합니다. 도구 결과가 추가된 다음 모델 호출도 매번 검사하며,
call과 stream에 동일하게 적용합니다.

기본 order는 Privacy `MAX_VALUE - 3`, 권한 `MAX_VALUE - 2`, 콘텐츠 검사
`MAX_VALUE - 1`, 모델 `MAX_VALUE`입니다. 이 순서는 도구 루프에서도 각 경계의
실행 순서를 유지합니다. Privacy와 함께 사용하려면 다음과 같이 구성하세요.

```java
var builder = inspection.configure(ChatClient.builder(model));
privacyConfigurer.forToolCallingAdvisorOrder(ToolCallingAdvisor.DEFAULT_ORDER)
    .apply(builder);
var client = builder.build();
```

스타터는 최종 Privacy 경계의 실제 처리 여부를 확인합니다.
수동 구성에서는 다음 resolver를 configurer 생성자에 전달하세요.

```java
request -> PrivacyModelBoundaryAdvisor.isModelContentProtected(request)
    ? ContentSegment.Representation.PRIVACY_PROTECTED
    : ContentSegment.Representation.AS_RECEIVED
```

기존 권한 팩토리에서는 terminal-boundary 연동 지점을 사용하세요.

```java
var client = privacySecurityFactory
    .builderWithTerminalBoundary(model, inspection)
    .build();

// ToolSearchToolCallingAdvisor.Builder를 포함한 사용자 정의 도구 루프:
var customClient = privacySecurityFactory
    .builder(model, toolAdvisorBuilder, inspection)
    .build();
```

`ToolAuthorizationChatClientFactory`도 Privacy 없이 같은 연동 지점을 제공합니다.
기존 오버로드는 유지합니다. Privacy·권한 Advisor의 기본 order는 0.3.0에서 변경되므로,
최종 경계 부근의 숫자를 하드코딩한 사용자 정의 Advisor는 업그레이드 시 확인하세요.
사용자 정의 도구 order도 예약된 경계 사이에 배치해야 합니다.

Configurer는 요청에 추가된 Advisor까지 포함해 체인을 검증하며, 검사 이후에는
Spring AI 모델 호출 Advisor만 실행되도록 합니다. 사용자 정의·관측 Advisor는 검사
앞에서 전체 호출을 감싸도록 배치하세요. 이 검증은 구성한 ChatClient 경로에 적용되며,
직접 모델 호출이나 사용자 정의 체인 구현에는 적용되지 않습니다.

## 텍스트 표현과 외부 전송

- `RAW`: 애플리케이션이 원문 지점에서 명시적으로 제공한 텍스트입니다.
- `AS_RECEIVED`: 현재 전달받은 텍스트로, 원문인지 보호되었는지 지정하지 않습니다.
- `PRIVACY_PROTECTED`: 설정한 개인정보 보호 처리를 거친 텍스트입니다.

로컬 검사기는 제공된 모든 표현을 검사할 수 있습니다. Spring 연동은 모델로 전달할
현재 텍스트를 검사하며, 이미 토큰화된 원문을 복원하지 않습니다. 원문 지점에서
검사하려면 애플리케이션에서 core API를 직접 호출할 수 있습니다.

원격 구현체는 `InspectionService`를 거치지 않고 직접 호출해도 기본적으로 보호된
표현을 요구합니다. 원문 또는 `AS_RECEIVED` 전송에는 `allowRawContent=true`를
명시해야 합니다. 공통 서비스는 모든 구현체의 전송 조건을 실행 전에 확인하며,
`FAIL_OPEN`으로 금지된 전송을 허용하지 않습니다.
사용자 정의 검사기도 기본적으로 보호된 표현을 요구합니다. 로컬 구현체는
`requiresProtectedContent()`를 재정의해 다른 표현을 허용할 수 있습니다.

메시지 역할은 유지하지만 출처가 불명확하면 `UNKNOWN`으로 둡니다.
user 역할에 RAG 텍스트가 포함될 수 있으며, system 역할 자체를 신뢰 근거로 삼지 않습니다.

## 구현체

### 규칙

`InspectionRule.literal(id, text)`는 문자열을 매칭합니다.
`InspectionRule.regex(id, category, expression)`는 Java의 백트래킹 정규식 대신
RE2/J를 사용합니다. 길이가 제한되고 ID가 고유한 규칙 1–256개를 지정하세요.
Lookaround와 역참조는 지원하지 않습니다. 결과에는 일치한 원문 대신 규칙 ID를
반환합니다. 규칙 집합은 애플리케이션에서 명시적으로 제공합니다.

### ONNX: Prompt Guard 2

ONNX 모듈은 Prompt Guard 2 이진 분류 변환 모델을 실행하는
`PromptGuardContentInspector`를 제공합니다. 아래 조건은 ONNX 모델의 공통 규격이
아니라 이 어댑터의 모델 입출력 계약입니다.

```java
var local = new PromptGuardContentInspector(
    PromptGuardConfig.defaults(Path.of("model.onnx"), Path.of("tokenizer.json")));
```

이 어댑터는 INT64 `input_ids`, `attention_mask`, 선택적인 `token_type_ids`와
FLOAT `logits[1][2]` 출력(BENIGN=0, MALICIOUS=1)을 요구합니다.
모델에 대응하는 tokenizer.json도 필요합니다.

512토큰 창과 기본 64토큰 중첩으로 전체 입력을 검사합니다. 기본 임계값은 0.5이며,
하나의 창이라도 조건을 충족하면 발견 항목으로 기록합니다. 구현체 간 점수는 평균을
내지 않으며, 입력을 자동으로 잘라내지 않습니다. 창 수 제한 초과는 실패로 처리합니다.

모델과 토크나이저 경로는 설정할 수 있습니다. 토큰화 방식, 입력 텐서 또는 출력 라벨의
의미가 다른 모델은 파일만 교체하는 대신, 해당 모델에 맞는 `ContentInspector`
구현체를 제공하세요.

사용이 끝나면 어댑터를 닫으세요. 인스턴스별 네이티브 작업량을 제한하기 위해 CPU 추론과
토크나이저 접근을 직렬화하고, 잠금 대기에도 공통 실행 기한을 적용합니다. Watchdog은
시간 초과 또는 인터럽트 시 네이티브 추론 중단을 요청합니다. 사용자 정의 동기 구현체도
요청의 처리 제한을 지키고 취소에 협력해야 합니다.

모델 파일은 번들하거나 자동으로 다운로드하지 않습니다. 사용 권한이 있는 모델과
토크나이저를 직접 제공하고 [모델 이용 조건](https://huggingface.co/meta-llama/Llama-Prompt-Guard-2-86M)을
확인하세요. 오프라인 배포에는 DJL 네이티브 토크나이저 라이브러리도 미리 준비해야 합니다.
Windows에서는 최신 JDK와 호환되는 [Visual C++ 런타임](https://onnxruntime.ai/docs/install/)을 사용하세요.

### OpenAI-compatible HTTP

```java
var remote = OpenAiCompatibleContentInspector.kanana(
    OpenAiCompatibleInspectionConfig.kanana(
        URI.create("http://127.0.0.1:8000/v1/chat/completions")));
```

URI는 base URL이 아닌 전체 endpoint입니다. 보호 대상 Spring AI ChatClient와
독립된 전용 JDK HTTP client를 사용합니다. 연결·요청·전체 검사 기한, 응답 크기 제한과
취소를 지원하며, 자동 redirect와 재시도는 수행하지 않습니다.

프로토콜은 명시적으로 선택합니다.

- `kanana(config)`: 모델의 chat-template 옵션과 단일 사용자 메시지·분류 토큰을
  사용합니다. 정확한 `<SAFE>`, `<UNSAFE-A1>`, `<UNSAFE-A2>`를 각각 발견 없음,
  `PROMPT_INJECTION`, `PROMPT_LEAKING`으로 변환합니다.
  이 1토큰 계약에 한해 length 종료를 허용합니다.
- `jsonGuard(config)`: instruction 모델에 고정 system 지시를 전달하며, 정확한
  `{"verdict":"SAFE"}` 또는 `{"verdict":"UNSAFE"}`를 요구합니다.
  추가 필드, 중복 키, 뒤따르는 JSON, 설명문, 도구 호출, 거부 응답, 잘린 JSON은 거부합니다.

각 프로토콜이 모델의 입력 형식과 허용 출력을 정의하므로, 사용하는 모델에 맞게 선택하세요.
Kanana 프로토콜은 개별 구간을 사용자 발화로 전달합니다. 모델별 입력 형식은
[공식 모델 설명](https://huggingface.co/kakaocorp/kanana-safeguard-prompt-2.1b)을 참고하세요.
vLLM은 호환 런타임 중 하나이며, 어댑터가 vLLM에 종속되지는 않습니다.
서버 배포와 보안 설정은 사용하는 런타임의 문서를 따르세요.

## 결정·실패·범위

결과는 완료 상태, 검사한 구간 ID, 발견 항목, 원문 없는 실패 코드를 구분합니다.
누락되거나 알 수 없는 검사 범위, 잘못된 모델 응답, 처리 제한, HTTP 실패와 시간 초과는
검사 성공으로 처리하지 않습니다.

등록한 모든 검사기는 필수이며 순서대로 실행합니다. 차단이 확정되면 중단하고, 앞서
발견한 항목은 이후 구간에서 실패하더라도 유지합니다. 기본 정책은 발견 항목과 실행
실패를 차단합니다. `FAIL_OPEN`을 명시하면 실패 상태와 `allowedAfterFailure=true`를
보존합니다. 취소, 전송 금지, 미지원 콘텐츠, 잘못된 구성은 `FAIL_OPEN`으로 우회하지
않으며, 기존 Privacy·권한 검사 실패도 별도로 유지합니다.

연동 범위는 매 업무 모델 호출 전의 표준 system/user/assistant 텍스트와 도구 응답
텍스트입니다. 사용자 정의 메시지 하위 타입과 미디어는 명시적으로 거부합니다.
Spring AI의 structured-output·format-instruction 컨텍스트도 최종 모델 Advisor가 검사
이후 텍스트를 추가할 수 있으므로 거부합니다. 명시적인 텍스트 프롬프트를 사용하세요.
현재 연동에서는 `call().entity(...)`를 지원하지 않습니다.

각 텍스트 구간은 독립적으로 검사합니다. 출력 검열, 도구 인자, 스키마, 메타데이터,
`returnDirect` 결과, 재작성, 재질문, 멀티모달 콘텐츠는 이 연동의 범위에 포함되지 않습니다.

기본 record와 예외에서는 입력 텍스트, endpoint 자격 증명, 모델의 원시 응답을 숨깁니다.
진단 ID에도 고객 데이터를 넣지 마세요. 애플리케이션 로깅, Spring AI payload 관측,
모델 서버 로깅에서의 데이터 전송은 별도로 관리해야 합니다.

## 검증

일반 테스트는 공통 정책, Spring AI 연동, 제한된 HTTP 응답 처리와 네이티브 ONNX 실행을
검증합니다. 선택적으로 실행하는 live 테스트는 Prompt Guard ONNX 추론과
OpenAI-compatible 모델 호출을 검증합니다.

테스트 명령, 모델 아티팩트, 검증 기록과 런타임 준비 방법은
[검사 테스트 가이드](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/scripts/inspection/README.md)를 참고하세요.

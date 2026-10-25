---
description: >-
  내장 프리셋으로 첫 개인정보 보호 호출을 실행하고, 필요한 도구·분석기·출력 기능을 추가합니다.
---

# 시작하기

[English](../getting-started.md) | **한국어**

<!-- i18n-source: docs/getting-started.md -->
<!-- i18n-source-sha256: 6aed6249d103c25821bdc9fe31321d8e57dc3c4aa7b208745a1f25355d19cea1 -->

기존 Spring AI 앱에 개인정보 보호를 추가하는 경로입니다. **1~4단계로 첫 호출을 완료**한 뒤
필요한 선택 기능을 추가하세요. 외부 분석 서비스나 사용자 정의 규칙은 첫 호출에 필요하지 않습니다.

[API 키 없이 데모 실행](sample.md#run-the-inspector) · [도구 권한만 사용](security.md#spring-boot-스타터-추가) · [콘텐츠 검사만 사용](inspection.md#입력-검사-설정)

## 사전 요구 사항

Java 17, Spring AI 2.0.0, Spring Boot 4.0.0 이상을 사용합니다. 애플리케이션에 `ChatModel`
빈과 해당 모델의 연결 설정이 있어야 합니다. 외부 모델을 사용한다면 그 모델의 인증 정보도 필요합니다.
클라이언트 스타터는 `GuardrailsChatClientFactory`를 제공합니다.

<a id="add-the-client-starter"></a>

## 1. 클라이언트 스타터 추가

다음 `0.4.0-SNAPSHOT` 예제는 개발 버전입니다. 저장소에서 먼저 `./gradlew publishToMavenLocal`을
실행하고, 사용 앱의 개발용 저장소 설정에 Gradle의 `mavenLocal()` 또는 Maven의 기본 로컬 저장소를 사용하세요.
정식 릴리스를 사용할 때는 모든 Guardrails 모듈을 실제 배포된 같은 버전으로 맞춥니다.
[로컬 SNAPSHOT 안내](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/516982fd6644f50f38b2ff3b6c4ce0a28c2fab39/CONTRIBUTING.md#development)

`build.gradle`에 추가합니다.

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-chat-client-spring-boot-starter:0.4.0-SNAPSHOT"
}
```

Maven 사용자는 `pom.xml`의 `dependencies`에 추가합니다.

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-chat-client-spring-boot-starter</artifactId>
    <version>0.4.0-SNAPSHOT</version>
</dependency>
```

<a id="enable-built-in-presets"></a>

## 2. 내장 프리셋 활성화

`src/main/resources/application.yml`에 추가합니다.

```yaml
spring:
  ai:
    privacy:
      rules:
        enabled: true
        presets: [COMMON, KR]
```

`COMMON`은 이메일·카드번호, `KR`은 한국 휴대전화번호·주민등록번호의 지원 형식을 탐지합니다.
사용자 정의 규칙 Bean은 필요하지 않습니다. 프리셋은 언어에 따라 자동 선택되지 않으며,
`COMMON`도 명시적으로 선택해야 합니다. [내장 규칙의 지원 범위](privacy.md#내장-규칙)를 확인하세요.

<a id="create-a-protected-client"></a>

## 3. 보호된 클라이언트 생성

애플리케이션의 컴포넌트 스캔 범위에 `PrivacyConfiguration.java`를 두고 프로젝트의 `package`를 추가합니다.
분석기 설정만으로 기존 클라이언트가 모두 보호되지는 않으므로 이 빈을 사용해야 합니다.

```java
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsPolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PrivacyConfiguration {
    @Bean
    ChatClient privacyChatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
        GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder().privacy().build();
        return guardrailsChatClientFactory.builder(chatModel, guardrailsPolicy).build();
    }
}
```

<a id="call-and-check"></a>

## 4. 호출하고 확인

같은 스캔 범위에 `PrivacyExample.java`를 두고 프로젝트의 `package`를 추가합니다.
애플리케이션의 기존 컨트롤러나 실행 코드에서 `PrivacyExample`을 주입받아 `reply()`를 호출하세요.

```java
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class PrivacyExample {
    private final ChatClient privacyChatClient;

    public PrivacyExample(@Qualifier("privacyChatClient") ChatClient privacyChatClient) {
        this.privacyChatClient = privacyChatClient;
    }

    public String reply() {
        return this.privacyChatClient.prompt()
                .user("Contact alice@example.com or 010-2345-6789.")
                .call()
                .content();
    }
}
```

모델에 전달되는 입력은 다음 형태입니다. `<opaque>`는 요청별 난수의 자리 표시자입니다.

```text
Contact [PII_EMAIL_ADDRESS_1:<opaque>] or [PII_PHONE_NUMBER_1:<opaque>].
```

`reply()`의 반환값은 모델이 생성한 답변이므로 위 문자열과 같을 필요는 없습니다.
실제 모델 입력은 테스트용 모델 프로브로 확인할 수 있습니다. [테스트 지원](privacy.md#테스트-지원)을 참고하세요.
앱 설정과 독립적으로 보호 흐름을 먼저 보려면 [Inspector의 고정 예제](sample.md)를 실행하세요.
같은 요청의 같은 값은 토큰을 재사용하고 요청 종료 시 매핑을 폐기합니다. 토큰 내부 형식에 의존하지 마세요.

여기까지가 첫 개인정보 보호 호출입니다. `ChatModel` 직접 호출은 이 보호 경계를 통과하지 않습니다.
예상과 다르면 [모델 입력 문제 해결](troubleshooting.md#raw-input)을 확인하세요.
최종 응답 보호는 기본적으로 꺼져 있으며 아래 선택 기능에서 켤 수 있습니다.

<a id="choose-an-analyzer"></a>

## 분석기와 확장 기능 선택

| 필요 | 구성과 지원 범위 |
| --- | --- |
| 지원 형식의 이메일·카드번호·한국 휴대전화번호·주민등록번호 | 위 내장 프리셋. [형식과 제한](privacy.md#내장-규칙) 확인 |
| 사번·고객번호 등 회사 전용 식별자 | [Java 규칙·프리셋](privacy.md#로컬-규칙과-프리셋). 등록 후 선택 필요 |
| 자연어의 더 넓은 개인정보 유형 | [Presidio](#use-presidio). 외부 서비스와 대상 언어의 NLP·인식기 구성 필요 |
| JVM 내부 NER | [OpenNLP](#use-opennlp). 대상 언어·엔티티용 호환 모델을 직접 준비 |

KR 프리셋은 한국어 이름·주소 전반을 탐지하지 않습니다. 샘플의 Presidio 프로필은 영어 NLP를
사용하며 한국어 NER 구성을 제공하지 않습니다. 문서·UI의 언어와 실제 탐지 범위를 구분하세요.

<a id="local-and-mcp-tools"></a>

## 선택 사항: 로컬·MCP 도구에 원문 공개

고객 조회 도구에 고객번호만 원문으로 전달하는 예제입니다. `CUSTOMER_ID`는 내장 프리셋에 없으므로
규칙 정의와 선택이 모두 필요합니다. 기존 YAML을 다음으로 확장합니다.

```yaml
spring:
  ai:
    privacy:
      rules:
        enabled: true
        presets: [COMMON, KR]
        include: [customer-id]
```

`CustomerToolConfiguration.java`를 같은 스캔 범위에 두고 프로젝트의 `package`를 추가합니다.
도구는 설정 확인용 고정 결과를 반환합니다. 실제 앱에서는 고객 조회 구현으로 교체하세요.

```java
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class CustomerToolConfiguration {
    @Bean
    PiiRule customerIdRule() {
        PatternDefinition pattern = PatternDefinition.builder()
                .expression("(?<![A-Za-z0-9_])CUST-[0-9]{6}(?![A-Za-z0-9_])")
                .score(0.90)
                .build();
        return PatternPiiRule.builder()
                .id("customer-id")
                .entityType("CUSTOMER_ID")
                .addPattern(pattern)
                .build();
    }

    @Bean
    ChatClient customerChatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
        GuardrailsPolicy toolPolicy = GuardrailsPolicy.builder()
                .privacy(GuardrailsPolicy.Privacy.builder()
                        .toolDisclosurePolicy(ToolDisclosurePolicy.allowEntityTypesByToolName(
                                Map.of("customerLookup", List.of("CUSTOMER_ID"))))
                        .build())
                .build();
        return guardrailsChatClientFactory.builder(chatModel, toolPolicy)
                .defaultTools(new CustomerLookup())
                .build();
    }

    public static class CustomerLookup {
        @Tool(description = "Look up a customer by customer ID")
        public String customerLookup(String customerId) {
            return "Customer " + customerId + ", email alice@example.com";
        }
    }
}
```

`@Qualifier("customerChatClient")`로 주입받은 클라이언트에 `Look up CUST-123456.`을 요청합니다.
모델이 `customerLookup`을 호출하면 도구에는 원문 `CUST-123456`이 전달되고, 결과의 고객번호와
이메일은 모델에 돌아가기 전에 토큰화됩니다. 실제 모델의 도구 선택은 달라질 수 있으므로,
도구 호출 자체를 재현하려면 [Local Tool 데모](sample.md#local-tool)를 사용하세요.

### MCP 제공자 사용

위 빈 메서드에서 앱의 MCP 설정이 제공하는 `ToolCallbackProvider mcpToolCallbackProvider`를
추가 인자로 받고, `.defaultTools(new CustomerLookup())`를 `.defaultTools(mcpToolCallbackProvider)`로
바꿉니다. 정책에는 제공자의 실제 `ToolDefinition.name()`을 대소문자와 접두사까지 일치시킵니다.
제공자는 요청마다 최신 도구 목록을 해석합니다. [MCP 데모](sample.md#mcp)에서 확인할 수 있습니다.

요청별 `.tools(...)`에도 같은 정책이 적용됩니다. 도구 결과는 `returnDirect`를 포함해
모델이나 앱에 전달되기 전에 보호합니다. 별도로 구성한 `ToolCallingManager`·`ToolCallbackResolver`
실행 경로는 자체 보호 연동이 필요합니다. [상세 공개 규칙](privacy.md#도구별-원문-공개)을 참고하세요.

<a id="use-presidio"></a>

## 선택 사항: Presidio 사용

Presidio는 오픈소스 PII 탐지·비식별화 프레임워크입니다. 애플리케이션 고유 형식뿐 아니라 다양한 PII 유형을 탐지하려면 외부 Presidio
Analyzer 서비스를 연동하는 Presidio 스타터를 사용합니다.

[클라이언트 스타터](#add-the-client-starter)와 함께 Presidio 스타터를 추가합니다.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-presidio-spring-boot-starter:0.4.0-SNAPSHOT"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-presidio-spring-boot-starter</artifactId>
    <version>0.4.0-SNAPSHOT</version>
</dependency>
```

Presidio를 활성화하고 `analyzer-url`을 지정합니다.

```yaml
spring:
  ai:
    privacy:
      analysis:
        language: en
      presidio:
        enabled: true
        analyzer-url: http://localhost:5002
```

이 저장소를 복제한 경우 포함된 고정 버전의 로컬 Presidio 서비스를 다음
명령으로 실행할 수 있습니다.

```bash
docker compose -f samples/presidio/docker-compose.yml up -d --wait
```

[ChatClient 보호](#create-a-protected-client)의 예제에 따라 클라이언트를 생성합니다.

로컬 규칙과 Presidio를 함께 활성화할 수도 있습니다. 기본 `UNION` 모드에서는 구성한
분석기를 모두 실행한 뒤 탐지 결과를 합칩니다. 기본 실패 정책인 `REQUIRE_ALL`에서는
구성한 분석기 중 하나라도 실패하면 해당 요청도 실패합니다. 여러 분석기를 함께
사용할 때의 선택 방식과 실패 처리 방법은
[개인정보 보호](privacy.md#탐지와-해석)를 참고하세요.

<a id="use-opennlp"></a>

## 선택 사항: OpenNLP 사용

호환되는 OpenNLP 모델을 애플리케이션 JVM 내부에서 사용해 탐지하려면
OpenNLP 스타터를 사용합니다. [클라이언트 스타터](#add-the-client-starter)와 함께 추가하세요.

### Gradle

```gradle
dependencies {
    implementation "io.github.ultramancode:spring-ai-privacy-guardrails-opennlp-spring-boot-starter:0.4.0-SNAPSHOT"
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.ultramancode</groupId>
    <artifactId>spring-ai-privacy-guardrails-opennlp-spring-boot-starter</artifactId>
    <version>0.4.0-SNAPSHOT</version>
</dependency>
```

`PERSON` 탐지를 위한 최소 구성은 다음과 같이 작성할 수 있습니다.

```yaml
spring:
  ai:
    privacy:
      analysis:
        language: en
      opennlp:
        enabled: true
        tokenizer-model: classpath:/models/en-token.bin
        entity-models:
          PERSON: classpath:/models/en-ner-person.bin
```

OpenNLP 모델 바이너리는 이 프로젝트에 포함되어 있지 않습니다.
애플리케이션이 모델 파일을 직접 관리하며 대상 환경에 맞게 모델 출처,
토크나이저 호환성 및 탐지 품질을 검증해야 합니다.

`tokenizer-model`은 선택 사항이며, 지정하지 않으면 OpenNLP의
`SimpleTokenizer`를 사용합니다.

재현 가능한 OpenNLP 스모크 테스트 구성은
[전체 샘플 가이드](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/samples/spring-ai-demo/README.ko.md)를
참고하세요.

<a id="protect-final-responses"></a>

## 선택 사항: 최종 응답 보호

최종 응답의 개인정보 보호는 기본적으로 비활성화되어 있습니다. 클라이언트 정책에서 활성화합니다.

```java
GuardrailsPolicy outputPolicy = GuardrailsPolicy.builder()
        .privacy(GuardrailsPolicy.Privacy.builder()
                .outputProtection().tokenize()
                .build())
        .build();
ChatClient outputClient = guardrailsChatClientFactory.builder(chatModel, outputPolicy).build();
```

개인정보 보호 설정의 `outputProtection()`에서 사용할 출력 처리 방식을 선택합니다.

지원하는 출력 동작은 다음과 같습니다.

- `TOKENIZE`: 탐지된 개인정보를 요청 범위의 불투명 토큰으로 바꿉니다.
- `REDACT`: 탐지된 개인정보를 원문으로 복원할 수 없는 유형 마커로 대체합니다.
- `BLOCK`: 개인정보가 탐지되면 응답 전달을 중단하고
  `PrivacyOutputBlockedException`을 발생시킵니다.

스트리밍 API 자체는 계속 사용할 수 있지만, 출력 보호를 활성화하면 응답 조각을
생성되는 즉시 애플리케이션에 전달하지 않습니다. 라이브러리가 전체 응답을 먼저
모아 개인정보를 검사한 뒤 보호된 결과를 전달합니다. 따라서 여러 응답 조각에
나뉜 개인정보도 탐지할 수 있지만, 모델 응답이 생성되는 즉시 전달되는 실시간
스트리밍은 사용할 수 없습니다.

자세한 내용은 [출력 정책과 스트리밍](privacy.md#출력-정책과-스트리밍)과
[입력·응답 처리 제한](privacy.md#입력응답-처리-제한)을 참고하세요.

<a id="custom-analyzers"></a>

## 선택 사항: 사용자 정의 분석기

로컬 규칙, Presidio 또는 OpenNLP가 적합하지 않은 경우 애플리케이션이 사용자
정의 `PiiAnalyzer` Spring Bean을 제공할 수 있습니다.

사용자 정의 분석기도 로컬 규칙, Presidio, OpenNLP와 동일한 탐지·해석 흐름을 거쳐
모델, 도구 및 선택적 출력 보호 경계에 적용됩니다.

사용자 정의 `PiiAnalyzer`는 여러 요청에서 동시에 호출될 수 있으므로 동시 호출에
안전(thread-safe)하고 재진입 가능(reentrant)하게 구현해야 합니다. 외부 서비스
호출처럼 대기 시간이 발생하는 작업에는 적절한 제한 시간을 두고, 메모리 등 자원
사용량도 자체적으로 제한해야 합니다.

분석기 선택, 분석기 ID(provider ID), 엔티티 별칭, 신뢰도 하한 및 실패 정책은
[개인정보 보호](privacy.md#탐지와-해석)를 참고하세요. 구현 시 지켜야 할 조건과
여러 텍스트의 분석 방법은 [사용자 정의 분석기](privacy.md#사용자-정의-분석기)를
참고하세요.

## 보호 경계 참고 사항

보호된 `ChatClient`는 지원되는 메시지 내용을 모델에 전달하기 전에 보호합니다.
여기에는 모델로 전달되는 메모리와 RAG 컨텍스트도 포함됩니다.
하지만 채팅 메모리 저장소, 벡터 저장소, 데이터베이스, 로그 또는 트레이스에 이미
저장된 PII 자체를 자동으로 변경하지는 않습니다.

## 다음 단계

- 기능 조합과 클라이언트 정책 재사용은 [설정](configuration.md)을 참고하세요.
- 개인정보 보호의 설정 속성, 동작과 API 사용법은 [개인정보 보호](privacy.md)를 참고하세요.
- 개인정보 원문이나 페이로드를 노출하지 않고 경계 처리 결과만 관측하려면
  [개인정보 보호 런타임 관측](privacy.md#개인정보-보호-런타임-관측)을 참고하세요.
- 로컬 도구, RAG 및 Streamable HTTP MCP 시나리오에서 보호 동작을 확인하려면
  [샘플 / 데모 가이드](sample.md)를 참고하세요.
- 모델, 도구, 세션 및 요청 수명주기 경계는
  [아키텍처](architecture.md)를 참고하세요.
- 재현 가능한 개인정보 보호 경계 검증 매트릭스와 벤치마크는
  [평가 및 벤치마크](evaluation.md)를 참고하세요.
- 운영 환경에 적용하기 전 [위협 모델](threat-model.md)을 검토하세요.

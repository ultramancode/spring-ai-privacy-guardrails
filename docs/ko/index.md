---
hide:
  - footer
---

# Spring AI Privacy Guardrails

[English](../index.md) | **한국어**

<!-- i18n-source: docs/index.md -->
<!-- i18n-source-sha256: 2cbc1eea70318779b50d546d3654783194edc6c6eeb53149d85b52be7a2fed12 -->

![Spring AI Privacy Guardrails 실행 경계](../images/hero.svg)

탐지된 개인정보가 모델에 전달되지 않도록 보호합니다. 각 도구에는 정책이 허용한 원문만
공개합니다. 모든 도구 결과는 모델이나 애플리케이션에 반환하기 전에 다시 보호합니다.

분석기는 **보호 대상 정보가 포함된 텍스트를 식별합니다**. Spring AI Privacy Guardrails는
그 결과에 따라 **모델과 도구에 전달할 값을 보호하고**, 각 도구에 공개할 원문의 범위를
제한합니다.

선택적으로 [Spring Security 연동](security.md)을 추가해 모델에 공개할 도구를 제한하고,
도구 실행 전에 권한을 검사할 수 있습니다. 도구 권한 검사는 개인정보 보호 기능 없이도
사용할 수 있습니다.

Spring 공식 블로그에서 소개:
[This Week in Spring — August 18, 2026](https://spring.io/blog/2026/08/18/this-week-in-spring-august-18-2026/).

## 직접 확인하기

Privacy Boundary Inspector에서 Local Tool, RAG 및 MCP 시나리오의 모델과 도구가
전달받은 값을 비교할 수 있습니다.

![Local Tool, RAG 및 MCP 시나리오에서 모델과 도구의 입력을 비교하는 Privacy Boundary Inspector](../images/privacy-boundary-inspector-demo-ko.gif)

전체 Inspector 흐름은 [샘플 / 데모 가이드](sample.md)를 참고하세요.

## 참고 문서

| 가이드 | 다루는 내용 |
| --- | --- |
| [시작하기](getting-started.md) | 스타터 선택, 기본 설정과 모델·도구·MCP·출력 보호 |
| [샘플 / 데모 가이드](sample.md) | Inspector 시나리오, 예상 결과, 엔드포인트와 언어 선택 |
| [설정과 사용법](configuration.md) | 스타터, 분석기, 출력 정책, 도구 공개와 처리 제한 |
| [Spring Security 도구 권한](security.md) | 사용자별 도구 공개·실행 권한 검사, Tool Search와 비동기 보안 컨텍스트 |
| [아키텍처](architecture.md) | 모듈 경계, 요청 세션, 탐지 결과 해석과 실행 수명 주기 |
| [위협 모델](threat-model.md) | 보호 대상, 신뢰 경계, 통제, 한계와 별도 관리 영역 |
| [평가와 벤치마크](evaluation.md) | 경계 테스트, 재현 가능한 분석기 기준선과 프로젝트 벤치마크 |

## 샘플 실행

기본 샘플은 로컬 `ChatModel`을 사용하므로 외부 모델 API 키가 필요하지 않습니다.

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:run
```

`http://127.0.0.1:8080`을 열어 Local Tool, RAG, MCP 시나리오에서 개인정보가 어떻게
보호되는지 확인할 수 있습니다.

Inspector 흐름은 [샘플 / 데모 가이드](sample.md), Presidio·OpenNLP 구성과 통합 예제는
[전체 샘플 애플리케이션 가이드](https://github.com/ultramancode/spring-ai-privacy-guardrails/blob/main/samples/spring-ai-demo/README.ko.md)를
참고하세요.

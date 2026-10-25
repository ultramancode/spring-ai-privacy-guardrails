---
description: >-
  모델에 전달되는 개인정보를 토큰화하고 도구별 원문 공개, 도구 권한과 콘텐츠 검사를 적용합니다.
---

# Spring AI Privacy Guardrails

[English](../index.md) | **한국어**

<!-- i18n-source: docs/index.md -->
<!-- i18n-source-sha256: 20ce12e06e0bb8c6a7b388f033772afc2a014ba4fda261a474478169d5064a75 -->

**모델에는 탐지된 개인정보를 토큰으로 전달하고, 도구에는 필요한 원문만 공개합니다. 도구 결과는 다시 보호합니다.**

**[API 키 없이 데모 실행](sample.md#run-the-inspector)** · **[기존 Spring AI 앱에 적용](getting-started.md)**

| 원하는 동작 | 시작할 곳 |
| --- | --- |
| 개인정보 토큰화와 도구별 최소 원문 공개 | [프리셋 기반 빠른 시작](getting-started.md) |
| 사용자 권한에 따른 도구 공개·실행 | [Spring Security 설정](security.md#spring-boot-스타터-추가) |
| 규칙·ONNX·HTTP 모델을 이용한 요청·응답 검사 | [콘텐츠 검사 설정](inspection.md#입력-검사-설정) |

세 기능은 독립적으로 선택하거나 조합할 수 있습니다. 분석기가 민감정보를 식별하면,
라이브러리는 지원되는 Spring AI 호출 경로에서 토큰화와 정책을 적용합니다.

## 고객 조회 흐름 확인

Inspector의 Local Tool에서는 모델에 토큰을 전달하고, 허용된 도구에 고객번호 원문만
복원하며, 도구 결과를 다시 보호하는 과정을 볼 수 있습니다.
[실행 준비와 성공 지표](sample.md#run-the-inspector)를 따라 확인하세요.

![개인정보 보호 Inspector의 Local Tool·RAG·MCP·Security·Inspection](../images/privacy-boundary-inspector-demo-ko.gif)

[![모델·도구 사이의 개인정보 보호 흐름과 선택 가능한 도구 권한·콘텐츠 검사](../images/hero.svg)](../images/hero.svg)

[큰 그림 보기](../images/hero.svg) · [아키텍처](architecture.md) · [문제 해결](troubleshooting.md)

**외부 소개:** [Spring 공식 블로그](https://spring.io/blog/2026/08/18/this-week-in-spring-august-18-2026/) · [Awesome Spring AI](https://github.com/spring-ai-community/awesome-spring-ai#extensions-and-forks)

## 참고 문서

| 가이드 | 다루는 내용 |
| --- | --- |
| [시작하기](getting-started.md) | 스타터 선택, 기본 설정과 모델·도구·MCP·출력 보호 |
| [설정](configuration.md) | 의존성, 클라이언트 생성, 기능 선택과 정책 재사용 |
| [샘플 / 데모 가이드](sample.md) | Inspector 시나리오, 예상 결과, 엔드포인트와 언어 선택 |
| [개인정보 보호](privacy.md) | 분석기, 토큰화, 도구 원문 공개, 출력 보호와 처리 한도 |
| [Spring Security 도구 권한](security.md) | 사용자별 도구 공개·실행 권한 검사, Tool Search와 비동기 보안 컨텍스트 |
| [콘텐츠 검사](inspection.md) | 규칙, 로컬 ONNX 텍스트 분류 모델, HTTP 검사 모델을 통한 입력 검사와 선택적 출력 검사 |
| [아키텍처](architecture.md) | 모듈 경계, 요청 세션, 탐지 결과 해석과 실행 수명 주기 |
| [위협 모델](threat-model.md) | 보호 대상, 신뢰 경계, 통제, 한계와 별도 관리 영역 |
| [평가와 벤치마크](evaluation.md) | 경계 테스트, 재현 가능한 분석기 기준선과 프로젝트 벤치마크 |

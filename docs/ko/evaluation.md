# 평가와 벤치마크

[English](../evaluation.md) | [한국어](evaluation.md)

<!-- i18n-source: docs/evaluation.md -->
<!-- i18n-source-sha256: 09eaf223f366ca324f004152ba349923daf2f3348082a92d83a3244257a095f9 -->

이 저장소에는 데모 분석기의 회귀 테스트, 개인정보 보호 경계 테스트와 JMH 벤치마크가
포함되어 있습니다. 회귀 테스트는 탐지 결과를, 경계 테스트는 정책 적용을, JMH 벤치마크는
로컬 처리 시간을 확인합니다. 각 결과는 서로 대체할 수 없으며 운영 환경의 정확도나 지연
시간을 보장하지 않습니다.

## 데모 분석기 회귀 테스트

데모의 정규식(Regex) 분석기는 실행 가능한 샘플과 같은 설정을 사용해 합성 데이터셋을
대상으로 테스트합니다. 테스트는 기대한 엔티티 유형과 원문 값을 탐지하는지, 토큰화
결과에서 해당 원문이 제거되는지, 요청 세션이 정리되는지 확인합니다.

기본 데모 구성에서 회귀 테스트만 실행하려면 다음 명령을 사용합니다.

```bash
./gradlew :spring-ai-privacy-guardrails-sample-demo:test --tests io.github.ultramancode.springai.privacy.sample.DemoRegexEvaluationTest
```

이 테스트는 데모 규칙의 변경을 확인하기 위한 것입니다. 이 결과는 일반적인 개인정보 탐지
정확도나 언어·도메인 전반의 탐지 성능을 의미하지 않습니다. 운영에 사용할 분석기는 해당
환경을 대표하는 데이터로 별도 검증해야 합니다.

## 개인정보 보호 경계 테스트

경계 테스트는 탐지 정확도를 측정하지 않습니다. 모델·도구·출력·요청 수명주기의 각
경계에서 설정한 정책이 적용되는지를 확인합니다. 전체 저장소 검증은 다음 명령으로
실행합니다.

```bash
./gradlew --no-daemon clean check
```

기본 검증은 테스트용 모델과 로컬 구성 요소를 사용합니다. 실제 원격 모델이나 분석 서비스를
사용하는 검증은 별도의 선택형 테스트입니다.

## JMH 벤치마크

저장소의 JMH 벤치마크는 Regex 분석, 요청 단위 토큰화, 도구 경계 처리와 원문 복원 등 주요
로컬 처리 경로의 실행 시간을 측정합니다. 같은 환경에서 결과를 비교하면 입력 규모에 따른
변화와 버전 간 성능 변화를 살펴볼 수 있습니다.

전체 벤치마크는 다음 명령으로 실행합니다.

```bash
./gradlew :spring-ai-privacy-guardrails-benchmarks:jmh
```

결과는 `spring-ai-privacy-guardrails-benchmarks/build/reports/jmh/results.json`에 저장됩니다.
결과를 비교할 때는 동일한 JVM과 실행 환경을 사용하세요.

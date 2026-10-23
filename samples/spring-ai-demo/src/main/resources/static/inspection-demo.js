const privacyInspectionDemo = (() => {
  const root = document.getElementById("inspectionDemo");
  let language = "en";
  let example = "block";
  let running = false;
  let result = null;
  const translations = {
    en: {
      examples: "Examples", blockExample: "Blocked cases", allowExample: "Allowed cases",
      inputBlocked: "Input blocked", outputBlocked: "Output blocked",
      inputAllowed: "Input allowed", outputAllowed: "Output allowed", inputTitle: "Input inspection", outputTitle: "Output inspection",
      modelCalls: "Model calls", callUnit: "call", pluralCallUnit: "calls", userRequest: "User request", originalInput: "Original input",
      inputPrivacy: "Input privacy protection", outputPrivacy: "Output privacy protection",
      inputCheck: "Input inspection", outputCheck: "Output inspection", protectedInput: "Protected input", protectedOutput: "Protected response",
      clientNote: "Privacy · Content inspection", response: "← Response", privacyNote: "Actual privacy-processed text",
      inputDetected: "Prompt injection detected", outputDetected: "Insult detected",
      inputReason: "Prompt injection", outputReason: "Insult directed at the user",
      inputPassed: "Input inspection passed", outputPassed: "Output inspection passed",
      modelAllowed: "Model call allowed", responseAllowed: "Response delivery allowed", noFindings: "No findings",
      notCalled: "Not called", called: "Called", blockedCall: "Model call blocked", stopped: "Stopped at input inspection",
      notDelivered: "Request never reaches the model", receivedInput: "Input received by model", generated: "Original model response",
      demoModel: "Local scripted ChatModel", app: "Calling application", exception: "Output block handled",
      withheld: "Model response is withheld", delivered: "Protected response delivered", pending: "Pending",
      scroll: "Scroll horizontally to see the full flow.", report: "Inspection report",
      complete: "Execution complete. Model calls: input case {input}, output case {output}."
    },
    ko: {
      examples: "실행 예시", blockExample: "차단 사례", allowExample: "허용 사례",
      inputBlocked: "입력 차단", outputBlocked: "출력 차단",
      inputAllowed: "입력 통과", outputAllowed: "출력 통과", inputTitle: "입력 검사", outputTitle: "출력 검사",
      modelCalls: "모델 호출", callUnit: "회", pluralCallUnit: "회", userRequest: "사용자 요청", originalInput: "입력 원문",
      inputPrivacy: "입력 개인정보 보호", outputPrivacy: "출력 개인정보 보호",
      inputCheck: "입력 검사", outputCheck: "출력 검사", protectedInput: "보호된 입력", protectedOutput: "보호된 응답",
      clientNote: "개인정보 보호 · 콘텐츠 검사", response: "← 응답", privacyNote: "실제 개인정보 보호 처리 결과",
      inputDetected: "프롬프트 인젝션 탐지", outputDetected: "모욕적 표현 탐지",
      inputReason: "프롬프트 인젝션", outputReason: "사용자에 대한 모욕적 표현",
      inputPassed: "입력 검사 통과", outputPassed: "출력 검사 통과",
      modelAllowed: "모델 호출 허용", responseAllowed: "응답 전달 허용", noFindings: "탐지 없음",
      notCalled: "호출되지 않음", called: "호출됨", blockedCall: "모델 호출 차단", stopped: "입력 검사에서 종료",
      notDelivered: "요청이 모델에 전달되지 않음", receivedInput: "모델에 전달된 입력", generated: "생성한 응답 원문",
      demoModel: "로컬 시연용 ChatModel", app: "호출한 애플리케이션", exception: "출력 차단 예외 처리",
      withheld: "모델 응답은 전달되지 않음", delivered: "보호된 응답 전달", pending: "대기 중",
      scroll: "좌우로 스크롤해 전체 흐름을 확인하세요.", report: "검사 보고서",
      complete: "실행 완료. 입력 사례 모델 호출 {input}회, 출력 사례 모델 호출 {output}회."
    }
  };

  function escapeHtml(value) {
    return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function renderHighlightedText(value) {
    const pattern = /(\[PII_([A-Z_]+)_([1-9]\d*):[A-Za-z0-9_-]{16}\])|([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})/g;
    let html = "";
    let position = 0;
    for (const match of value.matchAll(pattern)) {
      html += escapeHtml(value.slice(position, match.index));
      if (match[1]) {
        html += '<code class="token" title="' + escapeHtml(match[0]) + '">[PII_' + match[2] + '_…_' + match[3] + ']</code>';
      } else {
        html += '<span class="email">' + escapeHtml(match[0]) + '</span>';
      }
      position = match.index + match[0].length;
    }
    return html + escapeHtml(value.slice(position));
  }

  function renderMessageContent(value, blocked = false) {
    if (value == null) return '<p class="message">—</p>';
    const lines = value.split("\n");
    return '<div class="content-text">' + lines.map((line, index) => {
      const style = blocked && index === lines.length - 1 ? "policy-marker" : "message-line";
      return '<div class="' + style + '">' + renderHighlightedText(line) + '</div>';
    }).join("") + '</div>';
  }

  function renderConnector(kind = "") {
    return '<span class="connector ' + kind + '" aria-hidden="true">' + (kind.includes("cut") ? '<span class="cross">×</span>' : '') + '</span>';
  }

  function getInspectionDecision(inspection) {
    return inspection?.report.decision || "PENDING";
  }

  function renderDecisionBadge(value, direction = "") {
    let style = "waiting";
    if (value === "BLOCK") {
      style = "";
    } else if (value === "ALLOW") {
      style = "allow";
    }
    return '<span class="badge ' + style + '">' + (value === "PENDING" ? "…" : value + direction) + '</span>';
  }

  function renderPrivacyCard(direction, evidence, labels) {
    const input = direction === "input";
    const inspection = input ? evidence?.inputInspection : evidence?.outputInspection;
    const title = input ? labels.inputPrivacy : labels.outputPrivacy;
    const contentLabel = input ? labels.protectedInput : labels.protectedOutput;
    return `<section class="node privacy-node ${direction}-privacy">
      <div class="node-top">
        <span class="step-number">${input ? 1 : 3}</span>
        <h3>${title}</h3>
      </div>
      <p class="text-label">${contentLabel}</p>
      ${renderMessageContent(inspection?.inspectedText, getInspectionDecision(inspection) === "BLOCK")}
      <p class="node-note">${labels.privacyNote}</p>
      ${renderConnector(input ? "" : "reverse")}
    </section>`;
  }

  function renderInspectionCard(direction, evidence, labels) {
    const input = direction === "input";
    const inspection = input ? evidence?.inputInspection : evidence?.outputInspection;
    const verdict = getInspectionDecision(inspection);
    const blocked = verdict === "BLOCK";
    let style = "";
    if (blocked) {
      style = "blocked";
    } else if (verdict === "ALLOW") {
      style = "allow";
    }
    let title = labels.pending;
    let reason = "—";
    let findings = "—";
    if (inspection) {
      if (blocked) {
        title = input ? labels.inputDetected : labels.outputDetected;
        reason = input ? labels.inputReason : labels.outputReason;
      } else {
        title = input ? labels.inputPassed : labels.outputPassed;
        reason = input ? labels.modelAllowed : labels.responseAllowed;
      }
      findings = inspection.report.outcomes.flatMap(outcome => outcome.result.findings)
        .map(finding => finding.category + " · " + finding.code).join("\n") || labels.noFindings;
    }
    let connectorKind = input ? "" : "reverse";
    if (blocked) {
      connectorKind = input ? "cut" : "reverse denied";
    }
    let reportHtml = "";
    if (inspection) {
      const report = escapeHtml(JSON.stringify(inspection.report, null, 2));
      reportHtml = `<details class="inspection-report">
        <summary>${labels.report}</summary>
        <pre>${report}</pre>
      </details>`;
    }
    const heading = input ? labels.inputCheck : labels.outputCheck;
    return `<section class="node check-node ${direction}-check ${style}" data-decision="${verdict}">
      <div class="node-top">
        <span class="step-number">${input ? 2 : 4}</span>
        <h3>${heading}</h3>
        ${renderDecisionBadge(verdict)}
      </div>
      <p class="decision-title">${title}</p>
      <p class="decision-copy">${reason}</p>
      <code class="finding-code">${escapeHtml(findings)}</code>
      ${reportHtml}
      ${renderConnector(connectorKind)}
    </section>`;
  }

  function renderModelCard(evidence, inputCase, labels) {
    const stopped = evidence?.blockedAt === "MODEL_REQUEST";
    let body;
    if (stopped) {
      body = `<p class="stop-title">${labels.blockedCall}</p>
        <p class="decision-copy">${labels.stopped}</p>
        <p class="node-note">${labels.notDelivered}</p>`;
    } else {
      const contentLabel = inputCase ? labels.receivedInput : labels.generated;
      const content = inputCase ? evidence?.modelInput : evidence?.rawModelResponse;
      const outputBlocked = !inputCase && evidence?.blockedAt === "FINAL_OUTPUT";
      body = `<p class="text-label">${contentLabel}</p>
        ${renderMessageContent(content, outputBlocked)}
        <p class="node-note">${labels.demoModel}</p>`;
    }
    let state = labels.pending;
    if (stopped) {
      state = labels.notCalled;
    } else if (evidence) {
      state = labels.called;
    }
    return `<section class="node model-card ${stopped ? "inactive" : ""}">
      <span class="model-kicker">LOCAL CHAT MODEL</span>
      <h3>LLM</h3>
      <span class="model-state">${state}</span>
      <div class="model-divider"></div>
      ${body}
    </section>`;
  }

  function renderApplicationCard(evidence, labels) {
    const blocked = evidence?.blockedAt === "FINAL_OUTPUT";
    const delivered = evidence?.finalResponse != null;
    let title = labels.pending;
    let body;
    if (blocked) {
      title = labels.exception;
      body = `<p class="node-note">${labels.withheld}</p>
        <code class="exception-code">${escapeHtml(evidence.exceptionType)}</code>`;
    } else {
      title = delivered ? labels.delivered : labels.pending;
      body = renderMessageContent(evidence?.finalResponse);
    }
    return `<section class="node app-result ${delivered ? "delivered" : ""}">
      <h3>${labels.app}</h3>
      <p class="decision-title">${title}</p>
      ${body}
    </section>`;
  }

  function renderInspectionPanel(direction, evidence, labels) {
    const inputCase = direction === "input";
    const verdict = getInspectionDecision(inputCase ? evidence?.inputInspection : evidence?.outputInspection);
    let title = inputCase ? labels.inputTitle : labels.outputTitle;
    if (verdict === "BLOCK") {
      title = inputCase ? labels.inputBlocked : labels.outputBlocked;
    } else if (verdict !== "PENDING") {
      title = inputCase ? labels.inputAllowed : labels.outputAllowed;
    }
    const calls = evidence?.modelCalls ?? "—";
    const callUnit = calls === 1 ? labels.callUnit : labels.pluralCallUnit;
    const callCountStyle = calls === 0 ? "zero" : "";
    let outputInspectionHtml = "";
    if (!inputCase) {
      outputInspectionHtml = `${renderConnector("reverse model-return")}
        <div class="response-direction">${labels.response}</div>
        ${renderPrivacyCard("output", evidence, labels)}
        ${renderInspectionCard("output", evidence, labels)}
        ${renderApplicationCard(evidence, labels)}`;
    }

    return `<article class="inspection-section" data-direction="${direction}">
      <div class="section-heading">
        <h2 id="inspection-${direction}-title">${title}</h2>
        ${renderDecisionBadge(verdict, " · " + direction.toUpperCase())}
        <div class="call-count ${callCountStyle}">
          <span>${labels.modelCalls}</span>
          <strong data-calls="${calls}">${calls}</strong>
          <span>${callUnit}</span>
        </div>
      </div>
      <p class="scroll-hint">${labels.scroll}</p>
      <div class="flow-viewport" tabindex="0" role="region" aria-labelledby="inspection-${direction}-title">
        <div class="pipeline ${direction}">
          <div class="client-outline" aria-hidden="true"></div>
          <div class="client-heading">
            <span>Spring AI ChatClient</span>
            <small>${labels.clientNote}</small>
          </div>
          <section class="node user-card">
            <h3>${labels.userRequest}</h3>
            ${renderMessageContent(evidence?.rawInput, evidence?.blockedAt === "MODEL_REQUEST")}
            <p class="node-note">${labels.originalInput}</p>
            ${renderConnector()}
          </section>
          ${renderPrivacyCard("input", evidence, labels)}
          ${renderInspectionCard("input", evidence, labels)}
          ${renderModelCard(evidence, inputCase, labels)}
          ${outputInspectionHtml}
        </div>
      </div>
    </article>`;
  }

  function render() {
    const labels = translations[language];
    root.lang = language;
    root.querySelectorAll("[data-inspection-copy]").forEach(node => {
      node.textContent = labels[node.dataset.inspectionCopy];
    });
    root.querySelector(".example-toggle").setAttribute("aria-label", labels.examples);
    root.querySelectorAll("[data-inspection-example]").forEach(button => {
      button.disabled = running;
      button.setAttribute("aria-pressed", String(button.dataset.inspectionExample === example));
    });
    const cases = root.querySelector("#inspectionCases");
    const positions = [...cases.querySelectorAll(".flow-viewport")].map(node => node.scrollLeft);
    cases.setAttribute("aria-busy", String(running));
    cases.innerHTML = renderInspectionPanel("input", result?.inputCase, labels) + renderInspectionPanel("output", result?.outputCase, labels);
    cases.querySelectorAll(".flow-viewport").forEach((node, index) => {
      node.scrollLeft = positions[index] || 0;
    });
  }

  return {
    get example() {
      return example;
    },
    selectExample(exampleId) {
      example = exampleId;
    },
    setLanguage(languageCode) {
      language = languageCode;
      render();
    },
    setRunning(isRunning) {
      running = isRunning;
      render();
    },
    reset() {
      result = null;
      root.querySelector("#inspectionStatus").textContent = "";
      render();
    },
    display(inspectionResult) {
      result = inspectionResult;
      render();
      root.querySelector("#inspectionStatus").textContent = translations[language].complete
        .replace("{input}", inspectionResult.inputCase.modelCalls).replace("{output}", inspectionResult.outputCase.modelCalls);
    }
  };
})();

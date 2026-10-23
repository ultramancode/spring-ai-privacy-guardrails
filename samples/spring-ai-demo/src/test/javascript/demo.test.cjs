const assert = require("node:assert/strict");
const {readFileSync} = require("node:fs");
const {join} = require("node:path");
const {test} = require("node:test");
const {runInNewContext} = require("node:vm");

const demoScript = ["demo-translations.js", "demo.js"]
  .map(file => readFileSync(join(__dirname, "../../main/resources/static", file), "utf8"))
  .join("\n");
const inspectionScript = readFileSync(join(__dirname, "../../main/resources/static/inspection-demo.js"), "utf8");

// Run the page's actual render, reset, translation, and request logic with a small DOM substitute.
async function createDemoView(language = "en", scenarioKey = "security") {
  const elements = new Map();
  const requests = [];
  function node() {
    let ownText = "";
    return {
      dataset: {}, style: {}, children: [],
      addEventListener() {},
      get textContent() {
        return ownText + this.children.map(child => typeof child === "string" ? child : child.textContent).join("");
      },
      set textContent(value) { ownText = String(value); this.children = []; },
      classList: {
        add() {}, remove() {}, toggle() {}
      },
      append(...children) { this.children.push(...children); },
      replaceChildren(...children) { ownText = ""; this.children = children; },
      querySelector() { return this.small ??= node(); }
    };
  }
  const element = id => {
    if (!elements.has(id)) elements.set(id, node());
    return elements.get(id);
  };
  const document = {
    documentElement: {},
    getElementById: element,
    querySelectorAll: selector => selector === "[data-i18n]"
      ? [...elements.values()].filter(target => target.dataset.i18n) : [],
    createTextNode: textContent => ({textContent}),
    createElement: () => node()
  };
  const inspection = {example: "block", reset() {}, setLanguage() {}, setRunning() {}};
  element("scenarioSelector").value = scenarioKey;
  const page = runInNewContext(demoScript + `
    currentLanguage = ${JSON.stringify(language)};
    ({renderSecurityEvidence, renderToolEvidence, renderRagBoundary, setSessionCount, runSelectedScenario, translations, scenarios});
  `, {document, privacyInspectionDemo: inspection, fetch: async (path, options) => {
    requests.push({path, options});
    if (path === "/demo/example-input") return {ok: true, json: async () => ({input: "example"})};
    if (path === "/demo/protect") return {ok: true, json: async () => ({detectedSpans: []})};
    return {ok: false, status: 503};
  }});
  await new Promise(setImmediate);
  assert.equal(element("run").disabled, false, "The initial request must finish");
  requests.length = 0;
  return {
    element,
    page,
    requests,
    render(customerSupport = allowedSupport, overrides = {}) {
      page.renderSecurityEvidence({
        generalEmployee: deniedEmployee, customerSupport,
        activeSessionsAfterCall: 0,
        ...overrides
      });
    }
  };
}

function createInspectionView() {
  const elements = new Map();
  const element = selector => {
    if (!elements.has(selector)) elements.set(selector, {
      textContent: "", innerHTML: "", setAttribute() {}, querySelectorAll() {return [];}
    });
    return elements.get(selector);
  };
  const root = {querySelector: element, querySelectorAll() {return [];}};
  const view = runInNewContext(inspectionScript + "\nprivacyInspectionDemo;", {document: {getElementById() {return root;}}});
  return {view, element, html: () => element("#inspectionCases").innerHTML};
}

const deniedEmployee = {
  role: "ROLE_EMPLOYEE", exposedToolNames: [], callbackInvocations: 0,
  toolCallDenied: true, modelRequestedTool: true,
  toolReceivedOnlyAllowedOriginals: false, toolResultRetokenizedBeforeModel: false,
  finalResponse: null
};
const allowedSupport = {
  ...deniedEmployee, role: "ROLE_CUSTOMER_SUPPORT", exposedToolNames: ["customerLookup"],
  callbackInvocations: 1, toolCallDenied: false,
  toolReceivedOnlyAllowedOriginals: true, toolResultRetokenizedBeforeModel: true,
  finalResponse: 'done {"status":"found"}'
};
const deniedSupport = {...deniedEmployee, role: "ROLE_CUSTOMER_SUPPORT"};

const inspectionToken = "[PII_EMAIL_ADDRESS_1:AbCdEfGhIjKlMnOp]";
const allowedInspectionReport = {decision: "ALLOW", outcomes: [{result: {findings: []}}]};

function allowedInspectionResponse() {
  const evidence = {
    rawInput: "alice@example.com\nCheck my delivery status.",
    inputInspection: {inspectedText: inspectionToken, report: allowedInspectionReport},
    modelCalls: 1, modelInput: inspectionToken, rawModelResponse: "support@example.com",
    outputInspection: {inspectedText: inspectionToken, report: allowedInspectionReport},
    finalResponse: inspectionToken, blockedAt: null, exceptionType: null
  };
  return {inputCase: {...evidence, outputInspection: null}, outputCase: evidence};
}

test("English and Korean cover the page labels and scenario titles", async () => {
  const {page} = await createDemoView();
  const html = readFileSync(join(__dirname, "../../main/resources/static/index.html"), "utf8");
  const requiredKeys = [...html.matchAll(/data-i18n(?:-aria-label)?\s*=\s*["']([^"']+)["']/g)]
    .map(match => match[1]);
  for (const scenario of Object.values(page.scenarios)) {
    requiredKeys.push(scenario.titleKey, scenario.leadKey);
  }
  assert.deepEqual(Object.keys(page.translations.en).sort(), Object.keys(page.translations.ko).sort());
  for (const language of ["en", "ko"]) {
    for (const key of requiredKeys) {
      assert.equal(typeof page.translations[language][key], "string", `${language}: ${key}`);
      assert.notEqual(page.translations[language][key].trim(), "", `${language}: ${key}`);
    }
  }
});

for (const language of ["en", "ko"]) {
  for (const [scenarioKey, expectedPaths] of Object.entries({
    "local-tool": ["/demo/example-input", "/demo/protect", "/demo/tool-loop"],
    rag: ["/demo/rag"],
    mcp: ["/demo/example-input", "/demo/mcp-tool-loop"],
    security: ["/demo/security-tool-boundary"],
    inspection: ["/demo/inspection?example=block"]
  })) {
    test(`runs ${scenarioKey} with the selected locale and recovers from HTTP failure (${language})`, async () => {
      const view = await createDemoView(language, scenarioKey);
      await view.page.runSelectedScenario();
      assert.deepEqual(view.requests.map(request => request.path), expectedPaths);
      for (const request of view.requests) {
        assert.equal(request.options.headers["Accept-Language"], language);
      }
      assert.match(view.element("error").textContent, /503/);
      assert.equal(view.element("run").disabled, false);
      assert.equal(view.element("scenarioSelector").disabled, false);
      assert.equal(view.element("run").textContent, language === "en" ? "Run" : "실행");
    });
  }
}

test("renders a denied support request without claiming a privacy failure", async () => {
  const view = await createDemoView();
  view.render(deniedSupport);
  assert.match(view.element("securitySupportOutcome").textContent, /Permission denied/);
  assert.equal(view.element("securityFinalResponse").textContent, "");
  assert.equal(view.element("securityAuthorizationStatus").textContent, "FAIL");
  for (const metric of ["ToolInput", "ToolOutput"]) {
    assert.equal(view.element(`security${metric}Meaning`).textContent, "NOT EXECUTED");
    assert.equal(view.element(`security${metric}Status`).textContent, "—");
  }
});

test("renders the allowed response as JSON", async () => {
  const view = await createDemoView();
  view.render();
  assert.equal(view.element("securityAuthorizationStatus").textContent, "PASS");
  assert.match(view.element("securitySupportOutcome").textContent, /protected CRM result/);
  assert.deepEqual(JSON.parse(view.element("securityFinalResponse").textContent), {status: "found"});
});

test("preserves full opaque token values in rendered responses", async () => {
  const nonce = "_-0123456789aBcD";
  const payload = {
    employeeId: `[PII_EMPLOYEE_ID_1:${nonce}]`,
    email: `[PII_EMAIL_ADDRESS_12:${nonce}]`,
    phone: `[PII_PHONE_NUMBER_3:${nonce}]`,
    customerId: `[PII_CUSTOMER_ID_1:${nonce}]`
  };
  const view = await createDemoView();
  view.render({...allowedSupport, finalResponse: JSON.stringify(payload)});

  const response = view.element("securityFinalResponse");
  assert.deepEqual(JSON.parse(response.textContent), payload);
});

test("preserves malformed token text", async () => {
  const nonce = "_-0123456789aBcD";
  const text = [
    "[PII_EMAIL_ADDRESS_1]",
    `[PII_EMAIL_ADDRESS_1:${nonce.slice(1)}]`,
    `[PII_EMAIL_ADDRESS_1:${nonce}A]`,
    `[PII_EMAIL_ADDRESS_0:${nonce}]`
  ].join(" ");
  const view = await createDemoView();
  view.render({...allowedSupport, finalResponse: text});

  const response = view.element("securityFinalResponse");
  assert.equal(response.textContent, text);
});

test("clears the previous response when a later support request is denied", async () => {
  const view = await createDemoView();
  view.render();
  view.render(deniedSupport);
  assert.match(view.element("securitySupportOutcome").textContent, /Permission denied/);
  assert.equal(view.element("securityFinalResponse").textContent, "");
});

for (const language of ["en", "ko"]) {
  test(`shows actual callback counts, including unexpected repeated calls (${language})`, async () => {
    const view = await createDemoView(language);
    view.render({...allowedSupport, callbackInvocations: 2}, {
      generalEmployee: {...deniedEmployee, callbackInvocations: 2}
    });
    for (const role of ["Employee", "Support"]) {
      assert.equal(view.element(`security${role}CallbackState`).textContent, language === "en" ? "2 CALLS" : "2회");
    }
    assert.equal(view.element("securityAuthorizationStatus").textContent, "FAIL");
    assert.equal(view.element("securityAuthorizationMeaning").textContent, language === "en" ? "NOT CONFIRMED" : "확인되지 않음");
  });

  test(`failed rerun does not restore stale results during translation (${language})`, async () => {
    const view = await createDemoView(language);
    view.render();
    await view.page.runSelectedScenario();
    assert.equal(view.requests.length, 1);
    assert.equal(view.requests[0].path, "/demo/security-tool-boundary");
    assert.equal(view.requests[0].options.headers["Accept-Language"], language);
    for (const metric of ["Authorization", "ToolInput", "ToolOutput", "Cleanup"]) {
      assert.equal(view.element(`security${metric}Meaning`).textContent, "—");
      assert.equal(view.element(`security${metric}Status`).textContent, language === "en" ? "WAIT" : "대기");
    }
    for (const id of ["EmployeeOutcome", "SupportOutcome", "FinalResponse", "EmployeeRequest", "SupportRequest"]) {
      assert.equal(view.element(`security${id}`).textContent, "—");
    }
    assert.match(view.element("error").textContent, /503/);
    assert.equal(view.element("run").disabled, false);
    assert.equal(view.element("scenarioSelector").disabled, false);
  });
}

test("failed privacy checks do not retain successful explanations", async () => {
  const view = await createDemoView();
  view.render({...allowedSupport, toolReceivedOnlyAllowedOriginals: false, toolResultRetokenizedBeforeModel: false});
  for (const metric of ["ToolInput", "ToolOutput"]) {
    assert.equal(view.element(`security${metric}Meaning`).textContent, "NOT CONFIRMED");
    assert.equal(view.element(`security${metric}Status`).textContent, "FAIL");
  }
});

test("absence of a tool request is not labeled a permission denial", async () => {
  const view = await createDemoView();
  view.render({...deniedSupport, modelRequestedTool: false, toolCallDenied: false});
  assert.equal(view.element("securitySupportRequest").textContent, "NOT REQUESTED");
  assert.equal(view.element("securitySupportOutcome").textContent, "No tool execution was recorded.");
  assert.equal(view.element("securityAuthorizationStatus").textContent, "FAIL");
});

test("RAG protection requires complete backend evidence", async () => {
  const view = await createDemoView();
  const evidence = {
    retrievedDocumentContainsRawPii: true,
    modelInputContainsRawPii: false,
    modelInputContainsTokenizedPii: true
  };
  view.page.renderRagBoundary(evidence);
  assert.equal(view.element("ragBoundaryState").textContent, "PROTECTED");

  for (const missingEvidence of [
    {retrievedDocumentContainsRawPii: false},
    {modelInputContainsRawPii: true},
    {modelInputContainsTokenizedPii: false}
  ]) {
    view.page.renderRagBoundary({...evidence, ...missingEvidence});
    assert.equal(view.element("ragBoundaryState").textContent, "NOT CONFIRMED");
  }
});

test("local and MCP model stages reflect the raw-value evidence", async () => {
  const view = await createDemoView();
  const tool = {
    protectedModelInput: "protected request", tokenizedToolArguments: "{}", finalResponse: "{}",
    allowedOriginalEntityTypes: ["CUSTOMER_ID"], toolReceivedOnlyAllowedOriginals: true,
    toolLookupSucceeded: true, toolResultRetokenizedBeforeModel: true, activeSessionsAfterCall: 0,
    boundaryEvidence: {
      deniedToolRawValues: {observed: 0, total: 3, passed: true},
      allowedToolRawValues: {observed: 1, total: 1, passed: true},
      rawToolResultValuesAtModel: {observed: 0, total: 4, passed: true}
    }
  };

  for (const [prefix, stageId] of [["", "stageModel"], ["mcp", "mcpStageModel"]]) {
    tool.boundaryEvidence.modelRawValues = {observed: 0, total: 4, passed: true};
    view.page.renderToolEvidence(prefix, tool);
    assert.equal(view.element(stageId).querySelector("small").textContent, "PASS");

    tool.boundaryEvidence.modelRawValues = {observed: 1, total: 4, passed: false};
    view.page.renderToolEvidence(prefix, tool);
    assert.equal(view.element(stageId).querySelector("small").textContent, "FAIL");
  }
});

test("service-wide session counts remain observations, including concurrent sessions", async () => {
  const view = await createDemoView();
  view.render(allowedSupport, {activeSessionsAfterCall: 2});
  assert.equal(view.element("securityCleanupMeaning").textContent, "ACTIVE SESSIONS: 2");
  assert.equal(view.element("securityCleanupStatus").textContent, "OBSERVED");
  for (const prefix of ["", "mcp"]) {
    view.page.setSessionCount(prefix, 2);
    const stage = view.element(prefix ? "mcpStageCleanup" : "stageCleanup");
    assert.equal(stage.querySelector("small").textContent, "2");
  }
});

test("shows backend model call counts without assuming success", () => {
  const {view, element} = createInspectionView();
  const response = allowedInspectionResponse();
  response.outputCase.modelCalls = 3;
  view.display(response);
  assert.match(element("#inspectionStatus").textContent, /output case 3/);
});

test("does not claim an output inspection occurred when there is no report", () => {
  const {view, html} = createInspectionView();
  const response = allowedInspectionResponse();
  response.outputCase.outputInspection = null;
  view.display(response);
  assert.doesNotMatch(html(), /Output inspection passed/);
});

test("reset clears previous inspection results", () => {
  const {view, element, html} = createInspectionView();
  view.display(allowedInspectionResponse());
  view.reset();
  assert.doesNotMatch(html(), /Input inspection passed|Output inspection passed|Protected response delivered/);
  view.setLanguage("ko");
  assert.equal(element("#inspectionStatus").textContent, "");
});

test("escapes backend text and only shortens valid opaque tokens", () => {
  const {view, html} = createInspectionView();
  const response = allowedInspectionResponse();
  response.outputCase.rawModelResponse = '<img src=x onerror="alert(1)">';
  response.outputCase.finalResponse = "[PII_EMAIL_ADDRESS_1] " + inspectionToken;
  view.display(response);
  assert.doesNotMatch(html(), /<img/);
  assert.match(html(), /&lt;img src=x onerror=&quot;alert\(1\)&quot;&gt;/);
  assert.match(html(), /\[PII_EMAIL_ADDRESS_1\]/);
  assert.match(html(), /\[PII_EMAIL_ADDRESS_…_1\]/);
  assert.match(html(), /title="\[PII_EMAIL_ADDRESS_1:AbCdEfGhIjKlMnOp\]"/);
});

test("shows a blocked model call only for an observed input block", () => {
  const {view, html} = createInspectionView();
  const response = allowedInspectionResponse();
  response.inputCase = {
    ...response.inputCase, modelCalls: 0, modelInput: null, rawModelResponse: null,
    finalResponse: null, blockedAt: "MODEL_REQUEST", exceptionType: "InspectionBlockedException",
    inputInspection: {inspectedText: inspectionToken, report: {decision: "BLOCK", outcomes: [{result: {
      findings: [{category: "PROMPT_INJECTION", code: "INSTRUCTION_OVERRIDE"}]
    }}]}}
  };
  view.display(response);
  assert.match(html(), /Model call blocked/);
  assert.match(html(), /PROMPT_INJECTION · INSTRUCTION_OVERRIDE/);
  assert.match(html(), /Request never reaches the model/);
  view.display(allowedInspectionResponse());
  assert.doesNotMatch(html(), /Model call blocked|Request never reaches the model/);
});

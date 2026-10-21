const assert = require("node:assert/strict");
const {readFileSync} = require("node:fs");
const {join} = require("node:path");
const {test} = require("node:test");
const {runInNewContext} = require("node:vm");

const html = readFileSync(join(__dirname, "../../main/resources/static/index.html"), "utf8");
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const startup = script.indexOf('  element("scenarioSelector").addEventListener');
assert.ok(startup > 0);

// Run the page's actual render, reset, translation, and request logic with a small DOM substitute.
function createView(language = "en") {
  const elements = new Map();
  const requests = [];
  function node() {
    const classes = new Set();
    let ownText = "";
    return {
      dataset: {}, style: {}, children: [],
      get textContent() {
        return ownText + this.children.map(child => typeof child === "string" ? child : child.textContent).join("");
      },
      set textContent(value) { ownText = String(value); this.children = []; },
      classList: {
        add: (...names) => names.forEach(name => classes.add(name)),
        remove: (...names) => names.forEach(name => classes.delete(name)),
        contains: name => classes.has(name),
        toggle: (name, force) => force ? classes.add(name) : classes.delete(name)
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
  element("scenarioSelector").value = "security";
  const page = runInNewContext(script.slice(0, startup) + `
    currentLanguage = ${JSON.stringify(language)};
    ({renderSecurityEvidence, renderRagBoundary, setSessionCount, run});
  `, {document, fetch: async (path, options) => {
    requests.push({path, options});
    return {ok: false, status: 503};
  }});
  return {
    element,
    page,
    requests,
    render(customerSupport = allowedSupport, overrides = {}) {
      page.renderSecurityEvidence({
        generalEmployee: deniedEmployee, customerSupport,
        requestSummary: "Look up the customer information.", activeSessionsAfterCall: 0,
        ...overrides
      });
    }
  };
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

test("renders a denied support request without claiming a privacy failure", () => {
  const view = createView();
  view.render(deniedSupport);
  assert.match(view.element("securitySupportOutcome").textContent, /Permission denied/);
  assert.equal(view.element("securityFinalResponse").textContent, "");
  assert.equal(view.element("securityAuthorizationStatus").textContent, "FAIL");
  for (const metric of ["ToolInput", "ToolOutput"]) {
    assert.equal(view.element(`security${metric}Meaning`).textContent, "NOT EXECUTED");
    assert.equal(view.element(`security${metric}Status`).textContent, "—");
    assert.equal(view.element(`security${metric}Metric`).classList.contains("fail"), false);
  }
});

test("renders the allowed response as formatted JSON", () => {
  const view = createView();
  view.render();
  assert.equal(view.element("securityAuthorizationStatus").textContent, "PASS");
  assert.match(view.element("securitySupportOutcome").textContent, /protected CRM result/);
  assert.equal(view.element("securityFinalResponse").textContent, '{\n  "status": "found"\n}');
});

test("highlights compact tokens without changing the protected response", () => {
  const nonce = "_-0123456789aBcD";
  const payload = {
    employeeId: `[PII_EMPLOYEE_ID_1:${nonce}]`,
    email: `[PII_EMAIL_ADDRESS_12:${nonce}]`,
    phone: `[PII_PHONE_NUMBER_3:${nonce}]`,
    customerId: `[PII_CUSTOMER_ID_1:${nonce}]`
  };
  const view = createView();
  view.render({...allowedSupport, finalResponse: JSON.stringify(payload)});

  const response = view.element("securityFinalResponse");
  assert.equal(response.textContent, JSON.stringify(payload, null, 2));
  const tokens = response.children.filter(child => child.className === "opaque-token");
  assert.deepEqual(tokens.map(token => token.textContent), Object.values(payload));
  assert.equal(tokens[1].children[1].textContent, "PII_EMAIL_ADDRESS");
  assert.equal(tokens[1].children[2].textContent, `_12:${nonce}`);
});

test("leaves malformed token text unhighlighted", () => {
  const nonce = "_-0123456789aBcD";
  const text = [
    "[PII_EMAIL_ADDRESS_1]",
    `[PII_EMAIL_ADDRESS_1:${nonce.slice(1)}]`,
    `[PII_EMAIL_ADDRESS_1:${nonce}A]`,
    `[PII_EMAIL_ADDRESS_0:${nonce}]`
  ].join(" ");
  const view = createView();
  view.render({...allowedSupport, finalResponse: text});

  const response = view.element("securityFinalResponse");
  assert.equal(response.textContent, text);
  assert.equal(response.children.filter(child => child.className === "opaque-token").length, 0);
});

test("clears the previous response when a later support request is denied", () => {
  const view = createView();
  view.render();
  view.render(deniedSupport);
  assert.match(view.element("securitySupportOutcome").textContent, /Permission denied/);
  assert.equal(view.element("securityFinalResponse").textContent, "");
});

for (const language of ["en", "ko"]) {
  test(`shows actual callback counts, including unexpected repeated calls (${language})`, () => {
    const view = createView(language);
    view.render({...allowedSupport, callbackInvocations: 2}, {
      generalEmployee: {...deniedEmployee, callbackInvocations: 2}
    });
    for (const role of ["Viewer", "Support"]) {
      assert.equal(view.element(`security${role}CallbackState`).textContent, language === "en" ? "2 CALLS" : "2회");
      assert.equal(view.element(`security${role}CallbackStep`).classList.contains("fail"), true);
    }
    assert.equal(view.element("securityAuthorizationStatus").textContent, "FAIL");
    assert.equal(view.element("securityAuthorizationMeaning").dataset.i18n, "security.notConfirmed");
    assert.equal(view.element("securityViewerOutcome").dataset.i18n, "security.outcome.notConfirmed");
  });

  test(`failed rerun does not restore stale results during translation (${language})`, async () => {
    const view = createView(language);
    view.render();
    await view.page.run();
    assert.equal(view.requests.length, 1);
    assert.equal(view.requests[0].path, "/demo/security-tool-boundary");
    assert.equal(view.requests[0].options.headers["Accept-Language"], language);
    for (const metric of ["Authorization", "ToolInput", "ToolOutput", "Cleanup"]) {
      assert.equal(view.element(`security${metric}Meaning`).textContent, "—");
      assert.equal(view.element(`security${metric}Status`).dataset.i18n, "status.wait");
    }
    for (const id of ["ViewerOutcome", "SupportOutcome", "FinalResponse", "ViewerRequest", "SupportRequest"]) {
      assert.equal(view.element(`security${id}`).textContent, "—");
      assert.equal(view.element(`security${id}`).dataset.i18n, undefined);
    }
    assert.match(view.element("error").textContent, /503/);
    assert.equal(view.element("run").disabled, false);
    assert.equal(view.element("scenarioSelector").disabled, false);
  });
}

test("failed privacy checks do not retain successful explanations", () => {
  const view = createView();
  view.render({...allowedSupport, toolReceivedOnlyAllowedOriginals: false, toolResultRetokenizedBeforeModel: false});
  for (const metric of ["ToolInput", "ToolOutput"]) {
    assert.equal(view.element(`security${metric}Meaning`).textContent, "NOT CONFIRMED");
    assert.equal(view.element(`security${metric}Status`).textContent, "FAIL");
  }
  assert.equal(view.element("securitySupportOutcome").dataset.i18n, "security.outcome.notConfirmed");
});

test("absence of a tool request is not labeled a permission denial", () => {
  const view = createView();
  view.render({...deniedSupport, modelRequestedTool: false, toolCallDenied: false});
  assert.equal(view.element("securitySupportRequest").textContent, "NOT REQUESTED");
  assert.equal(view.element("securitySupportOutcome").textContent, "No tool execution was recorded.");
  assert.equal(view.element("securityAuthorizationStatus").textContent, "FAIL");
});

test("RAG protection requires complete backend evidence", () => {
  const view = createView();
  const evidence = {
    retrievedDocumentContainsRawPii: true,
    modelVisibleContextContainsRawPii: false,
    modelVisibleContextContainsTokenizedPii: true
  };
  view.page.renderRagBoundary(evidence);
  assert.equal(view.element("ragBoundaryState").dataset.i18n, "rag.boundaryProtected");

  for (const missingEvidence of [
    {retrievedDocumentContainsRawPii: false},
    {modelVisibleContextContainsRawPii: true},
    {modelVisibleContextContainsTokenizedPii: false}
  ]) {
    view.page.renderRagBoundary({...evidence, ...missingEvidence});
    assert.equal(view.element("ragBoundaryState").dataset.i18n, "rag.boundaryNotConfirmed");
  }
});

test("service-wide session counts remain observations, including concurrent sessions", () => {
  const view = createView();
  view.render(allowedSupport, {activeSessionsAfterCall: 2});
  assert.equal(view.element("securityCleanupMeaning").textContent, "ACTIVE SESSIONS: 2");
  assert.equal(view.element("securityCleanupStatus").textContent, "OBSERVED");
  assert.equal(view.element("securityCleanupMetric").classList.contains("fail"), false);
  for (const prefix of ["", "mcp"]) {
    view.page.setSessionCount(prefix, 2);
    const stage = view.element(prefix ? "mcpStageCleanup" : "stageCleanup");
    assert.equal(stage.querySelector("small").textContent, "2");
    assert.equal(stage.classList.contains("fail"), false);
    assert.equal(stage.classList.contains("pass"), false);
  }
});

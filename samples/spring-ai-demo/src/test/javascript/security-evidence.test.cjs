const assert = require("node:assert/strict");
const {readFileSync} = require("node:fs");
const {join} = require("node:path");
const {test} = require("node:test");

const html = readFileSync(join(__dirname, "../../main/resources/static/index.html"), "utf8");

// Run the page's own renderer, replacing only its DOM-facing helpers.
function pageFunction(name, parameters) {
  const match = html.match(new RegExp(`  function ${name}\\([^\\n]*\\) \\{([\\s\\S]*?)\\n  \\}`));
  assert.ok(match, `Missing page function: ${name}`);
  return new Function(...parameters, match[1]);
}

const renderSecurityEvidence = pageFunction("renderSecurityEvidence", [
  "security", "setSecurityMetric", "setTechnicalText", "setLocalizedText", "element",
  "text", "setAuthorizationStep", "prettyJsonPayload", "renderTokenEvidence"
]);
const prettyJsonPayload = pageFunction("prettyJsonPayload", ["value"]);

function createView() {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {});
    return elements.get(id);
  };
  const noOp = () => {};
  return {
    element,
    render(customerSupport) {
      renderSecurityEvidence(
        {generalEmployee: deniedEmployee, customerSupport, activeSessionsAfterCall: 0},
        noOp, noOp,
        (target, key) => { target.textContent = key; },
        element, key => key, noOp, prettyJsonPayload,
        (id, value) => {
          assert.equal(typeof value, "string");
          element(id).textContent = value;
        }
      );
    }
  };
}

const deniedEmployee = {
  role: "ROLE_EMPLOYEE",
  exposedToolNames: [],
  callbackInvocations: 0,
  toolCallDenied: true,
  modelRequestedTool: true,
  finalResponse: null
};
const allowedSupport = {
  ...deniedEmployee,
  role: "ROLE_CUSTOMER_SUPPORT",
  exposedToolNames: ["customerLookup"],
  callbackInvocations: 1,
  toolCallDenied: false,
  finalResponse: 'done {"status":"found"}'
};
const deniedSupport = {...deniedEmployee, role: "ROLE_CUSTOMER_SUPPORT"};

test("renders a denied support request with no final response", () => {
  const view = createView();
  view.render(deniedSupport);
  assert.equal(view.element("securitySupportOutcome").textContent, "security.outcome.denied");
  assert.equal(view.element("securityFinalResponse").textContent, "");
});

test("renders the allowed response as formatted JSON", () => {
  const view = createView();
  view.render(allowedSupport);
  assert.equal(view.element("securitySupportOutcome").textContent, "security.outcome.allowed");
  assert.equal(view.element("securityFinalResponse").textContent, '{\n  "status": "found"\n}');
});

test("clears the previous response when a later support request is denied", () => {
  const view = createView();
  view.render(allowedSupport);
  view.render(deniedSupport);
  assert.equal(view.element("securitySupportOutcome").textContent, "security.outcome.denied");
  assert.equal(view.element("securityFinalResponse").textContent, "");
});

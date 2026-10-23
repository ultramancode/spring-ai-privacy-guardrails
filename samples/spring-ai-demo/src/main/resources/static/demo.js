const element = id => document.getElementById(id);
const opaqueTokenEvidencePattern = /(\[)(PII_(?:EMPLOYEE_ID|EMAIL_ADDRESS|PHONE_NUMBER|CUSTOMER_ID))(_[1-9]\d*:[A-Za-z0-9_-]{16})(\])/g;

const scenarios = Object.freeze({
  "local-tool": {
    panelId: "localToolScenario",
    endpoint: "/demo/tool-loop",
    titleKey: "scenarioTitle.localTool",
    leadKey: "lead.localTool"
  },
  rag: {
    panelId: "ragScenario",
    endpoint: "/demo/rag",
    titleKey: "scenarioTitle.rag",
    leadKey: "lead.rag"
  },
  mcp: {
    panelId: "mcpScenario",
    endpoint: "/demo/mcp-tool-loop",
    titleKey: "scenarioTitle.mcp",
    leadKey: "lead.mcp"
  },
  security: {
    panelId: "securityScenario",
    endpoint: "/demo/security-tool-boundary",
    titleKey: "scenarioTitle.security",
    leadKey: "lead.security"
  },
  inspection: {
    panelId: "inspectionScenario",
    endpoint: "/demo/inspection",
    titleKey: "scenarioTitle.inspection",
    leadKey: "lead.inspection"
  }
});

let currentLanguage = "en";
let running = false;
let lastFailure = null;

async function runSelectedScenario() {
  if (running) return;
  const button = element("run");
  const selector = element("scenarioSelector");
  const scenarioKey = selector.value;
  const scenario = scenarios[scenarioKey];
  const locale = currentLanguage;
  running = true;
  privacyInspectionDemo.setRunning(true);
  button.disabled = true;
  selector.disabled = true;
  document.querySelectorAll("[data-language]").forEach(languageButton => {
    languageButton.disabled = true;
  });

  lastFailure = null;
  element("error").style.display = "none";
  applyTranslations();

  try {
    if (scenarioKey === "local-tool") {
      await runLocalTool(scenario, locale);
    } else if (scenarioKey === "rag") {
      await runRag(scenario, locale);
    } else if (scenarioKey === "mcp") {
      await runMcp(scenario, locale);
    } else if (scenarioKey === "security") {
      await runSecurity(scenario, locale);
    } else if (scenarioKey === "inspection") {
      await runInspection(scenario, locale);
    }
  } catch (failure) {
    showFailure(failure);
  } finally {
    running = false;
    privacyInspectionDemo.setRunning(false);
    button.disabled = false;
    selector.disabled = false;
    document.querySelectorAll("[data-language]").forEach(languageButton => {
      languageButton.disabled = false;
    });
    applyTranslations();
  }
}

async function runLocalTool(scenario, locale) {
  resetToolEvidence("");
  const source = await fetchJson("/demo/example-input", locale);
  const analysis = await fetchJson("/demo/protect", locale);
  const tool = await fetchJson(scenario.endpoint, locale);

  element("source").textContent = source.input;
  renderAnalyzerFindings(analysis.detectedSpans);
  renderToolEvidence("", tool);
}

async function runRag(scenario, locale) {
  resetRagEvidence();
  const rag = await fetchJson(scenario.endpoint, locale);
  const retrievedRaw = booleanText(rag.retrievedDocumentContainsRawPii);
  const modelRaw = booleanText(rag.modelInputContainsRawPii);
  const modelToken = booleanText(rag.modelInputContainsTokenizedPii);
  const activeSessions = String(rag.activeSessionsAfterCall);

  element("retrievedDocument").textContent = rag.retrievedDocument;
  renderTokenEvidence("modelInput", rag.modelInput);
  renderRagBoundary(rag);

  setLocalizedText(
    element("ragRetrievedRaw"),
    rag.retrievedDocumentContainsRawPii ? "rag.present" : "summary.none"
  );
  element("ragRetrievedRawDetail").textContent = retrievedRaw;
  setLocalizedText(
    element("ragModelRaw"),
    rag.modelInputContainsRawPii ? "rag.present" : "summary.none"
  );
  element("ragModelRawDetail").textContent = modelRaw;
  setLocalizedText(
    element("ragModelToken"),
    rag.modelInputContainsTokenizedPii ? "rag.present" : "summary.none"
  );
  element("ragModelTokenDetail").textContent = modelToken;
  element("ragActiveSessionsDetail").textContent = activeSessions;
}

async function runMcp(scenario, locale) {
  resetToolEvidence("mcp");
  const responses = await Promise.all([
    fetchJson("/demo/example-input", locale),
    fetchJson(scenario.endpoint, locale)
  ]);
  const source = responses[0];
  const tool = responses[1];

  element("mcpSource").textContent = source.input;
  element("mcpModelCalls").textContent = String(tool.modelCalls);
  renderToolEvidence("mcp", tool);
}

async function runSecurity(scenario, locale) {
  resetSecurityEvidence();
  const security = await fetchJson(scenario.endpoint, locale);
  renderSecurityEvidence(security);
}

async function runInspection(scenario, locale) {
  privacyInspectionDemo.reset();
  const response = await fetchJson(scenario.endpoint + "?example=" + privacyInspectionDemo.example, locale);
  privacyInspectionDemo.display(response);
}

async function fetchJson(path, locale) {
  const response = await fetch(path, {headers: {
    Accept: "application/json",
    "Accept-Language": locale
  }});
  if (!response.ok) {
    const failure = new Error(path);
    failure.httpPath = path;
    failure.httpStatus = response.status;
    throw failure;
  }
  return response.json();
}

function text(key) {
  return translations[currentLanguage][key] || translations.en[key] || key;
}

function selectedScenario() {
  return scenarios[element("scenarioSelector").value];
}

function prefixedElementId(prefix, id) {
  if (!prefix) return id;
  return prefix + id.charAt(0).toUpperCase() + id.slice(1);
}

function setLocalizedText(target, key) {
  target.dataset.i18n = key;
  target.textContent = text(key);
}

function setTechnicalText(target, value) {
  delete target.dataset.i18n;
  target.textContent = String(value);
}

function failureMessage(failure) {
  if (failure.httpPath !== undefined) {
    return failure.httpPath + " " + text("error.returnedHttp") + " " + failure.httpStatus;
  }
  return failure.message;
}

function applyTranslations() {
  document.documentElement.lang = currentLanguage;
  document.title = text("title");
  document.querySelectorAll("[data-i18n]").forEach(target => {
    target.textContent = text(target.dataset.i18n);
  });
  document.querySelectorAll("[data-i18n-aria-label]").forEach(target => {
    target.setAttribute("aria-label", text(target.dataset.i18nAriaLabel));
  });
  document.querySelectorAll("[data-language]").forEach(button => {
    button.setAttribute("aria-pressed", String(button.dataset.language === currentLanguage));
  });
  element("run").textContent = running ? text("running") : text("run");
  privacyInspectionDemo.setLanguage(currentLanguage);
  if (lastFailure !== null) {
    element("error").textContent = text("errorPrefix") + ": " + failureMessage(lastFailure);
  }
}

function showSelectedScenario() {
  const scenario = selectedScenario();
  document.querySelectorAll(".scenario-panel").forEach(panel => {
    panel.hidden = panel.id !== scenario.panelId;
  });
  element("scenarioTitle").dataset.i18n = scenario.titleKey;
  element("scenarioLead").dataset.i18n = scenario.leadKey;
  hideFailure();
  applyTranslations();
}

function setCheck(prefix, id, passed) {
  const target = element(prefixedElementId(prefix, id));
  target.textContent = passed ? "PASS" : "FAIL";
  target.classList.toggle("fail", !passed);
}

function setMetric(prefix, id, evidence, meaningKey) {
  const metric = element(prefixedElementId(prefix, id + "Metric"));
  setLocalizedText(element(prefixedElementId(prefix, id + "Meaning")), meaningKey);
  element(prefixedElementId(prefix, id + "Count")).textContent = evidence.observed + " / " + evidence.total;
  setTechnicalText(element(prefixedElementId(prefix, id + "Status")), evidence.passed ? "PASS" : "FAIL");
  metric.classList.toggle("pass", evidence.passed);
  metric.classList.toggle("fail", !evidence.passed);
}

function setStageResult(prefix, id, passed) {
  const stage = element(prefixedElementId(prefix, id));
  stage.classList.toggle("pass", passed);
  stage.classList.toggle("fail", !passed);
  setTechnicalText(stage.querySelector("small"), passed ? "PASS" : "FAIL");
}

function setSessionCount(prefix, count) {
  const stage = element(prefixedElementId(prefix, "stageCleanup"));
  stage.classList.remove("pass", "fail");
  setTechnicalText(stage.querySelector("small"), count);
}

function setChips(id, values) {
  const container = element(id);
  container.replaceChildren(...values.map(value => {
    const chip = document.createElement("span");
    chip.className = "chip";
    if (value === "CUSTOMER_ID") {
      setLocalizedText(chip, "disclosure.customerId");
    } else {
      setTechnicalText(chip, value);
    }
    return chip;
  }));
}

function prettyJsonPayload(value) {
  try {
    const parsed = JSON.parse(value);
    return parsed !== null && typeof parsed === "object"
      ? JSON.stringify(parsed, null, 2)
      : value;
  } catch (ignored) {
    return value;
  }
}

function prettyFinalResponse(value) {
  const objectStart = value.indexOf("{");
  if (objectStart < 0) {
    return value;
  }
  const prefix = value.slice(0, objectStart).trimEnd();
  const payload = value.slice(objectStart);
  try {
    const parsed = JSON.parse(payload);
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
      return value;
    }
    return prefix + "\n\n" + JSON.stringify(parsed, null, 2);
  } catch (ignored) {
    return value;
  }
}

function renderTokenEvidence(id, value, prettyJson = false) {
  const renderedValue = prettyJson ? prettyJsonPayload(value) : value;
  const nodes = [];
  let offset = 0;
  for (const match of renderedValue.matchAll(opaqueTokenEvidencePattern)) {
    nodes.push(document.createTextNode(renderedValue.slice(offset, match.index)));
    const token = document.createElement("span");
    token.className = "opaque-token";
    const entity = document.createElement("span");
    entity.className = "token-entity";
    entity.textContent = match[2];
    const detail = document.createElement("span");
    detail.className = "token-detail";
    detail.textContent = match[3];
    token.append(match[1], entity, detail, match[4]);
    nodes.push(token);
    offset = match.index + match[0].length;
  }
  nodes.push(document.createTextNode(renderedValue.slice(offset)));
  element(id).replaceChildren(...nodes);
}

function renderAnalyzerFindings(spans) {
  element("spans").replaceChildren(...spans.map(span => {
    const row = document.createElement("tr");
    [span.type, span.providers.join(", "), span.start, span.end, span.reason].forEach(value => {
      const cell = document.createElement("td");
      cell.textContent = String(value);
      row.appendChild(cell);
    });
    return row;
  }));
}

function resetToolEvidence(prefix) {
  ["modelRaw", "deniedTool", "allowedTool", "resultRaw"].forEach(id => {
    const metric = element(prefixedElementId(prefix, id + "Metric"));
    metric.classList.remove("pass", "fail");
    setTechnicalText(element(prefixedElementId(prefix, id + "Meaning")), "—");
    element(prefixedElementId(prefix, id + "Count")).textContent = "—";
    setLocalizedText(element(prefixedElementId(prefix, id + "Status")), "status.wait");
  });
  ["stageModel", "stageTool", "stageResult", "stageCleanup"].forEach(id => {
    const stage = element(prefixedElementId(prefix, id));
    stage.classList.remove("pass", "fail");
    setLocalizedText(stage.querySelector("small"), "status.wait");
  });
  ["source", "protected", "toolArguments", "finalResponse"].forEach(id => {
    element(prefixedElementId(prefix, id)).textContent = "—";
  });
  ["allowedDisclosureCheck", "lookupCheck"].forEach(id => {
    const target = element(prefixedElementId(prefix, id));
    target.textContent = "—";
    target.classList.remove("fail");
  });
  setChips(prefixedElementId(prefix, "allowedOriginalEntityTypes"), []);
  if (!prefix) {
    renderAnalyzerFindings([]);
  } else {
    element("mcpModelCalls").textContent = "—";
  }
}

function resetRagEvidence() {
  ["ragRetrievedRaw", "ragModelRaw", "ragModelToken"].forEach(id => {
    setTechnicalText(element(id), "—");
  });
  ["ragRetrievedRawDetail", "ragModelRawDetail", "ragModelTokenDetail",
    "ragActiveSessionsDetail"].forEach(id => {
    element(id).textContent = "—";
  });
  ["ragBoundaryInput", "ragBoundaryOutput", "ragBoundaryState"].forEach(id => {
    setLocalizedText(element(id), "status.wait");
  });
  element("retrievedDocument").textContent = "—";
  element("modelInput").textContent = "—";
}

function renderRagBoundary(rag) {
  const protectedBoundary = rag.retrievedDocumentContainsRawPii
    && !rag.modelInputContainsRawPii
    && rag.modelInputContainsTokenizedPii;
  let modelInputKey = "rag.tokenNotObserved";
  if (rag.modelInputContainsRawPii) {
    modelInputKey = "rag.modelRawObserved";
  } else if (rag.modelInputContainsTokenizedPii) {
    modelInputKey = "rag.opaqueToken";
  }

  setLocalizedText(
    element("ragBoundaryInput"),
    rag.retrievedDocumentContainsRawPii ? "rag.rawPii" : "summary.none"
  );
  setLocalizedText(element("ragBoundaryOutput"), modelInputKey);
  setLocalizedText(
    element("ragBoundaryState"),
    protectedBoundary ? "rag.boundaryProtected" : "rag.boundaryNotConfirmed"
  );
}

function renderToolEvidence(prefix, tool) {
  const evidence = tool.boundaryEvidence;
  let allowedMeaning = "summary.scopedRestored";
  if (tool.allowedOriginalEntityTypes.length === 1
    && tool.allowedOriginalEntityTypes[0] === "CUSTOMER_ID") {
    allowedMeaning = "summary.customerOnly";
  }

  renderTokenEvidence(prefixedElementId(prefix, "protected"), tool.protectedModelInput);
  renderTokenEvidence(prefixedElementId(prefix, "toolArguments"), tool.tokenizedToolArguments, true);
  renderTokenEvidence(prefixedElementId(prefix, "finalResponse"), prettyFinalResponse(tool.finalResponse));
  setChips(prefixedElementId(prefix, "allowedOriginalEntityTypes"), tool.allowedOriginalEntityTypes);

  setMetric(
    prefix,
    "modelRaw",
    evidence.modelRawValues,
    evidence.modelRawValues.observed === 0 ? "summary.none" : "summary.rawObserved"
  );
  setMetric(
    prefix,
    "deniedTool",
    evidence.deniedToolRawValues,
    evidence.deniedToolRawValues.observed === 0 ? "summary.none" : "summary.rawObserved"
  );
  setMetric(prefix, "allowedTool", evidence.allowedToolRawValues, allowedMeaning);
  setMetric(
    prefix,
    "resultRaw",
    evidence.rawToolResultValuesAtModel,
    evidence.rawToolResultValuesAtModel.observed === 0 ? "summary.none" : "summary.rawObserved"
  );
  setCheck(prefix, "allowedDisclosureCheck", tool.toolReceivedOnlyAllowedOriginals);
  setCheck(prefix, "lookupCheck", tool.toolLookupSucceeded);
  setStageResult(prefix, "stageModel", evidence.modelRawValues.passed);
  setStageResult(prefix, "stageTool", tool.toolReceivedOnlyAllowedOriginals);
  setStageResult(prefix, "stageResult", tool.toolResultRetokenizedBeforeModel);
  setSessionCount(prefix, tool.activeSessionsAfterCall);
}

function resetSecurityEvidence() {
  setTechnicalText(element("securityRequest"), "—");
  ["Authorization", "ToolInput", "ToolOutput", "Cleanup"].forEach(id => {
    const metric = element("security" + id + "Metric");
    metric.classList.remove("pass", "fail");
    setTechnicalText(element("security" + id + "Meaning"), "—");
    setLocalizedText(element("security" + id + "Status"), "status.wait");
  });
  ["EmployeeDefinition", "EmployeeRequest", "EmployeeCallback",
    "SupportDefinition", "SupportRequest", "SupportCallback"].forEach(id => {
    const step = element("security" + id + "Step");
    step.classList.remove("pass", "blocked", "fail");
  });
  ["EmployeeDefinition", "EmployeeRequest", "EmployeeCallbackState",
    "SupportDefinition", "SupportRequest", "SupportCallbackState",
    "EmployeeOutcome", "SupportOutcome",
    "FinalResponse"].forEach(id => {
    setTechnicalText(element("security" + id), "—");
  });
}

function setAuthorizationStep(stepId, valueId, stateKey, style) {
  const step = element("security" + stepId + "Step");
  step.classList.remove("pass", "blocked", "fail");
  step.classList.add(style);
  setLocalizedText(element("security" + valueId), stateKey);
}

function setSecurityMetric(id, passed, meaningKey) {
  let status = "FAIL";
  let stateKey = "security.notConfirmed";
  if (passed === null) {
    status = "—";
    stateKey = "security.notExecuted";
  } else if (passed) {
    status = "PASS";
    stateKey = meaningKey;
  }
  const metric = element("security" + id + "Metric");
  metric.classList.toggle("pass", passed === true);
  metric.classList.toggle("fail", passed === false);
  setLocalizedText(element("security" + id + "Meaning"), stateKey);
  setTechnicalText(element("security" + id + "Status"), status);
}

function setCallbackCount(role, count, style) {
  setAuthorizationStep(role + "Callback", role + "CallbackState", "security.calls", style);
  setTechnicalText(element("security" + role + "CallbackState"),
    text(count === 1 ? "security.oneCall" : "security.calls").replace("{count}", count));
}

function toolRequestStateKey(roleResult) {
  if (!roleResult.modelRequestedTool) {
    return "security.notRequested";
  }
  return roleResult.toolCallDenied ? "security.denied" : "security.allowed";
}

function securityOutcomeKey(roleResult) {
  if (roleResult.callbackInvocations === 0) {
    return roleResult.toolCallDenied ? "security.outcome.denied" : "security.outcome.notExecuted";
  }
  if (!roleResult.toolCallDenied
    && roleResult.toolResultRetokenizedBeforeModel
    && roleResult.finalResponse) {
    return "security.outcome.allowed";
  }
  return "security.outcome.notConfirmed";
}

function renderSecurityEvidence(security) {
  const generalEmployee = security.generalEmployee;
  const customerSupport = security.customerSupport;
  const employeeToolHidden = generalEmployee.exposedToolNames.length === 0;
  const employeeCallbackStopped = generalEmployee.callbackInvocations === 0;
  const employeeRequestDenied = generalEmployee.modelRequestedTool && generalEmployee.toolCallDenied;
  const employeeAccessBlocked = employeeToolHidden && employeeRequestDenied && employeeCallbackStopped;

  const supportToolExposed = customerSupport.exposedToolNames.includes("customerLookup");
  const supportExecuted = customerSupport.callbackInvocations > 0;
  const supportCalledOnce = customerSupport.callbackInvocations === 1;
  const supportRequestAllowed = customerSupport.modelRequestedTool && !customerSupport.toolCallDenied;
  const supportAccessAllowed = supportToolExposed && supportRequestAllowed && supportCalledOnce;
  const authorizationEnforced = employeeAccessBlocked && supportAccessAllowed;

  setLocalizedText(element("securityRequest"), "security.scenarioDescription");
  setSecurityMetric("Authorization", authorizationEnforced, "security.metric.authorizationMeaning");
  setSecurityMetric("ToolInput", supportExecuted ? customerSupport.toolReceivedOnlyAllowedOriginals : null, "security.metric.toolInputMeaning");
  setSecurityMetric("ToolOutput", supportExecuted ? customerSupport.toolResultRetokenizedBeforeModel : null, "security.metric.toolOutputMeaning");
  setTechnicalText(
    element("securityCleanupMeaning"),
    text("security.metric.cleanupMeaning").replace("{count}", security.activeSessionsAfterCall)
  );
  setLocalizedText(element("securityCleanupStatus"), "status.observed");

  element("securityEmployeeRole").textContent = generalEmployee.role;
  setAuthorizationStep(
    "EmployeeDefinition",
    "EmployeeDefinition",
    employeeToolHidden ? "security.hidden" : "security.exposed",
    employeeToolHidden ? "blocked" : "pass"
  );
  setTechnicalText(
    element("securityEmployeeDefinition"),
    JSON.stringify(generalEmployee.exposedToolNames)
  );
  setAuthorizationStep(
    "EmployeeRequest",
    "EmployeeRequest",
    toolRequestStateKey(generalEmployee),
    employeeRequestDenied ? "blocked" : "pass"
  );
  setCallbackCount("Employee", generalEmployee.callbackInvocations, employeeCallbackStopped ? "blocked" : "fail");
  setLocalizedText(element("securityEmployeeOutcome"), securityOutcomeKey(generalEmployee));

  element("securitySupportRole").textContent = customerSupport.role;
  setAuthorizationStep(
    "SupportDefinition",
    "SupportDefinition",
    supportToolExposed ? "security.exposed" : "security.hidden",
    supportToolExposed ? "pass" : "blocked"
  );
  setTechnicalText(
    element("securitySupportDefinition"),
    JSON.stringify(customerSupport.exposedToolNames)
  );
  setAuthorizationStep(
    "SupportRequest",
    "SupportRequest",
    toolRequestStateKey(customerSupport),
    supportRequestAllowed ? "pass" : "blocked"
  );
  setCallbackCount("Support", customerSupport.callbackInvocations, supportCalledOnce ? "pass" : "fail");
  setLocalizedText(element("securitySupportOutcome"), securityOutcomeKey(customerSupport));

  const finalResponse = customerSupport.finalResponse ?? "";
  const payloadStart = finalResponse.indexOf("{");
  const protectedPayload = payloadStart >= 0
    ? prettyJsonPayload(finalResponse.slice(payloadStart))
    : finalResponse;
  renderTokenEvidence("securityFinalResponse", protectedPayload);
}

function booleanText(value) {
  return String(value).toUpperCase();
}

function showFailure(failure) {
  lastFailure = failure;
  element("error").textContent = text("errorPrefix") + ": " + failureMessage(failure);
  element("error").style.display = "block";
}

function hideFailure() {
  lastFailure = null;
  element("error").style.display = "none";
  element("error").textContent = "";
}

element("scenarioSelector").addEventListener("change", () => {
  showSelectedScenario();
  runSelectedScenario();
});
document.querySelectorAll("[data-inspection-example]").forEach(button => {
  button.addEventListener("click", () => {
    if (running || privacyInspectionDemo.example === button.dataset.inspectionExample) return;
    privacyInspectionDemo.selectExample(button.dataset.inspectionExample);
    runSelectedScenario();
  });
});
element("run").addEventListener("click", runSelectedScenario);
document.querySelectorAll("[data-language]").forEach(button => {
  button.addEventListener("click", () => {
    if (running || button.dataset.language === currentLanguage) return;
    currentLanguage = button.dataset.language;
    applyTranslations();
    runSelectedScenario();
  });
});

showSelectedScenario();
runSelectedScenario();

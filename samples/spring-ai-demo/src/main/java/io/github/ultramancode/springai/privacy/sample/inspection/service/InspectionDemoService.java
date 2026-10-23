package io.github.ultramancode.springai.privacy.sample.inspection.service;

import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionBlockedException;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionExecution;
import io.github.ultramancode.springai.privacy.sample.inspection.dto.response.InspectionDemoResponse;
import io.github.ultramancode.springai.privacy.sample.inspection.infrastructure.DemoInspectionChatModel;
import io.github.ultramancode.springai.privacy.sample.inspection.infrastructure.InspectionEvidenceRecorder;
import io.github.ultramancode.springai.privacy.sample.inspection.infrastructure.RecordingContentInspector;
import io.github.ultramancode.springai.privacy.sample.inspection.model.InspectionDemoCaseType;
import io.github.ultramancode.springai.privacy.sample.inspection.model.InspectionDemoData;
import io.github.ultramancode.springai.privacy.sample.inspection.model.InspectionDemoMessages;
import io.github.ultramancode.springai.privacy.sample.inspection.model.InspectionRunResult;
import io.github.ultramancode.springai.privacy.sample.inspection.model.InspectionScope;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public final class InspectionDemoService {

    private final PrivacyService privacyService;

    public InspectionDemoService(PrivacyService privacyService) {
        this.privacyService = privacyService;
    }

    public InspectionDemoResponse runInspectionDemo(Locale locale, InspectionDemoCaseType caseType) {
        InspectionDemoMessages messages = InspectionDemoMessages.getByLocale(locale);
        InspectionRunResult inputResult = runInspection(messages, messages.createInputDemoData(caseType));
        InspectionRunResult outputResult = runInspection(messages, messages.createOutputDemoData(caseType));
        return InspectionDemoResponse.from(
                caseType, inputResult, outputResult,
                this.privacyService.activeSessionCount());
    }

    private InspectionRunResult runInspection(
            InspectionDemoMessages messages,
            InspectionDemoData demoData
    ) {
        InspectionEvidenceRecorder evidenceRecorder = new InspectionEvidenceRecorder();
        DemoInspectionChatModel model = new DemoInspectionChatModel(demoData.modelResponse(), evidenceRecorder);
        GuardrailsPolicy policy = createGuardrailsPolicy(demoData.inspectionScope());
        ChatClient client = createChatClient(messages, model, policy, evidenceRecorder);

        String finalResponse = null;
        try {
            finalResponse = client.prompt().user(demoData.userRequest()).call().content();
        }
        catch (InspectionBlockedException exception) {
            evidenceRecorder.recordBlock(exception);
        }
        return evidenceRecorder.createResult(demoData.userRequest(), finalResponse);
    }

    private GuardrailsPolicy createGuardrailsPolicy(InspectionScope inspectionScope) {
        GuardrailsPolicy.Privacy privacyPolicy = GuardrailsPolicy.Privacy.builder()
                .outputProtection().tokenize()
                .build();
        GuardrailsPolicy.Inspection inspectionPolicy = GuardrailsPolicy.Inspection.builder()
                .modelRequestInspectionEnabled(inspectionScope.includesInput())
                .finalOutputInspectionEnabled(inspectionScope.includesOutput())
                .build();
        return GuardrailsPolicy.builder()
                .privacy(privacyPolicy)
                .inspection(inspectionPolicy)
                .build();
    }

    private ChatClient createChatClient(
            InspectionDemoMessages messages,
            DemoInspectionChatModel model,
            GuardrailsPolicy policy,
            InspectionEvidenceRecorder evidenceRecorder
    ) {
        GuardrailsChatClientFactory factory = GuardrailsChatClientFactory.builder()
                .privacyService(this.privacyService)
                .inputInspectionService(createInputInspectionService(messages, evidenceRecorder))
                .outputInspectionService(createOutputInspectionService(messages, evidenceRecorder))
                .inspectionObserver(evidenceRecorder::recordInspection)
                .build();
        return factory.builder(model, policy).build();
    }

    private InspectionService createInputInspectionService(
            InspectionDemoMessages messages,
            InspectionEvidenceRecorder evidenceRecorder
    ) {
        InspectionRule injectionRule = InspectionRule.literal(
                "INSTRUCTION_OVERRIDE", InspectionFinding.Category.PROMPT_INJECTION, messages.injection());
        RuleBasedContentInspector inputRules = new RuleBasedContentInspector(
                "demo-input-rules", List.of(injectionRule));
        RecordingContentInspector inputInspector = new RecordingContentInspector(
                inputRules, InspectionExecution.Phase.MODEL_REQUEST, evidenceRecorder);
        return new InspectionService(List.of(inputInspector));
    }

    private InspectionService createOutputInspectionService(
            InspectionDemoMessages messages,
            InspectionEvidenceRecorder evidenceRecorder
    ) {
        InspectionRule insultRule = InspectionRule.literal(
                "INSULT", InspectionFinding.Category.TOXICITY, messages.insult());
        RuleBasedContentInspector outputRules = new RuleBasedContentInspector(
                "demo-output-rules", List.of(insultRule));
        RecordingContentInspector outputInspector = new RecordingContentInspector(
                outputRules, InspectionExecution.Phase.FINAL_OUTPUT, evidenceRecorder);
        return new InspectionService(List.of(outputInspector));
    }
}

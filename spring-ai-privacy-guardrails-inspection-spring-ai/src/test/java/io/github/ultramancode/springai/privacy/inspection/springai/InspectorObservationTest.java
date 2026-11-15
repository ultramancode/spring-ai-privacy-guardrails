package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionPolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class InspectorObservationTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private final ObservationRegistry observationRegistry = ObservationRegistry.create();
    private final List<InspectorObservationContext> inspectorObservations = new CopyOnWriteArrayList<>();

    InspectorObservationTest() {
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                if (context instanceof InspectorObservationContext inspectorObservation) {
                    inspectorObservations.add(inspectorObservation);
                }
            }
        });
    }

    @ParameterizedTest(name = "streaming={0}")
    @ValueSource(booleans = {false, true})
    void recordsEachInspectorUnderTheAdvisorThatRanIt(boolean streaming) {
        InspectionService inspectionService = new InspectionService(List.of(allowingInspector("allow-all")));
        ChatClient chatClient = chatClient(inspectionService);

        if (streaming) {
            chatClient.prompt("private prompt").stream().content().collectList().block(TEST_TIMEOUT);
        } else {
            chatClient.prompt("private prompt").call().content();
        }

        assertThat(inspectorObservations).hasSize(2);
        InspectorObservationContext input = inspectorObservations.get(0);
        InspectorObservationContext output = inspectorObservations.get(1);
        assertThat(input.getName()).isEqualTo("spring.ai.inspector");
        assertThat(input.getContextualName()).isEqualTo("inspect allow-all");
        assertThat(input.getInspectionExecution()).isEqualTo(
                new InspectionExecution(InspectionExecution.Phase.MODEL_REQUEST, streaming));
        assertThat(parentAdvisorName(input)).isEqualTo("ModelRequestBoundaryAdvisor");
        assertThat(keyValues(input)).containsOnly(
                entry("spring.ai.inspector.id", "allow-all"),
                entry("spring.ai.inspector.phase", "MODEL_REQUEST"),
                entry("spring.ai.inspector.decision", "ALLOW"),
                entry("spring.ai.inspector.failure.code", "none"));
        assertThat(output.getInspectionExecution()).isEqualTo(
                new InspectionExecution(InspectionExecution.Phase.FINAL_OUTPUT, streaming));
        assertThat(parentAdvisorName(output)).isEqualTo("InspectionOutputAdvisor");
        assertThat(keyValues(output)).containsEntry("spring.ai.inspector.phase", "FINAL_OUTPUT");
    }

    @Test
    void recordsFailureCodeAndBlockDecision() {
        InspectionRule rule = InspectionRule.literal("attack", InspectionFinding.Category.PROMPT_ATTACK, "attack");
        InspectionService inspectionService = new InspectionService(List.of(
                failingInspector("timeout", InspectionFailureCode.TIMEOUT),
                new RuleBasedContentInspector("rules", List.of(rule))),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
        ChatClient chatClient = chatClient(inspectionService);

        assertThatThrownBy(() -> chatClient.prompt("attack").call().content())
                .isInstanceOf(InspectionBlockedException.class);

        assertThat(inspectorObservations).hasSize(2);
        InspectorObservationContext failed = inspectorObservations.get(0);
        assertThat(keyValues(failed))
                .containsEntry("spring.ai.inspector.decision", "ALLOW")
                .containsEntry("spring.ai.inspector.failure.code", "TIMEOUT");
        assertThat(failed.getError()).isInstanceOfSatisfying(InspectionException.class,
                error -> assertThat(error.code()).isEqualTo(InspectionFailureCode.TIMEOUT));
        InspectorObservationContext blocked = inspectorObservations.get(1);
        assertThat(keyValues(blocked))
                .containsEntry("spring.ai.inspector.decision", "BLOCK")
                .containsEntry("spring.ai.inspector.failure.code", "none");
        assertThat(blocked.getError()).isNull();
    }

    private ChatClient chatClient(InspectionService inspectionService) {
        ChatClient.Builder builder = ChatClient.builder(
                new InspectionTestSupport.RecordingChatModel(), observationRegistry, null, null);
        InspectionOutputAdvisor outputAdvisor = new InspectionOutputAdvisor(inspectionService, InspectionLimits.defaults(),
                InspectionObserver.noop(), InspectionOutputAdvisor.DEFAULT_MAX_FRAMES,
                InspectionOutputAdvisor.DEFAULT_STREAM_TIMEOUT, observationRegistry, null);
        new InspectionChatClientConfigurer(inspectionService, InspectionLimits.defaults(),
                PrivacyProcessingStatusResolver.unknown(), InspectionObserver.noop(), observationRegistry, null)
                .withOutputInspection(outputAdvisor)
                .configure(builder);
        return builder.build();
    }

    private static ContentInspector allowingInspector(String inspectorId) {
        return inspector(inspectorId, request -> InspectionResult.completed(
                request.segments().stream().map(ContentSegment::id).collect(Collectors.toSet()), List.of()));
    }

    private static ContentInspector failingInspector(String inspectorId, InspectionFailureCode failureCode) {
        return inspector(inspectorId, request -> InspectionResult.failed(failureCode));
    }

    private static ContentInspector inspector(String inspectorId,
            Function<InspectionRequest, InspectionResult> inspection) {
        return new ContentInspector() {
            @Override
            public InspectionResult inspect(InspectionRequest request) {
                return inspection.apply(request);
            }

            @Override
            public String inspectorId() {
                return inspectorId;
            }

            @Override
            public boolean requiresPrivacyProcessedContent() {
                return false;
            }
        };
    }

    private static String parentAdvisorName(InspectorObservationContext context) {
        assertThat(context.getParentObservation()).isNotNull();
        return ((AdvisorObservationContext) context.getParentObservation().getContextView()).getAdvisorName();
    }

    private static Map<String, String> keyValues(InspectorObservationContext context) {
        return context.getLowCardinalityKeyValues().stream()
                .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }
}

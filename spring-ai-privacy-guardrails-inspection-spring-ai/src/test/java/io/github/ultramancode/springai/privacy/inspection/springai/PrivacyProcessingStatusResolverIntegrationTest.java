package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionPolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionTestSupport.RecordingChatModel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyProcessingStatusResolverIntegrationTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

    enum ResolverFailureMode {
        NULL,
        RUNTIME,
        INSPECTION_EXCEPTION,
        INTERRUPTED_RUNTIME,
        INTERRUPTED_NULL,
        INTERRUPTED_PROCESSED_RETURN
    }

    private InspectionService invalidResultInspectionService(AtomicInteger inspectorInvocationCount) {
        ContentInspector inspector = new ContentInspector() {
            public String inspectorId() {
                return "test";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                inspectorInvocationCount.incrementAndGet();
                return InspectionResult.failed(InspectionFailureCode.INVALID_RESULT);
            }
        };
        return new InspectionService(List.of(inspector), InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
    }

    private void invoke(ChatClient client, boolean streaming) {
        ChatClient.ChatClientRequestSpec request = client.prompt().user("synthetic-private-text");
        if (streaming) {
            request.stream().content().collectList().block(TEST_TIMEOUT);
        } else {
            request.call().content();
        }
    }

    static Stream<Arguments> resolverFailureCases() {
        List<Arguments> cases = new ArrayList<>();
        for (ResolverFailureMode failureMode : ResolverFailureMode.values()) {
            cases.add(Arguments.of(false, false, failureMode));
            cases.add(Arguments.of(false, true, failureMode));
            cases.add(Arguments.of(true, false, failureMode));
            cases.add(Arguments.of(true, true, failureMode));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "streaming={0}, observerThrows={1}, resolver={2}")
    @MethodSource("resolverFailureCases")
    void resolverFailuresAreSanitizedObservedOnceAndCannotFailOpen(
            boolean streaming, boolean observerThrows, ResolverFailureMode failureMode) {
        AtomicInteger inspectorInvocationCount = new AtomicInteger();
        AtomicInteger inspectionReportNotificationCount = new AtomicInteger();
        AtomicBoolean interruptObserved = new AtomicBoolean();
        List<InspectionException> observedInspectionFailures = new CopyOnWriteArrayList<>();
        InspectionObserver observer = new InspectionObserver() {
            public void onInspection(InspectionReport report, InspectionExecution execution) {
                inspectionReportNotificationCount.incrementAndGet();
            }

            public void onFailure(InspectionException failure, InspectionExecution execution) {
                observedInspectionFailures.add(failure);
                interruptObserved.set(Thread.currentThread().isInterrupted());
                if (observerThrows) {
                    throw new IllegalStateException("observer must not replace the inspection failure");
                }
            }
        };
        boolean interrupted = failureMode.name().startsWith("INTERRUPTED_");
        PrivacyProcessingStatusResolver privacyProcessingStatusResolver = request -> {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (failureMode == ResolverFailureMode.NULL || failureMode == ResolverFailureMode.INTERRUPTED_NULL) {
                return null;
            }
            // Interruption must stop the request even when the resolver returns PROCESSED.
            if (failureMode == ResolverFailureMode.INTERRUPTED_PROCESSED_RETURN) {
                return ContentSegment.PrivacyProcessingStatus.PROCESSED;
            }
            // Include private input in exception details to test sanitization.
            // Neither the observer nor the caller should receive that input.
            RuntimeException resolverException;
            if (failureMode == ResolverFailureMode.INSPECTION_EXCEPTION) {
                resolverException = new InspectionException(
                        InspectionFailureCode.PRIVACY_PROCESSING_REQUIRED);
            } else {
                resolverException = new IllegalStateException(request.prompt().getContents());
            }
            resolverException.initCause(new IllegalArgumentException(request.prompt().getContents()));
            resolverException.addSuppressed(new IllegalStateException(request.prompt().getContents()));
            throw resolverException;
        };
        RecordingChatModel model = new RecordingChatModel();
        ChatClient client = new InspectionChatClientConfigurer(
                invalidResultInspectionService(inspectorInvocationCount), InspectionLimits.defaults(),
                privacyProcessingStatusResolver, observer).configure(ChatClient.builder(model)).build();
        InspectionFailureCode expectedFailureCode;
        if (interrupted) {
            expectedFailureCode = InspectionFailureCode.CANCELLED;
        } else if (failureMode == ResolverFailureMode.INSPECTION_EXCEPTION) {
            expectedFailureCode = InspectionFailureCode.PRIVACY_PROCESSING_REQUIRED;
        } else {
            expectedFailureCode = InspectionFailureCode.INVALID_RESULT;
        }
        try {
            assertThatThrownBy(() -> invoke(client, streaming))
                    .isInstanceOfSatisfying(InspectionException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(expectedFailureCode);
                        assertThat(failure).hasMessage("Content inspection failed: " + expectedFailureCode).hasNoCause();
                        assertThat(failure.getSuppressed())
                                .noneMatch(ex -> ex.getMessage().contains("synthetic-private-text"));
                        assertThat(failure.report()).isEmpty();
                        assertThat(observedInspectionFailures).containsExactly(failure);
                    });
            assertThat(interruptObserved.get()).isEqualTo(interrupted);
            assertThat(inspectionReportNotificationCount).hasValue(0);
            assertThat(inspectorInvocationCount).hasValue(0);
            assertThat(model.modelCallCount).hasValue(0);
            // Streaming inspection runs on a boundedElastic worker,
            // so only synchronous calls check the test thread's interrupt flag.
            if (!streaming) {
                assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
            }
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serviceFailureRetainsTheCollectedReportAtTheObserverAndCaller(boolean streaming) {
        AtomicInteger inspectorInvocationCount = new AtomicInteger();
        List<InspectionException> observedInspectionFailures = new CopyOnWriteArrayList<>();
        InspectionObserver observer = new InspectionObserver() {
            public void onInspection(InspectionReport report, InspectionExecution execution) {
                throw new AssertionError("A hard failure must use onFailure");
            }

            public void onFailure(InspectionException failure, InspectionExecution execution) {
                observedInspectionFailures.add(failure);
            }
        };
        RecordingChatModel model = new RecordingChatModel();
        ChatClient client = new InspectionChatClientConfigurer(invalidResultInspectionService(inspectorInvocationCount), InspectionLimits.defaults(),
                PrivacyProcessingStatusResolver.unknown(), observer).configure(ChatClient.builder(model)).build();
        assertThatThrownBy(() -> invoke(client, streaming))
                .isInstanceOfSatisfying(InspectionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
                    assertThat(failure).hasNoCause();
                    assertThat(observedInspectionFailures).containsExactly(failure);

                    InspectionReport report = failure.report().orElseThrow();
                    assertThat(report.outcomes()).hasSize(1);
                    InspectionReport.Outcome outcome = report.outcomes().get(0);
                    assertThat(outcome.inspectorId()).isEqualTo("test");
                    assertThat(outcome.result().failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
                });
        assertThat(inspectorInvocationCount).hasValue(1);
        assertThat(model.modelCallCount).hasValue(0);
    }
}

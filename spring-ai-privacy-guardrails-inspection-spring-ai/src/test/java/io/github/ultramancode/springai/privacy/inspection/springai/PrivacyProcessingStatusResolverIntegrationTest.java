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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyProcessingStatusResolverIntegrationTest {

    enum Failure { NULL, RUNTIME, DECLARED, INTERRUPTED_RUNTIME, INTERRUPTED_NULL, INTERRUPTED_RETURN }

    static Stream<Arguments> failures() {
        return Stream.of(false, true).flatMap(streaming -> Stream.of(false, true).flatMap(observerThrows ->
                Stream.of(Failure.values()).map(failure -> Arguments.of(streaming, observerThrows, failure))));
    }

    @ParameterizedTest(name = "streaming={0}, observerThrows={1}, resolver={2}")
    @MethodSource("failures")
    void resolverFailuresAreSanitizedObservedOnceAndCannotFailOpen(
            boolean streaming, boolean observerThrows, Failure mode) {
        AtomicInteger inspections = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        AtomicBoolean interruptObserved = new AtomicBoolean();
        List<InspectionException> failures = new CopyOnWriteArrayList<>();
        InspectionObserver observer = new InspectionObserver() {
            public void onInspection(InspectionReport report) {
                reports.incrementAndGet();
            }

            public void onFailure(InspectionException failure) {
                failures.add(failure);
                interruptObserved.set(Thread.currentThread().isInterrupted());
                if (observerThrows) {
                    throw new IllegalStateException("observer must not replace the inspection failure");
                }
            }
        };
        boolean interrupted = mode.name().startsWith("INTERRUPTED_");
        PrivacyProcessingStatusResolver resolver = request -> {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (mode == Failure.NULL || mode == Failure.INTERRUPTED_NULL) {
                return null;
            }
            if (mode == Failure.INTERRUPTED_RETURN) {
                return ContentSegment.PrivacyProcessingStatus.PROCESSED;
            }
            RuntimeException failure = mode == Failure.DECLARED
                    ? new InspectionException(InspectionFailureCode.DISCLOSURE_DENIED)
                    : new IllegalStateException(request.prompt().getContents());
            failure.initCause(new IllegalArgumentException(request.prompt().getContents()));
            failure.addSuppressed(new IllegalStateException(request.prompt().getContents()));
            throw failure;
        };
        var model = new InspectionChatClientIntegrationTest.RecordingModel();
        ChatClient client = new InspectionChatClientConfigurer(service(inspections), InspectionLimits.defaults(),
                resolver, observer).configure(ChatClient.builder(model)).build();
        InspectionFailureCode expected = interrupted ? InspectionFailureCode.CANCELLED
                : mode == Failure.DECLARED ? InspectionFailureCode.DISCLOSURE_DENIED : InspectionFailureCode.INVALID_RESULT;
        try {
            assertThatThrownBy(() -> invoke(client, streaming)).isInstanceOfSatisfying(InspectionException.class, failure -> {
                assertThat(failure.failureCode()).isEqualTo(expected);
                assertThat(failure).hasMessage("Content inspection failed: " + expected).hasNoCause();
                assertThat(failure.getSuppressed()).noneMatch(ex -> ex.getMessage().contains("synthetic-private-text"));
                assertThat(failure.report()).isEmpty();
                assertThat(failures).containsExactly(failure);
            });
            assertThat(interruptObserved.get()).isEqualTo(interrupted);
            assertThat(reports).hasValue(0);
            assertThat(inspections).hasValue(0);
            assertThat(model.calls).hasValue(0);
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
        AtomicInteger inspections = new AtomicInteger();
        List<InspectionException> failures = new CopyOnWriteArrayList<>();
        InspectionObserver observer = new InspectionObserver() {
            public void onInspection(InspectionReport report) {
                throw new AssertionError("A hard failure must use onFailure");
            }

            public void onFailure(InspectionException failure) {
                failures.add(failure);
            }
        };
        var model = new InspectionChatClientIntegrationTest.RecordingModel();
        ChatClient client = new InspectionChatClientConfigurer(service(inspections), InspectionLimits.defaults(),
                PrivacyProcessingStatusResolver.unknown(), observer).configure(ChatClient.builder(model)).build();
        assertThatThrownBy(() -> invoke(client, streaming)).isInstanceOfSatisfying(InspectionException.class, failure -> {
            assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
            assertThat(failure).hasNoCause();
            assertThat(failures).containsExactly(failure);
            assertThat(failure.report()).isPresent();
            assertThat(failure.report().orElseThrow().outcomes()).singleElement().satisfies(outcome -> {
                assertThat(outcome.inspectorId()).isEqualTo("test");
                assertThat(outcome.result().failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
            });
        });
        assertThat(inspections).hasValue(1);
        assertThat(model.calls).hasValue(0);
    }

    private InspectionService service(AtomicInteger inspections) {
        ContentInspector inspector = new ContentInspector() {
            public String inspectorId() { return "test"; }
            public boolean requiresPrivacyProcessedContent() { return false; }
            public InspectionResult inspect(InspectionRequest request) {
                inspections.incrementAndGet();
                return InspectionResult.failed(InspectionFailureCode.INVALID_RESULT);
            }
        };
        return new InspectionService(List.of(inspector), InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
    }

    private void invoke(ChatClient client, boolean streaming) {
        var request = client.prompt().user("synthetic-private-text");
        if (streaming) {
            request.stream().content().collectList().block(Duration.ofSeconds(5));
        } else {
            request.call().content();
        }
    }
}

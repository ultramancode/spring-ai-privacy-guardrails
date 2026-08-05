package io.github.ultramancode.springai.privacy.test;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import org.assertj.core.api.AbstractAssert;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** AssertJ assertions over model and tool boundaries captured by a {@link PrivacyTestProbe}. */
public final class PrivacyTestProbeAssert extends AbstractAssert<PrivacyTestProbeAssert, PrivacyTestProbe> {

    PrivacyTestProbeAssert(PrivacyTestProbe actual) {
        super(actual, PrivacyTestProbeAssert.class);
    }

    /**
     * Verifies the number of captured model invocations.
     *
     * @param expected expected non-negative count
     * @return this assertion object
     */
    public PrivacyTestProbeAssert hasModelRequestCount(int expected) {
        isNotNull();
        requireNonNegative(expected, "expected");
        int actualCount = this.actual.modelRequests().size();
        if (actualCount != expected) {
            failWithMessage("Expected <%s> model requests but found <%s>", expected, actualCount);
        }
        return this;
    }

    /**
     * Verifies the number of captured delegate tool invocations.
     *
     * @param expected expected non-negative count
     * @return this assertion object
     */
    public PrivacyTestProbeAssert hasToolCallCount(int expected) {
        isNotNull();
        requireNonNegative(expected, "expected");
        int actualCount = this.actual.toolCalls().size();
        if (actualCount != expected) {
            failWithMessage("Expected <%s> tool calls but found <%s>", expected, actualCount);
        }
        return this;
    }

    /**
     * Verifies that no captured model-visible field contains any supplied raw value.
     *
     * @param rawValues non-empty raw values that must be absent
     * @return this assertion object
     */
    public PrivacyTestProbeAssert modelRequestsDoNotContainRawValues(String... rawValues) {
        isNotNull();
        List<String> values = requiredValues(rawValues, "rawValues");
        String modelContent = allModelContent();
        for (int index = 0; index < values.size(); index++) {
            if (modelContent.contains(values.get(index))) {
                failWithMessage("Expected model requests not to contain raw value at index <%s>", index);
            }
        }
        return this;
    }

    /**
     * Verifies that captured model content contains a canonical opaque token of one type.
     *
     * @param entityType exact uppercase entity type embedded in the expected token
     * @return this assertion object
     */
    public PrivacyTestProbeAssert modelRequestsContainOpaqueToken(String entityType) {
        isNotNull();
        Pattern token = opaqueTokenPattern(entityType);
        if (!token.matcher(allModelContent()).find()) {
            failWithMessage("Expected model requests to contain an opaque <%s> token", entityType);
        }
        return this;
    }

    /**
     * Verifies the number of distinct canonical tokens in one model request.
     *
     * @param requestIndex zero-based captured request index
     * @param entityType exact uppercase entity type embedded in the expected tokens
     * @param expected expected non-negative number of distinct tokens
     * @return this assertion object
     */
    public PrivacyTestProbeAssert modelRequestHasDistinctOpaqueTokenCount(
            int requestIndex,
            String entityType,
            int expected
    ) {
        isNotNull();
        requireNonNegative(expected, "expected");
        long actualCount = opaqueTokenPattern(entityType)
                .matcher(modelContentAt(requestIndex))
                .results()
                .map(result -> result.group())
                .distinct()
                .count();
        if (actualCount != expected) {
            failWithMessage(
                    "Expected model request <%s> to contain <%s> distinct opaque <%s> tokens but found <%s>",
                    requestIndex,
                    expected,
                    entityType,
                    actualCount
            );
        }
        return this;
    }

    /**
     * Verifies that calls to one delegate tool collectively received every supplied value.
     *
     * @param toolName exact tool definition name
     * @param values non-empty values expected in captured input
     * @return this assertion object
     */
    public PrivacyTestProbeAssert toolInputsContain(String toolName, String... values) {
        isNotNull();
        List<String> expectedValues = requiredValues(values, "values");
        List<ToolCallSnapshot> calls = callsFor(toolName);
        for (int index = 0; index < expectedValues.size(); index++) {
            String expected = expectedValues.get(index);
            boolean found = calls.stream()
                    .map(ToolCallSnapshot::input)
                    .filter(Objects::nonNull)
                    .anyMatch(input -> input.contains(expected));
            if (!found) {
                failWithMessage("Expected tool <%s> to receive value at index <%s>", toolName, index);
            }
        }
        return this;
    }

    /**
     * Verifies that calls to one delegate tool did not receive any supplied raw value.
     *
     * @param toolName exact tool definition name
     * @param rawValues non-empty raw values that must be absent
     * @return this assertion object
     */
    public PrivacyTestProbeAssert toolInputsDoNotContainRawValues(String toolName, String... rawValues) {
        isNotNull();
        List<String> values = requiredValues(rawValues, "rawValues");
        String toolInput = allToolInputs(toolName);
        for (int index = 0; index < values.size(); index++) {
            if (toolInput.contains(values.get(index))) {
                failWithMessage("Expected tool <%s> not to receive raw value at index <%s>", toolName, index);
            }
        }
        return this;
    }

    /**
     * Verifies that one delegate tool received a canonical opaque token of one type.
     *
     * @param toolName exact tool definition name
     * @param entityType exact uppercase entity type embedded in the expected token
     * @return this assertion object
     */
    public PrivacyTestProbeAssert toolInputsContainOpaqueToken(String toolName, String entityType) {
        isNotNull();
        if (!opaqueTokenPattern(entityType).matcher(allToolInputs(toolName)).find()) {
            failWithMessage("Expected tool <%s> to receive an opaque <%s> token", toolName, entityType);
        }
        return this;
    }

    /**
     * Verifies that calls to one delegate tool collectively returned every supplied value.
     *
     * @param toolName exact tool definition name
     * @param values non-empty values expected in captured output
     * @return this assertion object
     */
    public PrivacyTestProbeAssert toolOutputsContain(String toolName, String... values) {
        isNotNull();
        List<String> expectedValues = requiredValues(values, "values");
        List<ToolCallSnapshot> calls = callsFor(toolName);
        for (int index = 0; index < expectedValues.size(); index++) {
            String expected = expectedValues.get(index);
            boolean found = calls.stream()
                    .map(ToolCallSnapshot::output)
                    .filter(Objects::nonNull)
                    .anyMatch(output -> output.contains(expected));
            if (!found) {
                failWithMessage("Expected tool <%s> to return value at index <%s>", toolName, index);
            }
        }
        return this;
    }

    /**
     * Verifies that the bound privacy service retains no active request sessions.
     *
     * @return this assertion object
     */
    public PrivacyTestProbeAssert hasNoActivePrivacySessions() {
        isNotNull();
        int activeSessions = this.actual.activePrivacySessionCount();
        if (activeSessions != 0) {
            failWithMessage("Expected no active privacy sessions but found <%s>", activeSessions);
        }
        return this;
    }

    private String allModelContent() {
        return requiredModelRequests().stream()
                .flatMap(this::modelContent)
                .collect(Collectors.joining("\n"));
    }

    private String modelContentAt(int requestIndex) {
        requireNonNegative(requestIndex, "requestIndex");
        List<ModelRequestSnapshot> requests = requiredModelRequests();
        if (requestIndex >= requests.size()) {
            failWithMessage(
                    "Expected model request index <%s> to exist but only <%s> requests were recorded",
                    requestIndex,
                    requests.size()
            );
        }
        return modelContent(requests.get(requestIndex)).collect(Collectors.joining("\n"));
    }

    private Stream<String> modelContent(ModelRequestSnapshot request) {
        Stream<String> definitions = request.toolDefinitions().stream()
                .flatMap(definition -> Stream.of(
                        definition.name(),
                        definition.description(),
                        definition.inputSchema()
                ));
        Stream<String> controlFields = request.toolControlFields().stream()
                .flatMap(control -> Stream.of(control.id(), control.toolCallType(), control.name()));
        return Stream.concat(
                        request.modelVisibleContent().stream(),
                        Stream.concat(definitions, controlFields)
                )
                .filter(Objects::nonNull);
    }

    private List<ModelRequestSnapshot> requiredModelRequests() {
        List<ModelRequestSnapshot> requests = this.actual.modelRequests();
        if (requests.isEmpty()) {
            failWithMessage("Expected at least one recorded model request");
        }
        return requests;
    }

    private String allToolInputs(String toolName) {
        return callsFor(toolName).stream()
                .map(ToolCallSnapshot::input)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    private List<ToolCallSnapshot> callsFor(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        List<ToolCallSnapshot> calls = this.actual.toolCalls().stream()
                .filter(call -> toolName.equals(call.toolName()))
                .toList();
        if (calls.isEmpty()) {
            failWithMessage("Expected a recorded call for tool <%s>", toolName);
        }
        return calls;
    }

    private List<String> requiredValues(String[] values, String name) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return Arrays.stream(values)
                .map(value -> {
                    if (value == null || value.isEmpty()) {
                        throw new IllegalArgumentException(name + " must not contain null or empty values");
                    }
                    return value;
                })
                .toList();
    }

    private Pattern opaqueTokenPattern(String entityType) {
        return OpaquePiiTokenFormat.patternForEntityType(entityType);
    }

    private void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
    }
}

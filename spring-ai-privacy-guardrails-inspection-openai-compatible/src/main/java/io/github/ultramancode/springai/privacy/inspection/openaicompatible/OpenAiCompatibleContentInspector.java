package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.JsonValue;
import com.openai.core.LogLevel;
import com.openai.core.ObjectMappers;
import com.openai.core.RequestOptions;
import com.openai.core.http.HttpResponseFor;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionIdentifiers;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol.GuardModelProtocol;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Inspects text through an OpenAI-compatible API.
 * Guard requests do not pass through the application's ChatClient advisors.
 */
public final class OpenAiCompatibleContentInspector implements ContentInspector {

    private final OpenAiCompatibleInspectionConfig config;
    private final String inspectorId;
    private final GuardModelProtocol protocol;
    private final ObjectReader responseReader;
    private final OpenAIClientAsync client;

    /** Creates an inspector with an explicit instance ID, transport settings and model protocol. */
    @SuppressWarnings("deprecation")
    public OpenAiCompatibleContentInspector(
            String inspectorId, OpenAiCompatibleInspectionConfig config, GuardModelProtocol protocol) {
        this.inspectorId = InspectionIdentifiers.requireValid(inspectorId);
        this.config = Objects.requireNonNull(config, "config");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        JsonMapper jsonMapper = ObjectMappers.jsonMapper().rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        // Configure the copied factory to retain the SDK's mapper settings.
        jsonMapper.getFactory().setInputDecorator(new ResponseBodyLimit(config.maxResponseBytes()));
        // Check trailing tokens only at the root because SDK deserializers read nested values.
        this.responseReader = jsonMapper.readerFor(ChatCompletion.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        String apiKey = config.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "not-required";
        }
        this.client = OpenAIOkHttpClientAsync.builder()
                .baseUrl(config.baseUrl().toString())
                .apiKey(apiKey)
                .jsonMapper(jsonMapper)
                .timeout(config.requestTimeout())
                .followRedirects(false)
                .maxRetries(0)
                .logLevel(LogLevel.OFF)
                .build();
    }

    @Override
    public String inspectorId() {
        return inspectorId;
    }

    @Override
    public boolean requiresPrivacyProcessedContent() {
        return config.requirePrivacyProcessedContent();
    }

    @Override
    public InspectionResult inspect(InspectionRequest request) {
        if (requiresPrivacyProcessedContent()) {
            request.requirePrivacyProcessed();
        }
        Set<String> completedSegmentIds = new HashSet<>();
        List<InspectionFinding> findings = new ArrayList<>();
        try {
            for (ContentSegment segment : request.segments()) {
                request.checkActive();
                String output = requestModelOutput(request, segment.text());
                Optional<InspectionFinding> finding = protocol.parse(segment.id(), output);
                if (finding.isPresent()) {
                    findings.add(finding.get());
                }
                completedSegmentIds.add(segment.id());
            }
            return InspectionResult.completed(completedSegmentIds, findings);
        } catch (InspectionException ex) {
            return InspectionResult.failed(ex.failureCode(), completedSegmentIds, findings);
        } catch (RuntimeException ex) {
            return InspectionResult.failed(InspectionFailureCode.INVALID_RESULT, completedSegmentIds, findings);
        }
    }

    private String requestModelOutput(InspectionRequest request, String text) {
        ChatCompletionCreateParams protocolParams = Objects.requireNonNull(
                protocol.request(config.model(), text), "protocol request");
        if (protocolParams.n().orElse(1L) != 1L) {
            throw new IllegalArgumentException("Guard requests must use n=1");
        }
        // Verdict parsing requires the complete model output.
        ChatCompletionCreateParams requestParams = protocolParams.toBuilder()
                .putAdditionalBodyProperty("stream", JsonValue.from(false))
                .build();

        Duration httpTimeout = config.requestTimeout();
        Duration remainingInspectionTime = request.remaining();
        if (remainingInspectionTime.compareTo(httpTimeout) < 0) {
            httpTimeout = remainingInspectionTime;
        }
        RequestOptions requestOptions = RequestOptions.builder().timeout(httpTimeout).build();

        AtomicBoolean responseNoLongerNeeded = new AtomicBoolean();
        CompletableFuture<HttpResponseFor<ChatCompletion>> responseFuture =
                client.chat().completions().withRawResponse().create(requestParams, requestOptions);
        CompletableFuture<String> modelOutputFuture = responseFuture.thenApplyAsync(
                response -> readModelOutput(response, responseNoLongerNeeded));
        try {
            // Account for time spent starting the request.
            return modelOutputFuture.get(request.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InspectionException(InspectionFailureCode.CANCELLED);
        } catch (TimeoutException ex) {
            throw new InspectionException(InspectionFailureCode.TIMEOUT);
        } catch (ExecutionException ex) {
            throw responseFailure(ex.getCause());
        } finally {
            responseNoLongerNeeded.set(true);
        }
    }

    private String readModelOutput(
            HttpResponseFor<ChatCompletion> response,
            AtomicBoolean responseNoLongerNeeded) {
        // Only the reader closes the response. The SDK timeout bounds an in-progress read.
        try (response) {
            if (responseNoLongerNeeded.get()) {
                throw new CancellationException("Response is no longer needed");
            }
            if (response.statusCode() != 200) {
                throw new InspectionException(InspectionFailureCode.HTTP_ERROR);
            }
            return extractModelOutput(responseReader.readValue(response.body()));
        } catch (IOException ex) {
            throw responseFailure(ex);
        }
    }

    private InspectionException responseFailure(Throwable failure) {
        InspectionFailureCode code = InspectionFailureCode.INVALID_RESULT;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InspectionException inspectionFailure) {
                return new InspectionException(inspectionFailure.failureCode());
            }
            if (cause instanceof InterruptedIOException) {
                return new InspectionException(InspectionFailureCode.TIMEOUT);
            }
            if (cause instanceof OpenAIServiceException) {
                return new InspectionException(InspectionFailureCode.HTTP_ERROR);
            }
            if (cause instanceof OpenAIInvalidDataException || cause instanceof JsonProcessingException) {
                code = InspectionFailureCode.INVALID_RESPONSE;
            } else if (cause instanceof OpenAIIoException || cause instanceof IOException) {
                code = InspectionFailureCode.TRANSPORT_ERROR;
            }
        }
        return new InspectionException(code);
    }

    @SuppressWarnings("deprecation")
    private String extractModelOutput(ChatCompletion completion) {
        if (completion == null) {
            throw invalidResponse();
        }
        List<ChatCompletion.Choice> choices = completion.choices();
        if (choices.size() != 1 || choices.get(0) == null) {
            throw invalidResponse();
        }
        ChatCompletionMessage message = choices.get(0).message();
        if (!message._role().equals(JsonValue.from("assistant"))) {
            throw invalidResponse();
        }
        // Reject legacy function_call responses as well as tool_calls.
        if (!message.toolCalls().orElse(List.of()).isEmpty() || message.functionCall().isPresent()) {
            throw invalidResponse();
        }
        if (!message.refusal().orElse("").isEmpty()) {
            throw invalidResponse();
        }
        return message.content().orElseThrow(OpenAiCompatibleContentInspector::invalidResponse);
    }

    private static InspectionException invalidResponse() {
        return new InspectionException(InspectionFailureCode.INVALID_RESPONSE);
    }
}

package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Dedicated non-streaming HTTP client, outside the protected business ChatClient's advisor chain. */
public final class OpenAiCompatibleContentInspector implements ContentInspector {

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
    private final OpenAiCompatibleInspectionConfig config;
    private final String inspectorId;
    private final GuardModelProtocol protocol;
    private final HttpClient client;

    private OpenAiCompatibleContentInspector(
            String inspectorId, OpenAiCompatibleInspectionConfig config, GuardModelProtocol protocol) {
        this.inspectorId = ContentSegment.requireIdentifier(inspectorId);
        this.config = Objects.requireNonNull(config, "config");
        this.protocol = protocol;
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(config.requestTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public static OpenAiCompatibleContentInspector kanana(OpenAiCompatibleInspectionConfig config) {
        return kanana("kanana-prompt", config);
    }

    /** Configures a distinct inspector instance, independent of the model or endpoint name. */
    public static OpenAiCompatibleContentInspector kanana(
            String inspectorId, OpenAiCompatibleInspectionConfig config) {
        return new OpenAiCompatibleContentInspector(inspectorId, config, new KananaPromptProtocol());
    }

    /** Explicit strict JSON inspection protocol for instruction models. */
    public static OpenAiCompatibleContentInspector jsonGuard(
            OpenAiCompatibleInspectionConfig config) {
        return jsonGuard("json-guard", config);
    }

    /** Configures a distinct inspector instance using the strict JSON protocol. */
    public static OpenAiCompatibleContentInspector jsonGuard(
            String inspectorId, OpenAiCompatibleInspectionConfig config) {
        return new OpenAiCompatibleContentInspector(inspectorId, config, new JsonGuardProtocol());
    }

    @Override
    public String inspectorId() {
        return inspectorId;
    }

    @Override
    public boolean requiresPrivacyProcessedContent() {
        return !config.allowUnprocessedContent();
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
                String output = requestClassification(request, segment.text());
                InspectionFinding finding = protocol.parse(segment.id(), output);
                if (finding != null) {
                    findings.add(finding);
                }
                completedSegmentIds.add(segment.id());
            }
            request.checkActive();
            return InspectionResult.completed(completedSegmentIds, findings);
        } catch (InspectionException ex) {
            return InspectionResult.failed(ex.failure(), completedSegmentIds, findings);
        } catch (RuntimeException ex) {
            return InspectionResult.failed(Thread.currentThread().isInterrupted()
                    ? InspectionFailureCode.CANCELLED : InspectionFailureCode.INVALID_RESPONSE,
                    completedSegmentIds, findings);
        }
    }

    private String requestClassification(InspectionRequest request, String text) {
        Duration remainingTime = request.remaining();
        Duration timeout =
                remainingTime.compareTo(config.requestTimeout()) < 0
                        ? remainingTime
                        : config.requestTimeout();
        HttpRequest httpRequest = buildRequest(text, timeout);
        HttpResponse<byte[]> response = sendRequest(httpRequest, request, timeout);
        return extractClassificationOutput(response);
    }

    private HttpRequest buildRequest(String text, Duration timeout) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(config.endpoint())
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        JSON.writeValueAsString(
                                                protocol.request(config.model(), text))));
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }
        return builder.build();
    }

    private HttpResponse<byte[]> sendRequest(
            HttpRequest httpRequest, InspectionRequest request, Duration timeout) {
        CompletableFuture<HttpResponse<byte[]>> pending =
                client.sendAsync(
                        httpRequest,
                        ignored -> new BoundedBodySubscriber(config.maxResponseBytes()));
        try {
            return pending.get(
                    Math.min(timeout.toNanos(), request.remaining().toNanos()),
                    TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new InspectionException(InspectionFailureCode.CANCELLED);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new InspectionException(InspectionFailureCode.TIMEOUT);
        } catch (ExecutionException ex) {
            pending.cancel(true);
            Throwable cause = ex.getCause();
            for (int i = 0; cause != null && i < 16; i++, cause = cause.getCause()) {
                if (cause instanceof InspectionException failure) {
                    throw new InspectionException(failure.failure());
                }
                if (cause instanceof HttpTimeoutException) {
                    throw new InspectionException(InspectionFailureCode.TIMEOUT);
                }
            }
            throw new InspectionException(InspectionFailureCode.TRANSPORT_ERROR);
        } catch (RuntimeException ex) {
            pending.cancel(true);
            throw ex;
        }
    }

    private String extractClassificationOutput(HttpResponse<byte[]> response) {
        if (response.statusCode() != 200) {
            throw new InspectionException(InspectionFailureCode.HTTP_ERROR);
        }
        JsonNode root = JSON.readTree(response.body());
        JsonNode choices = root == null ? null : root.get("choices");
        if (choices == null || !choices.isArray() || choices.size() != 1) {
            throw invalidResponse();
        }
        JsonNode choice = choices.get(0);
        String finishReason = choice.path("finish_reason").asString("");
        if (!"stop".equals(finishReason)
                && !(protocol.acceptsLengthFinish() && "length".equals(finishReason))) {
            throw invalidResponse();
        }
        JsonNode message = choice.path("message");
        if (!"assistant".equals(message.path("role").asString(""))
                || !message.path("content").isString()) {
            throw invalidResponse();
        }
        JsonNode toolCalls = message.path("tool_calls");
        if (message.hasNonNull("tool_calls") && (!toolCalls.isArray() || !toolCalls.isEmpty())) {
            throw invalidResponse();
        }
        if (message.hasNonNull("function_call") || message.hasNonNull("refusal")) {
            throw invalidResponse();
        }
        return message.path("content").asString();
    }

    private static InspectionException invalidResponse() {
        return new InspectionException(InspectionFailureCode.INVALID_RESPONSE);
    }
}

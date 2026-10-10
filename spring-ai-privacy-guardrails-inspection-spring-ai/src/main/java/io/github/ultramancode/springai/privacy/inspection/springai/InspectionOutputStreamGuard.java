package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.OutputResponseContext;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;

import java.util.List;

/** Enforces frame and text-length limits across a streamed tool loop. */
final class InspectionOutputStreamGuard implements StreamAdvisor {
    // ToolCallLimitExceededException is unavailable in the supported Spring AI 2.0.0 baseline.
    private static final String TOOL_CALL_LIMIT_EXCEEDED = "toolCallLimitExceeded";

    private final int toolAdvisorOrder;
    private final InspectionLimits limits;
    private final int maxFrames;
    private final InspectionEnforcement enforcement;
    private int modelFrameCount;
    private int outputFrameCount;
    private int characters;

    private InspectionOutputStreamGuard(int toolAdvisorOrder, InspectionLimits limits, int maxFrames,
            InspectionEnforcement enforcement) {
        this.toolAdvisorOrder = toolAdvisorOrder;
        this.limits = limits;
        this.maxFrames = maxFrames;
        this.enforcement = enforcement;
    }

    static StreamAdvisorChain prepare(StreamAdvisorChain chain, InspectionOutputAdvisor outputAdvisor,
            InspectionLimits limits, int maxFrames, InspectionEnforcement enforcement) {
        List<StreamAdvisor> toolAdvisors = chain.getStreamAdvisors().stream()
                .filter(ToolAdvisor.class::isInstance)
                .toList();
        if (toolAdvisors.isEmpty()) {
            return chain;
        }
        if (!(chain instanceof BaseAdvisorChain baseChain)) {
            throw new IllegalStateException("Output stream limits require a configurable advisor chain");
        }
        if (toolAdvisors.size() != 1) {
            throw new IllegalStateException("Output stream limits require exactly one tool advisor");
        }

        StreamAdvisor toolAdvisor = toolAdvisors.get(0);
        int toolAdvisorOrder = toolAdvisor.getOrder();
        if (toolAdvisorOrder == Integer.MIN_VALUE || toolAdvisorOrder == Integer.MAX_VALUE) {
            throw new IllegalStateException("Tool advisor order must allow advisors before and after it");
        }

        InspectionOutputStreamGuard outputStreamGuard =
                new InspectionOutputStreamGuard(toolAdvisorOrder, limits, maxFrames, enforcement);
        // Use distinct orders because Spring AI chain copies can reverse equal-order advisors.
        StreamAdvisor modelFrameLimitAdvisor = outputStreamGuard.modelFrameLimitAdvisor();
        StreamAdvisorChain preparedChain = baseChain.mutate()
                .pushAll(List.of(outputStreamGuard, modelFrameLimitAdvisor))
                .build()
                .copy((StreamAdvisor) outputAdvisor);
        List<StreamAdvisor> orderedStreamAdvisors = preparedChain.getStreamAdvisors();
        int toolAdvisorIndex = orderedStreamAdvisors.indexOf(toolAdvisor);
        if (orderedStreamAdvisors.indexOf(outputStreamGuard) >= toolAdvisorIndex
                || orderedStreamAdvisors.indexOf(modelFrameLimitAdvisor) <= toolAdvisorIndex) {
            throw new IllegalStateException("Output stream limits must surround the tool advisor");
        }
        return preparedChain;
    }

    private StreamAdvisor modelFrameLimitAdvisor() {
        return new StreamAdvisor() {
            public String getName() {
                return "InspectionModelFrameLimit";
            }

            public int getOrder() {
                return toolAdvisorOrder + 1;
            }

            public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
                return chain.nextStream(request).doOnNext(response -> {
                    // Count even tool-call frames that ToolCallingAdvisor filters out.
                    if (modelFrameCount >= maxFrames) {
                        throw enforcement.failure(new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED));
                    }
                    modelFrameCount++;
                });
            }
        };
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request).map(response -> {
            try {
                checkLimits(response);
            } catch (RuntimeException ex) {
                throw enforcement.failure(ex);
            }
            ChatResponse chatResponse = response.chatResponse();
            if (chatResponse == null) {
                return response;
            }
            for (Generation generation : chatResponse.getResults()) {
                String finishReason = generation.getMetadata().getFinishReason();
                boolean directToolResult = ToolExecutionResult.FINISH_REASON.equals(finishReason);
                boolean toolCallLimitExceeded = TOOL_CALL_LIMIT_EXCEEDED.equals(finishReason);
                if (directToolResult || toolCallLimitExceeded) {
                    return OutputResponseContext.markCompleteToolResponse(response);
                }
            }
            return response;
        });
    }

    private void checkLimits(ChatClientResponse response) {
        if (response.chatResponse() != null) {
            for (Generation generation : response.chatResponse().getResults()) {
                String text = generation.getOutput().getText();
                if (text != null) {
                    if (text.length() > limits.maxCharacters() - characters) {
                        throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
                    }
                    characters += text.length();
                }
            }
        }
        if (outputFrameCount >= maxFrames) {
            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
        }
        outputFrameCount++;
    }

    @Override
    public String getName() {
        return "InspectionOutputStreamGuard";
    }

    @Override
    public int getOrder() {
        return toolAdvisorOrder - 1;
    }
}

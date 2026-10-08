package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/** Subscription-local filtering at the tool-loop boundary, before outer Privacy transformations. */
final class InspectionFinalOutputStream implements StreamAdvisor {
    // Spring AI 2.0.1 introduced ToolCallLimitExceededException and this finish reason.
    // Use the string value because referencing that class would prevent compilation against 2.0.0.
    // This response ends the tool-call loop without another model round, so discard buffered intermediate text.
    private static final String TOOL_CALL_LIMIT_EXCEEDED_FINISH_REASON = "toolCallLimitExceeded";

    private final int order;
    private final InspectionLimits limits;
    private final int maxFrames;
    private final InspectionEnforcement enforcement;
    private final List<ChatClientResponse> frames = new ArrayList<>();
    private int frameCount;
    private int characters;
    private boolean direct;

    private InspectionFinalOutputStream(int order, InspectionLimits limits, int maxFrames,
            InspectionEnforcement enforcement) {
        this.order = order;
        this.limits = limits;
        this.maxFrames = maxFrames;
        this.enforcement = enforcement;
    }

    static StreamAdvisorChain prepare(StreamAdvisorChain chain, InspectionOutputAdvisor outer,
            InspectionLimits limits, int maxFrames, InspectionEnforcement enforcement) {
        var tools = chain.getStreamAdvisors().stream().filter(ToolAdvisor.class::isInstance).toList();
        if (tools.isEmpty()) {
            return chain;
        }
        if (!(chain instanceof BaseAdvisorChain mutable) || tools.size() != 1
                || tools.get(0).getOrder() == Integer.MIN_VALUE
                || tools.get(0).getOrder() == Integer.MAX_VALUE) {
            throw new IllegalStateException("Final output streaming requires one tool advisor before the model boundary");
        }
        var filter = new InspectionFinalOutputStream(tools.get(0).getOrder(), limits, maxFrames, enforcement);
        // Strict orders survive Spring AI's chain copies, which can reverse equal-order
        // advisors. Keep filtering outside the loop and mark every model round inside it.
        StreamAdvisor marker = filter.roundMarker();
        StreamAdvisorChain selected = mutable.mutate().pushAll(List.of(filter, marker)).build()
                .copy((StreamAdvisor) outer);
        var ordered = selected.getStreamAdvisors();
        int toolIndex = ordered.indexOf(tools.get(0));
        if (ordered.indexOf(filter) >= toolIndex || ordered.indexOf(marker) <= toolIndex) {
            throw new IllegalStateException("Final output stream boundaries must surround the tool advisor");
        }
        return selected;
    }

    private StreamAdvisor roundMarker() {
        return new StreamAdvisor() {
            public String getName() {
                return "InspectionOutputRoundBoundary";
            }

            public int getOrder() {
                return order + 1;
            }

            public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
                return Flux.defer(() -> {
                    frames.clear();
                    characters = 0;
                    direct = false;
                    return chain.nextStream(request).doOnNext(response -> {
                        // Count even tool-call frames that ToolCallingAdvisor filters out.
                        if (frameCount >= maxFrames) {
                            throw enforcement.failure(new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED));
                        }
                        frameCount++;
                    });
                });
            }
        };
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request).doOnNext(response -> {
            try {
                accept(response);
            } catch (RuntimeException ex) {
                throw enforcement.failure(ex);
            }
        }).thenMany(Flux.defer(() -> Flux.fromIterable(frames)));
    }

    private void accept(ChatClientResponse response) {
        if (response.chatResponse() != null) {
            boolean terminalToolResult = response.chatResponse().getResults().stream().anyMatch(generation ->
                    ToolExecutionResult.FINISH_REASON.equals(generation.getMetadata().getFinishReason())
                            || TOOL_CALL_LIMIT_EXCEEDED_FINISH_REASON.equals(generation.getMetadata().getFinishReason()));
            if (terminalToolResult && !direct) {
                frames.clear();
                characters = 0;
                direct = true;
            }
            for (var generation : response.chatResponse().getResults()) {
                String text = generation.getOutput().getText();
                if (text != null) {
                    if (text.length() > limits.maxCharacters() - characters) {
                        throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
                    }
                    characters += text.length();
                }
            }
        }
        if (frames.size() >= maxFrames) {
            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
        }
        frames.add(response);
    }

    @Override
    public String getName() {
        return "InspectionFinalOutputStream";
    }

    @Override
    public int getOrder() {
        return order - 1;
    }
}

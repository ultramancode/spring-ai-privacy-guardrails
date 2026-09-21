package io.github.ultramancode.springai.privacy.boundary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ChatModelCallAdvisor;
import org.springframework.ai.chat.client.advisor.ChatModelStreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Executes the selected client's fixed phase plan before every model call, including tool loops. */
final class ModelRequestBoundaryAdvisor implements CallAdvisor, StreamAdvisor {
    static final int DEFAULT_ORDER = ModelRequestBoundarySpec.DEFAULT_ORDER;
    private static final Logger logger = LoggerFactory.getLogger(ModelRequestBoundaryAdvisor.class);
    private final ModelRequestBoundaryPlan plan;

    ModelRequestBoundaryAdvisor(ModelRequestBoundaryPlan plan) {
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    boolean containsStage(Object stage) {
        return plan.containsStage(stage);
    }

    boolean containsStageType(Class<?> type) {
        return plan.containsStageType(type);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        validate(chain.getCallAdvisors(), true);
        ChatClientRequest prepared = plan.prepare(request);
        checkInterrupted();
        return chain.nextCall(prepared);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (!plan.hasInspection()) {
            return Flux.defer(() -> {
                validate(chain.getStreamAdvisors(), true);
                return chain.nextStream(plan.prepare(request));
            });
        }
        // Blocking inspectors run per subscription and are interrupted on cancellation.
        return Mono.fromCallable(() -> {
                    validate(chain.getStreamAdvisors(), true);
                    return plan.prepare(request);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(prepared -> {
                    checkInterrupted();
                    return chain.nextStream(prepared);
                });
    }

    void validate(List<? extends Advisor> advisors, boolean logAllowedAdvisors) {
        int boundaryIndex = advisors.indexOf(this);
        long boundaryCount = advisors.stream().filter(ModelRequestBoundaryAdvisor.class::isInstance).count();
        if (boundaryIndex < 0 || boundaryCount != 1) {
            throw new IllegalStateException("Exactly one managed model request boundary is required");
        }
        long modelAdvisorCount = advisors.stream().filter(ModelRequestBoundaryAdvisor::isModelAdvisor).count();
        Advisor lastAdvisor = advisors.get(advisors.size() - 1);
        if (plan.hasInspection() && (modelAdvisorCount != 1 || boundaryIndex == advisors.size() - 1
                || !isModelAdvisor(lastAdvisor))) {
            throw new IllegalStateException("The model request boundary requires one terminal model advisor");
        }
        validateAdvisorsAfterBoundary(advisors, boundaryIndex, logAllowedAdvisors);
    }

    private void validateAdvisorsAfterBoundary(List<? extends Advisor> advisors, int boundaryIndex,
            boolean logAllowedAdvisors) {
        List<String> downstreamAdvisorDescriptions = new ArrayList<>();
        for (int i = boundaryIndex + 1; i < advisors.size(); i++) {
            Advisor advisor = advisors.get(i);
            if (advisor instanceof ToolAdvisor) {
                throw new IllegalStateException("Tool advisors must run before the model request boundary");
            }
            if (!isModelAdvisor(advisor)) {
                downstreamAdvisorDescriptions.add(advisor.getName() + "(order=" + advisor.getOrder() + ")");
            }
        }
        if (plan.hasInspection() && !downstreamAdvisorDescriptions.isEmpty()) {
            if (!plan.allowsAdvisorsAfterBoundary()) {
                throw new IllegalStateException("Advisors after inspection require explicit opt-in: " + downstreamAdvisorDescriptions);
            }
            if (logAllowedAdvisors) {
                logger.warn("Advisors after the model request boundary were explicitly allowed: {}. "
                        + "Their changes are outside the final-inspection guarantee.", downstreamAdvisorDescriptions);
            }
        }
    }

    private static boolean isModelAdvisor(Advisor advisor) {
        return advisor.getClass() == ChatModelCallAdvisor.class || advisor.getClass() == ChatModelStreamAdvisor.class;
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Model request boundary interrupted");
        }
    }

    @Override
    public String getName() {
        return "ModelRequestBoundaryAdvisor";
    }

    @Override
    public int getOrder() {
        return DEFAULT_ORDER;
    }
}

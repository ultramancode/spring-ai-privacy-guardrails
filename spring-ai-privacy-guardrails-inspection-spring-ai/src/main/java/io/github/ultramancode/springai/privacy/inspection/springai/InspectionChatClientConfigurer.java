package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailure;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Explicit, client-scoped integration. Distinct terminal orders keep privacy,
 * authorization and inspection in sequence, even in copied tool-loop chains.
 * Actual placement is checked on every request. Use the factories' terminalConfigurer overloads.
 * Put application and observability advisors before the final inspection advisor.
 * No shared ChatModel or global ChatClient builder is modified.
 */
public final class InspectionChatClientConfigurer implements UnaryOperator<ChatClient.Builder> {

    private final InspectionService service;
    private final InspectionLimits limits;
    private final ContentRepresentationResolver representationResolver;
    private final Consumer<InspectionReport> observer;
    private final WeakHashMap<ChatClient.Builder, Boolean> configuredBuilders = new WeakHashMap<>();

    public InspectionChatClientConfigurer(InspectionService service) {
        this(
                service,
                InspectionLimits.defaults(),
                ContentRepresentationResolver.asReceived(),
                ignored -> {});
    }

    public InspectionChatClientConfigurer(
            InspectionService service,
            InspectionLimits limits,
            ContentRepresentationResolver representationResolver,
            Consumer<InspectionReport> observer) {
        this.service = Objects.requireNonNull(service, "service");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.representationResolver = Objects.requireNonNull(representationResolver, "representationResolver");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public ChatClient.Builder configure(ChatClient.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        synchronized (configuredBuilders) {
            if (configuredBuilders.containsKey(builder)) {
                throw new IllegalStateException("Builder already configured for inspection");
            }
            ContentInspectionAdvisor inspectionAdvisor =
                    new ContentInspectionAdvisor(service, limits, representationResolver, observer);
            builder.defaultAdvisors(new ChainValidator(inspectionAdvisor), inspectionAdvisor);
            configuredBuilders.put(builder, true);
            return builder;
        }
    }

    @Override
    public ChatClient.Builder apply(ChatClient.Builder builder) {
        return configure(builder);
    }

    private static final class ChainValidator
            implements CallAdvisor, StreamAdvisor, PriorityOrdered {
        private final ContentInspectionAdvisor inspectionAdvisor;

        private ChainValidator(ContentInspectionAdvisor inspectionAdvisor) {
            this.inspectionAdvisor = inspectionAdvisor;
        }

        private void validate(List<? extends Advisor> advisors) {
            int index = advisors.indexOf(inspectionAdvisor);
            long inspectionAdvisorCount =
                    advisors.stream().filter(a -> a instanceof ContentInspectionAdvisor).count();
            if (index < 0 || inspectionAdvisorCount != 1) {
                throw new InspectionException(InspectionFailure.CONFIGURATION);
            }
            ContentInspectionAdvisor.validateModelOnlyTail(
                    advisors.subList(index + 1, advisors.size()));
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            validate(chain.getCallAdvisors());
            return chain.nextCall(request);
        }

        @Override
        public Flux<ChatClientResponse> adviseStream(
                ChatClientRequest request, StreamAdvisorChain chain) {
            return Flux.defer(
                    () -> {
                        validate(chain.getStreamAdvisors());
                        return chain.nextStream(request);
                    });
        }

        @Override
        public String getName() {
            return "InspectionAdvisorChainValidator";
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}

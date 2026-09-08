package io.github.ultramancode.springai.privacy.autoconfigure;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyInputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyModelBoundaryAdvisor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

/**
 * Applies the starter-managed privacy boundary to a {@link ChatClient.Builder}
 * explicitly selected by the application.
 *
 * <p>{@link #configure(ChatClient.Builder)} uses {@link ToolCallingAdvisor#DEFAULT_ORDER}.
 * {@link #forToolCallingAdvisorOrder(int)} positions the complete boundary around
 * the supplied tool order and checks the actual request chain.
 * Both entry points include all mandatory privacy advisors.
 * Output protection is included only when {@code spring.ai.privacy.output.enabled=true}.</p>
 */
public final class PrivacyChatClientConfigurer
        implements UnaryOperator<ChatClient.Builder>, IntFunction<UnaryOperator<ChatClient.Builder>> {

    private final IntFunction<List<Advisor>> advisorFactory;
    private final WeakHashMap<ChatClient.Builder, Boolean> configuredBuilders = new WeakHashMap<>();

    PrivacyChatClientConfigurer(IntFunction<List<Advisor>> advisorFactory) {
        this.advisorFactory = Objects.requireNonNull(advisorFactory, "advisorFactory must not be null");
    }

    /**
     * Applies the complete privacy boundary using {@link ToolCallingAdvisor#DEFAULT_ORDER}
     * as the tool order for relative positioning and returns the same builder.
     *
     * @param builder ChatClient builder to configure for privacy protection
     * @return the supplied builder after the privacy advisors have been registered
     * @throws IllegalStateException when the same builder is configured more than once
     */
    public ChatClient.Builder configure(ChatClient.Builder builder) {
        return configure(builder, this.advisorFactory.apply(ToolCallingAdvisor.DEFAULT_ORDER));
    }

    /**
     * Prepares a complete privacy boundary for the given tool order. Applying the returned
     * configurer creates fresh advisors. The tool advisor and application advisors keep their orders.
     * The input and terminal model boundaries retain their default positions.
     * The final chain is checked for each call or stream subscription, including
     * advisors added to individual requests.
     *
     * @param toolOrder the planned order of the client's tool advisor
     * @return a configurer that validates the actual call and stream advisor layouts
     * @throws IllegalArgumentException when the relative boundaries cannot fit between input and model processing
     */
    public UnaryOperator<ChatClient.Builder> forToolCallingAdvisorOrder(int toolOrder) {
        // Required order from input to model (T = toolOrder, lower order values enter first):
        // PrivacyInputAdvisor < PrivacyOutputAdvisor(T-2, optional) < PrivacyToolContextAdvisor(T-1)
        //                     < ToolAdvisor(T) < PrivacyToolCallValidationAdvisor(T+1)
        //                     < PrivacyModelBoundaryAdvisor.
        // PrivacyOutputAdvisor is included only when spring.ai.privacy.output.enabled=true.
        // The other privacy advisors are mandatory.
        // ChatClient auto-registers a ToolAdvisor by default, even for requests without tools.
        // If it is absent, only requests without tools are allowed. T still determines the privacy layout.
        // ToolAdvisor examples include ToolCallingAdvisor and ToolSearchToolCallingAdvisor.
        // PrivacyOutputAdvisor prepares protection. PrivacyLifecycleAdvisor applies it to the returning response.
        // Reserve two positions between input and tool, so T >= input+3, even when output protection is disabled.
        // Reserve one position between tool and model, so T <= model-2.
        // Compute the bounds in long to avoid overflow if the boundary constants change.
        long minimumToolOrder = (long) PrivacyInputAdvisor.DEFAULT_ORDER + 3;
        long maximumToolOrder = (long) PrivacyModelBoundaryAdvisor.DEFAULT_ORDER - 2;
        if (toolOrder < minimumToolOrder || toolOrder > maximumToolOrder) {
            throw new IllegalArgumentException("Privacy tool advisor order must be between "
                    + minimumToolOrder + " and " + maximumToolOrder
                    + " to fit the input and model boundaries; received " + toolOrder);
        }
        return builder -> {
            List<Advisor> managedAdvisors = List.copyOf(this.advisorFactory.apply(toolOrder));
            List<Advisor> advisors = new ArrayList<>(managedAdvisors.size() + 1);
            advisors.add(new PrivacyAdvisorChainValidator(managedAdvisors, toolOrder));
            advisors.addAll(managedAdvisors);
            return configure(builder, List.copyOf(advisors));
        };
    }

    /**
     * Exposes {@link #forToolCallingAdvisorOrder(int)} through {@link IntFunction}
     * so optional integrations can use this configurer without referencing its concrete class.
     */
    @Override
    public UnaryOperator<ChatClient.Builder> apply(int toolOrder) {
        return forToolCallingAdvisorOrder(toolOrder);
    }

    private ChatClient.Builder configure(ChatClient.Builder builder, List<Advisor> advisors) {
        ChatClient.Builder selectedBuilder = Objects.requireNonNull(builder, "builder must not be null");
        synchronized (this.configuredBuilders) {
            if (this.configuredBuilders.containsKey(selectedBuilder)) {
                throw new IllegalStateException(
                        "PrivacyChatClientConfigurer cannot configure the same ChatClient.Builder more than once"
                );
            }
            selectedBuilder.defaultAdvisors(advisors);
            this.configuredBuilders.put(selectedBuilder, Boolean.TRUE);
        }
        return selectedBuilder;
    }

    /**
     * Applies the complete configured privacy boundary.
     *
     * @param builder ChatClient builder to configure for privacy protection
     * @return the supplied builder after the privacy advisors have been registered
     */
    @Override
    public ChatClient.Builder apply(ChatClient.Builder builder) {
        return configure(builder);
    }
}

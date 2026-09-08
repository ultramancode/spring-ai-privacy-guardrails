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
 * <p>The default entry point uses the standard tool order. The order-aware entry
 * point positions the complete boundary around a selected tool order and checks
 * the actual request chain. Neither entry point omits mandatory privacy advisors.
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
     * Applies the complete configured privacy boundary and returns the same builder.
     *
     * @param builder ChatClient builder to configure for privacy protection
     * @return the supplied builder after the privacy advisors have been registered
     * @throws IllegalStateException when the same builder is configured more than once
     */
    public ChatClient.Builder configure(ChatClient.Builder builder) {
        return configure(builder, this.advisorFactory.apply(ToolCallingAdvisor.DEFAULT_ORDER));
    }

    /**
     * Prepares a complete privacy boundary for the given tool order. Each application
     * creates fresh advisors; the tool advisor and application advisors keep their orders.
     * The input and terminal model boundaries retain their default positions.
     * The final chain is checked for each call or stream subscription, including
     * advisors added to individual requests.
     *
     * @param toolOrder the order of the tool advisor that will be registered on the client
     * @return a configurer that validates the actual call and stream advisor layouts
     * @throws IllegalArgumentException when the relative boundaries cannot fit between input and model processing
     */
    public UnaryOperator<ChatClient.Builder> forToolCallingAdvisorOrder(int toolOrder) {
        // Reserve output=T-2 and context=T-1 after input, and validation=T+1
        // before the model boundary. Check in long arithmetic before narrowing.
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

    /** JDK-only composition contract used by optional integrations. */
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

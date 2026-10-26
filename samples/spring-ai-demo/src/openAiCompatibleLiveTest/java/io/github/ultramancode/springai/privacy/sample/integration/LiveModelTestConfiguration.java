package io.github.ultramancode.springai.privacy.sample.integration;

import io.github.ultramancode.springai.privacy.chatclient.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.sample.config.DemoPiiRuleConfiguration;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(DemoPiiRuleConfiguration.class)
class LiveModelTestConfiguration {

    @Bean
    GuardrailsPolicy guardrailsPolicy() {
        ToolDisclosurePolicy toolDisclosurePolicy = ToolDisclosurePolicy.allowEntityTypesByToolName(
            Map.of("customerLookup", List.of("CUSTOMER_ID")));
        GuardrailsPolicy.Privacy privacyPolicy = GuardrailsPolicy.Privacy.builder()
            .toolDisclosurePolicy(toolDisclosurePolicy)
            .outputProtection().tokenize()
            .build();
        return GuardrailsPolicy.builder()
            .privacy(privacyPolicy)
            .build();
    }
}

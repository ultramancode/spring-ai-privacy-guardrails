package io.github.ultramancode.springai.privacy.sample.config;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Application-owned identifiers selected by the demo's rules.include setting. */
@Configuration(proxyBeanMethods = false)
public class DemoPiiRuleConfiguration {
    @Bean
    PiiRule employeeIdRule() {
        PatternDefinition employeeIdPattern = PatternDefinition.builder()
            .expression("(?<![A-Za-z0-9_])EMP-[0-9]{4}(?![A-Za-z0-9_])")
            .score(0.9)
            .build();
        return PatternPiiRule.builder()
            .id("employee-id")
            .entityType("EMPLOYEE_ID")
            .addPattern(employeeIdPattern)
            .build();
    }

    @Bean
    PiiRule customerIdRule() {
        PatternDefinition customerIdPattern = PatternDefinition.builder()
            .expression("(?<![A-Za-z0-9_])CUST-[0-9]{6}(?![A-Za-z0-9_])")
            .score(0.9)
            .build();
        return PatternPiiRule.builder()
            .id("customer-id")
            .entityType("CUSTOMER_ID")
            .addPattern(customerIdPattern)
            .build();
    }
}

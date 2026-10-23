package io.github.ultramancode.springai.privacy.sample.config;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Application-owned identifiers selected by the demo's rules.include setting. */
@Configuration(proxyBeanMethods = false)
public class DemoPiiRuleConfiguration {
    @Bean
    PiiRule employeeIdRule() {
        return new PatternPiiRule("employee-id", "EMPLOYEE_ID",
                "(?<![A-Za-z0-9_])EMP-[0-9]{4}(?![A-Za-z0-9_])", 0.9, 0);
    }

    @Bean
    PiiRule customerIdRule() {
        return new PatternPiiRule("customer-id", "CUSTOMER_ID",
                "(?<![A-Za-z0-9_])CUST-[0-9]{6}(?![A-Za-z0-9_])", 0.9, 0);
    }
}

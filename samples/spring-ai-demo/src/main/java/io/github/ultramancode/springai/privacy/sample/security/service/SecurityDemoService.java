package io.github.ultramancode.springai.privacy.sample.security.service;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.sample.localtool.service.LocalToolLoopDemoService;
import io.github.ultramancode.springai.privacy.sample.security.dto.response.SecurityDemoResponse;
import io.github.ultramancode.springai.privacy.sample.shared.model.CustomerDemoData;
import io.github.ultramancode.springai.privacy.sample.shared.model.DemoLanguage;
import io.github.ultramancode.springai.privacy.sample.shared.security.model.DemoRole;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolSecurityResult;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public final class SecurityDemoService {

    private final LocalToolLoopDemoService toolLoopService;
    private final PrivacyService privacyService;

    public SecurityDemoService(LocalToolLoopDemoService toolLoopService, PrivacyService privacyService) {
        this.toolLoopService = toolLoopService;
        this.privacyService = privacyService;
    }

    public SecurityDemoResponse runSecurityDemo(Locale locale) {
        DemoLanguage language = DemoLanguage.from(locale);
        String input = CustomerDemoData.getByLanguage(language).input();

        ToolSecurityResult generalEmployee = this.toolLoopService.executeToolLoopAsRole(
                input, language, DemoRole.GENERAL_EMPLOYEE);
        ToolSecurityResult customerSupport = this.toolLoopService.executeToolLoopAsRole(
                input, language, DemoRole.CUSTOMER_SUPPORT);

        return SecurityDemoResponse.from(
                generalEmployee, customerSupport, this.privacyService.activeSessionCount());
    }
}

package io.github.ultramancode.springai.privacy.sample.privacy.service;

import io.github.ultramancode.springai.privacy.core.PiiTokenizationResult;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import io.github.ultramancode.springai.privacy.sample.privacy.dto.response.ProtectedPromptResponse;
import io.github.ultramancode.springai.privacy.sample.shared.model.CustomerDemoData;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public final class PromptProtectionDemoService {

    private final PrivacyService privacyService;

    public PromptProtectionDemoService(PrivacyService privacyService) {
        this.privacyService = privacyService;
    }

    public ProtectedPromptResponse runPromptProtectionDemo(Locale locale) {
        return protect(CustomerDemoData.getByLocale(locale).input());
    }

    public ProtectedPromptResponse protect(String input) {
        try (PrivacySession session = this.privacyService.openSession()) {
            PiiTokenizationResult tokenization = this.privacyService.analyzeAndTokenize(session.handle(), input);
            return ProtectedPromptResponse.from(tokenization);
        }
    }
}

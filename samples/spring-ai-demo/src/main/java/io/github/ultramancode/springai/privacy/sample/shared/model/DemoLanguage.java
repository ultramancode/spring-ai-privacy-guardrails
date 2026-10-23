package io.github.ultramancode.springai.privacy.sample.shared.model;

import java.util.Locale;

public enum DemoLanguage {
    EN,
    KO;

    public static DemoLanguage from(Locale locale) {
        if (locale == null) {
            return EN;
        }
        String language = locale.getLanguage();
        if (Locale.KOREAN.getLanguage().equals(language)) {
            return KO;
        }
        return EN;
    }
}

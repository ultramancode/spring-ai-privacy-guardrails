package io.github.ultramancode.privacy.buildlogic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.gradle.api.Action;

public final class PrivacyBuildExtension {

    private final Map<String, PrivacyModuleSpec> modules = new LinkedHashMap<>();

    private final Map<String, LocalizedDocument> localizedDocuments = new LinkedHashMap<>();

    private final List<Action<? super PrivacyModuleSpec>> moduleListeners = new ArrayList<>();

    private final List<Action<? super LocalizedDocument>> localizationListeners = new ArrayList<>();

    public void module(String name, Action<? super PrivacyModuleSpec> configuration) {
        if (this.modules.containsKey(name)) {
            throw new IllegalStateException("Duplicate privacy module declaration: " + name);
        }
        PrivacyModuleSpec spec = new PrivacyModuleSpec(name);
        configuration.execute(spec);
        spec.validate();
        this.modules.put(name, spec);
        this.moduleListeners.forEach(listener -> listener.execute(spec));
    }

    public void localizedDocument(String sourcePath, String targetPath) {
        if (this.localizedDocuments.containsKey(sourcePath)) {
            throw new IllegalStateException("Duplicate localization source: " + sourcePath);
        }
        LocalizedDocument document = new LocalizedDocument(sourcePath, targetPath);
        this.localizedDocuments.put(sourcePath, document);
        this.localizationListeners.forEach(listener -> listener.execute(document));
    }

    public void whenModuleDeclared(Action<? super PrivacyModuleSpec> listener) {
        this.modules.values().forEach(listener::execute);
        this.moduleListeners.add(listener);
    }

    public void whenLocalizedDocumentDeclared(Action<? super LocalizedDocument> listener) {
        this.localizedDocuments.values().forEach(listener::execute);
        this.localizationListeners.add(listener);
    }
}

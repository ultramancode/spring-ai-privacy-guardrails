package io.github.ultramancode.privacy.buildlogic;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class PrivacyModuleSpec {

    private final String name;

    private final Set<String> allowedProjectDependencies = new LinkedHashSet<>();

    private final Set<String> forbiddenExternalGroupPrefixes = new LinkedHashSet<>();

    private String automaticModuleName;

    private String publicationDisplayName;

    private boolean denyAllExternalMainDependencies;

    private boolean springIndependentPublication;

    PrivacyModuleSpec(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9-]*")) {
            throw new IllegalArgumentException("Invalid Gradle module name: " + name);
        }
        this.name = name;
    }

    public void publication(String automaticModuleName, String displayName) {
        if (automaticModuleName == null
                || !automaticModuleName.matches("[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)+")) {
            throw new IllegalArgumentException(
                    "Invalid Automatic-Module-Name for " + this.name + ": " + automaticModuleName
            );
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException(
                    "Publication display name must not be blank for " + this.name
            );
        }
        this.automaticModuleName = automaticModuleName;
        this.publicationDisplayName = displayName;
    }

    public void dependsOnProject(String... moduleNames) {
        Objects.requireNonNull(moduleNames, "moduleNames");
        Arrays.stream(moduleNames).forEach(moduleName -> {
            if (moduleName == null || !moduleName.matches("[A-Za-z0-9][A-Za-z0-9-]*")) {
                throw new IllegalArgumentException(
                        "Invalid allowed dependency for " + this.name + ": " + moduleName
                );
            }
            this.allowedProjectDependencies.add(":" + moduleName);
        });
    }

    public void denyAllExternalMainDependencies() {
        this.denyAllExternalMainDependencies = true;
    }

    public void denyExternalGroup(String... groupPrefixes) {
        Objects.requireNonNull(groupPrefixes, "groupPrefixes");
        Arrays.stream(groupPrefixes).forEach(prefix -> {
            if (prefix == null || prefix.isBlank()) {
                throw new IllegalArgumentException(
                        "External dependency group prefix must not be blank for " + this.name
                );
            }
            this.forbiddenExternalGroupPrefixes.add(prefix);
        });
    }

    public void springIndependentPublication() {
        this.springIndependentPublication = true;
    }

    void validate() {
        if (this.springIndependentPublication && !isPublishable()) {
            throw new IllegalStateException(
                    this.name + " cannot verify a publication without declaring publication(...)"
            );
        }
        if (this.allowedProjectDependencies.contains(getPath())) {
            throw new IllegalStateException(this.name + " cannot depend on itself");
        }
    }

    public String getName() {
        return this.name;
    }

    public String getPath() {
        return ":" + this.name;
    }

    public boolean isPublishable() {
        return this.automaticModuleName != null;
    }

    public String getAutomaticModuleName() {
        return this.automaticModuleName;
    }

    public String getPublicationDisplayName() {
        return this.publicationDisplayName;
    }

    public Set<String> getAllowedProjectDependencies() {
        return Set.copyOf(this.allowedProjectDependencies);
    }

    public boolean isAllExternalMainDependenciesDenied() {
        return this.denyAllExternalMainDependencies;
    }

    public Set<String> getForbiddenExternalGroupPrefixes() {
        return Set.copyOf(this.forbiddenExternalGroupPrefixes);
    }

    public boolean isSpringIndependentPublication() {
        return this.springIndependentPublication;
    }
}

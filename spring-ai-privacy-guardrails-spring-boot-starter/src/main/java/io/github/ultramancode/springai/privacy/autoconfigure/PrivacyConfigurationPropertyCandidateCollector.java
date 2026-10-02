package io.github.ultramancode.springai.privacy.autoconfigure;

import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.env.PropertySourceInfo;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Collects configuration-property names and the source context needed to diagnose them. */
final class PrivacyConfigurationPropertyCandidateCollector {

    private PrivacyConfigurationPropertyCandidateCollector() {
    }

    static List<PropertyNameCandidate> collect(Environment environment) {
        List<PropertyNameCandidate> candidates = new ArrayList<>();
        collectFromEnvironment(environment, candidates);
        return List.copyOf(candidates);
    }

    /**
     * Walks raw property sources on a best-effort basis. If iteration fails, candidates
     * already collected from preceding sources are retained.
     */
    private static void collectFromEnvironment(
            Environment environment,
            List<PropertyNameCandidate> candidates
    ) {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return;
        }
        Iterator<PropertySource<?>> sources;
        try {
            sources = configurableEnvironment.getPropertySources().iterator();
        } catch (RuntimeException ignored) {
            return;
        }
        while (true) {
            PropertySource<?> source;
            try {
                if (!sources.hasNext()) {
                    return;
                }
                source = sources.next();
            } catch (RuntimeException ignored) {
                return;
            }
            adaptPropertySourceAndCollectCandidates(source, candidates);
        }
    }

    /**
     * Adapts one raw property source independently. An adapter failure skips only that
     * source, while non-iterable adapters are intentionally ignored.
     */
    private static void adaptPropertySourceAndCollectCandidates(
            PropertySource<?> propertySource,
            List<PropertyNameCandidate> candidates
    ) {
        Iterator<ConfigurationPropertySource> sources;
        try {
            sources = ConfigurationPropertySources.from(List.of(propertySource)).iterator();
        } catch (RuntimeException ignored) {
            return;
        }
        while (true) {
            ConfigurationPropertySource source;
            try {
                if (!sources.hasNext()) {
                    return;
                }
                source = sources.next();
            } catch (RuntimeException ignored) {
                return;
            }
            if (source instanceof IterableConfigurationPropertySource iterableSource) {
                candidates.addAll(collectCandidatesUsingSourceMapping(iterableSource));
            }
        }
    }

    /**
     * Uses the mapping semantics of one adapted source without exposing partial results.
     * Failures discard this source's candidates without affecting other sources.
     */
    private static List<PropertyNameCandidate> collectCandidatesUsingSourceMapping(
            IterableConfigurationPropertySource source
    ) {
        try {
            Object underlyingSource = source.getUnderlyingSource();
            if (!(underlyingSource instanceof SystemEnvironmentPropertySource propertySource)) {
                return collectDirectlyEnumeratedCandidates(source);
            }
            String name = propertySource.getName();
            String systemEnvironment =
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
            if (!name.equals(systemEnvironment)
                    && !name.endsWith("-" + systemEnvironment)) {
                return collectDirectlyEnumeratedCandidates(source);
            }
            return collectCandidatesFromSystemEnvironment(propertySource);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /** Pairs directly enumerated names with their already-adapted source. */
    private static List<PropertyNameCandidate> collectDirectlyEnumeratedCandidates(
            IterableConfigurationPropertySource source
    ) {
        List<ConfigurationPropertyName> names = enumeratePropertyNames(source);
        DiagnosticContext context = new DiagnosticContext(source, false);
        List<PropertyNameCandidate> candidates = new ArrayList<>(names.size());
        for (ConfigurationPropertyName name : names) {
            candidates.add(new PropertyNameCandidate(name, context));
        }
        return List.copyOf(candidates);
    }

    /** Propagates enumeration failures to the source boundary before publishing any candidates. */
    private static List<ConfigurationPropertyName> enumeratePropertyNames(
            IterableConfigurationPropertySource source
    ) {
        List<ConfigurationPropertyName> names = new ArrayList<>();
        for (ConfigurationPropertyName name : source) {
            names.add(Objects.requireNonNull(name, "property name must not be null"));
        }
        return List.copyOf(names);
    }

    /**
     * Maps every raw variable independently. This mirrors Boot's lookup without reading
     * values and prevents one valid alias from hiding another invalid name.
     */
    private static List<PropertyNameCandidate> collectCandidatesFromSystemEnvironment(
            SystemEnvironmentPropertySource propertySource
    ) {
        if (propertySource instanceof PropertySourceInfo sourceInfo) {
            String prefix = sourceInfo.getPrefix();
            if (prefix != null && !prefix.isBlank()) {
                // Prefix-aware environment mapping has additional whole-name
                // semantics, so skipping it avoids speculative false warnings.
                return List.of();
            }
        }
        List<PropertyNameCandidate> candidates = new ArrayList<>();
        for (String propertyName : propertySource.getPropertyNames()) {
            Objects.requireNonNull(propertyName, "property name must not be null");
            candidates.addAll(mapEnvironmentVariableToCandidates(propertySource.getName(), propertyName));
        }
        return List.copyOf(candidates);
    }

    /**
     * Maps one environment variable through Boot using a one-entry source, keeping its
     * aliases independent from every other variable in the original source.
     */
    private static List<PropertyNameCandidate> mapEnvironmentVariableToCandidates(
            String propertySourceName,
            String environmentVariableName
    ) {
        PropertySource<?> isolatedSource = new SystemEnvironmentPropertySource(
                propertySourceName,
                Map.of(environmentVariableName, Boolean.TRUE)
        );
        List<PropertyNameCandidate> candidates = new ArrayList<>();
        for (ConfigurationPropertySource source :
                ConfigurationPropertySources.from(List.of(isolatedSource))) {
            if (!(source instanceof IterableConfigurationPropertySource iterableSource)) {
                continue;
            }
            List<ConfigurationPropertyName> mappedNames = enumeratePropertyNames(iterableSource);
            DiagnosticContext context = new DiagnosticContext(iterableSource, true);
            for (ConfigurationPropertyName mappedName : mappedNames) {
                candidates.add(new PropertyNameCandidate(mappedName, context));
            }
        }
        return List.copyOf(candidates);
    }

    /** Source and mapping mode required while diagnosing one candidate name. */
    record DiagnosticContext(
            IterableConfigurationPropertySource source,
            boolean systemEnvironmentMapping
    ) {
    }

    /** A discovered property name paired with the context required to diagnose it. */
    record PropertyNameCandidate(
            ConfigurationPropertyName name,
            DiagnosticContext context
    ) {
    }

}

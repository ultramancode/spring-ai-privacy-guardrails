package io.github.ultramancode.privacy.buildlogic.tasks;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@DisableCachingByDefault(because = "This task validates generated Maven metadata and produces no output")
public abstract class VerifyPublishedModuleBoundaries extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getPomFiles();

    @Input
    public abstract SetProperty<String> getSpringIndependentPublications();

    @Input
    public abstract MapProperty<String, String> getExpectedFirstPartyDependencies();

    @Input
    public abstract Property<String> getPublicationGroup();

    @Input
    public abstract Property<String> getPublicationVersion();

    @Input
    public abstract Property<String> getDependencyFreePublication();

    @TaskAction
    public void verifyPublishedBoundaries() {
        verifyGeneratedPoms(
                getPomFiles().getFiles(),
                getSpringIndependentPublications().get(),
                getExpectedFirstPartyDependencies().get(),
                getPublicationGroup().get(),
                getPublicationVersion().get(),
                getDependencyFreePublication().get()
        );
        getLogger().lifecycle(
                "Verified exact direct first-party dependency graphs for {} generated Maven POMs "
                        + "and Spring-independent boundaries for {} publications.",
                getPomFiles().getFiles().size(),
                getSpringIndependentPublications().get().size()
        );
    }

    static void verifyGeneratedPoms(
            Collection<File> pomFiles,
            Set<String> springIndependentPublications,
            Map<String, String> expectedFirstPartyDependencies,
            String publicationGroup,
            String publicationVersion,
            String dependencyFreePublication
    ) {
        List<String> violations = new ArrayList<>();
        Set<String> actualArtifactIds = new TreeSet<>();
        for (File pomFile : pomFiles) {
            Document pom = parseXml(pomFile);
            Element project = pom.getDocumentElement();
            String artifactId = directChildText(project, "artifactId");
            if (!actualArtifactIds.add(artifactId)) {
                violations.add("Duplicate generated publication metadata for " + artifactId);
            }
            if (!expectedFirstPartyDependencies.containsKey(artifactId)) {
                violations.add("Unexpected publication metadata input: " + artifactId);
                continue;
            }
            String pomGroup = directChildText(project, "groupId");
            if (!publicationGroup.equals(pomGroup)) {
                violations.add(artifactId + " POM must use group " + publicationGroup
                        + ", found " + pomGroup);
            }
            String version = directChildText(project, "version");
            if (!publicationVersion.equals(version)) {
                violations.add(artifactId + " POM must use version " + publicationVersion
                        + ", found " + version);
            }

            Set<String> actualDependencies = directFirstPartyDependencies(
                    pom,
                    artifactId,
                    publicationGroup,
                    publicationVersion,
                    violations
            );
            Set<String> expectedDependencies = parseExpectedDependencies(
                    expectedFirstPartyDependencies.get(artifactId)
            );
            if (!actualDependencies.equals(expectedDependencies)) {
                violations.add(artifactId + " POM first-party dependencies must be "
                        + expectedDependencies + ", found " + actualDependencies);
            }

            if (springIndependentPublications.contains(artifactId)) {
                Set<String> springGroups = new TreeSet<>();
                NodeList groupIds = pom.getElementsByTagName("groupId");
                for (int index = 0; index < groupIds.getLength(); index++) {
                    String group = groupIds.item(index).getTextContent().trim();
                    if (group.startsWith("org.springframework")) {
                        springGroups.add(group);
                    }
                }
                if (!springGroups.isEmpty()) {
                    violations.add(artifactId + " POM contains Spring coordinates " + springGroups);
                }
            }
            if (artifactId.equals(dependencyFreePublication)
                    && pom.getElementsByTagName("dependency").getLength() > 0) {
                violations.add("Core POM must not publish dependencies or imported platforms");
            }
        }
        Set<String> expectedArtifactIds = new TreeSet<>(expectedFirstPartyDependencies.keySet());
        if (!actualArtifactIds.equals(expectedArtifactIds)) {
            violations.add("Expected generated POM artifactIds " + expectedArtifactIds
                    + ", found " + actualArtifactIds);
        }
        if (!violations.isEmpty()) {
            throw new GradleException(
                    "Published module boundary violations:\n - " + String.join("\n - ", violations)
            );
        }
    }

    private static Set<String> directFirstPartyDependencies(
            Document pom,
            String publication,
            String publicationGroup,
            String publicationVersion,
            List<String> violations
    ) {
        Element directDependencies = findDirectChild(pom.getDocumentElement(), "dependencies");
        if (directDependencies == null) {
            return Set.of();
        }
        Set<String> artifactIds = new TreeSet<>();
        NodeList dependencies = directDependencies.getChildNodes();
        for (int index = 0; index < dependencies.getLength(); index++) {
            if (!(dependencies.item(index) instanceof Element dependency)
                    || !dependency.getTagName().equals("dependency")) {
                continue;
            }
            String group = directChildText(dependency, "groupId");
            if (!publicationGroup.equals(group)) {
                continue;
            }
            String artifactId = directChildText(dependency, "artifactId");
            if (!artifactIds.add(artifactId)) {
                violations.add(publication + " POM contains duplicate first-party dependency "
                        + artifactId);
            }
            String version = directChildText(dependency, "version");
            if (!publicationVersion.equals(version)) {
                violations.add(publication + " POM dependency " + artifactId
                        + " must use version " + publicationVersion + ", found " + version);
            }
        }
        return artifactIds;
    }

    private static Set<String> parseExpectedDependencies(String encodedDependencies) {
        if (encodedDependencies.isBlank()) {
            return Set.of();
        }
        Set<String> dependencies = new TreeSet<>();
        for (String dependency : encodedDependencies.split(",")) {
            dependencies.add(dependency);
        }
        return dependencies;
    }

    private static Document parseXml(File file) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(file);
        } catch (Exception exception) {
            throw new GradleException("Unable to parse generated Maven POM " + file, exception);
        }
    }

    private static String directChildText(Element parent, String name) {
        Element child = findDirectChild(parent, name);
        if (child != null) {
            return child.getTextContent().trim();
        }
        throw new GradleException("Generated POM is missing direct " + name);
    }

    private static Element findDirectChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }
}

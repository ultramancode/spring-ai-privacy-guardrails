package io.github.ultramancode.privacy.buildlogic.tasks;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifyPublishedModuleBoundariesTest {

    private static final String GROUP = "io.github.example";

    private static final String VERSION = "0.1.0";

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsExactFirstPartyDependencyGraph() throws IOException {
        Path core = pom("privacy-core", List.of());
        Path adapter = pom("privacy-adapter", List.of("privacy-core"));

        assertThatCode(() -> VerifyPublishedModuleBoundaries.verifyGeneratedPoms(
                List.of(core.toFile(), adapter.toFile()),
                Set.of("privacy-core", "privacy-adapter"),
                Map.of("privacy-core", "", "privacy-adapter", "privacy-core"),
                GROUP,
                VERSION,
                "privacy-core"
        )).doesNotThrowAnyException();
    }

    @Test
    void ignoresManagedAndProfileDependenciesWhenCheckingDirectGraph() throws IOException {
        Path adapter = pomWithNonDirectFirstPartyDependencies("privacy-adapter");

        assertThatCode(() -> VerifyPublishedModuleBoundaries.verifyGeneratedPoms(
                List.of(adapter.toFile()),
                Set.of(),
                Map.of("privacy-adapter", ""),
                GROUP,
                VERSION,
                "privacy-core"
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsDependencyThatAnotherPublicationWouldOtherwiseMask() throws IOException {
        Path firstStarter = pom("privacy-first-starter", List.of("privacy-base-starter"));
        Path secondStarter = pom(
                "privacy-second-starter",
                List.of("privacy-base-starter", "privacy-first-adapter", "privacy-second-adapter")
        );

        assertThatThrownBy(() -> VerifyPublishedModuleBoundaries.verifyGeneratedPoms(
                List.of(firstStarter.toFile(), secondStarter.toFile()),
                Set.of(),
                Map.of(
                        "privacy-first-starter",
                        "privacy-base-starter,privacy-first-adapter",
                        "privacy-second-starter",
                        "privacy-base-starter,privacy-second-adapter"
                ),
                GROUP,
                VERSION,
                "privacy-core"
        ))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("privacy-first-starter POM first-party dependencies")
                .hasMessageContaining("privacy-second-starter POM first-party dependencies");
    }

    @Test
    void rejectsWrongFirstPartyDependencyVersion() throws IOException {
        Path adapter = pom("privacy-adapter", List.of("privacy-core"), "0.2.0");

        assertThatThrownBy(() -> VerifyPublishedModuleBoundaries.verifyGeneratedPoms(
                List.of(adapter.toFile()),
                Set.of(),
                Map.of("privacy-adapter", "privacy-core"),
                GROUP,
                VERSION,
                "privacy-core"
        ))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("privacy-core must use version 0.1.0, found 0.2.0");
    }

    private Path pom(String artifactId, List<String> dependencies) throws IOException {
        return pom(artifactId, dependencies, VERSION);
    }

    private Path pom(
            String artifactId,
            List<String> dependencies,
            String dependencyVersion
    ) throws IOException {
        String dependencyXml = dependencies.stream()
                .map(dependency -> """
                        <dependency>
                          <groupId>%s</groupId>
                          <artifactId>%s</artifactId>
                          <version>%s</version>
                        </dependency>
                        """.formatted(GROUP, dependency, dependencyVersion))
                .reduce("", String::concat);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>
                %s  </dependencies>
                </project>
                """.formatted(GROUP, artifactId, VERSION, dependencyXml);
        Path file = this.temporaryDirectory.resolve(artifactId + ".xml");
        Files.writeString(file, xml);
        return file;
    }

    private Path pomWithNonDirectFirstPartyDependencies(String artifactId) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>%s</groupId>
                        <artifactId>privacy-managed</artifactId>
                        <version>%s</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <profiles>
                    <profile>
                      <id>optional-profile</id>
                      <dependencies>
                        <dependency>
                          <groupId>%s</groupId>
                          <artifactId>privacy-profile</artifactId>
                          <version>%s</version>
                        </dependency>
                      </dependencies>
                    </profile>
                  </profiles>
                </project>
                """.formatted(GROUP, artifactId, VERSION, GROUP, VERSION, GROUP, VERSION);
        Path file = this.temporaryDirectory.resolve(artifactId + "-non-direct.xml");
        Files.writeString(file, xml);
        return file;
    }
}

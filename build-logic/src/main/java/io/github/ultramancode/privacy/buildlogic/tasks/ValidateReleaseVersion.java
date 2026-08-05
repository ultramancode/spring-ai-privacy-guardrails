package io.github.ultramancode.privacy.buildlogic.tasks;

import java.util.Arrays;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "This task validates a configured version and produces no output")
public abstract class ValidateReleaseVersion extends DefaultTask {

    @Input
    public abstract Property<String> getReleaseVersion();

    @TaskAction
    public void validateVersion() {
        String version = getReleaseVersion().get();
        boolean valid = version.matches(
                "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                        + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
        );
        if (valid && version.contains("-")) {
            String prerelease = version.substring(version.indexOf('-') + 1);
            valid = Arrays.stream(prerelease.split("\\."))
                    .allMatch(identifier -> !identifier.equalsIgnoreCase("SNAPSHOT")
                            && (!identifier.matches("\\d+")
                            || identifier.equals("0")
                            || !identifier.startsWith("0")));
        }
        if (!valid) {
            throw new GradleException(
                    "Central Portal publication requires strict SemVer without build metadata or SNAPSHOT "
                            + "identifiers, got " + version
            );
        }
    }
}

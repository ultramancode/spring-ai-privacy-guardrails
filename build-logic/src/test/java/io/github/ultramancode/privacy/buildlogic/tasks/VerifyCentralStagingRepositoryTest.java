package io.github.ultramancode.privacy.buildlogic.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifyCentralStagingRepositoryTest {

    @TempDir
    Path repository;

    @Test
    void rejectsOnlyChecksumsExcludedFromCentralBundles() throws IOException {
        List<String> retained = List.of(
                "module-0.1.1.jar",
                "module-0.1.1.jar.asc",
                "module-0.1.1.jar.md5",
                "module-0.1.1.jar.sha1"
        );
        List<String> forbidden = List.of(
                "module-0.1.1.jar.sha256",
                "module-0.1.1.jar.sha512",
                "module-0.1.1.jar.asc.md5",
                "module-0.1.1.jar.asc.sha1",
                "module-0.1.1.jar.asc.sha256",
                "module-0.1.1.jar.asc.sha512"
        );
        for (String file : retained) {
            Files.createFile(this.repository.resolve(file));
        }
        for (String file : forbidden) {
            Files.createFile(this.repository.resolve(file));
        }

        assertThat(VerifyCentralStagingRepository.findForbiddenChecksums(
                this.repository.toFile()
        )).containsExactlyElementsOf(forbidden.stream().sorted().toList());
    }
}

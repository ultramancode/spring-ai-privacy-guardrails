package io.github.ultramancode.privacy.buildlogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class PrivacyModuleSpecTest {

    @Test
    void capturesPublicationAndDependencyPolicy() {
        PrivacyModuleSpec spec = new PrivacyModuleSpec("privacy-adapter");

        spec.publication("io.github.example.privacy.adapter", "Example Privacy Adapter");
        spec.dependsOnProject("privacy-core", "privacy-test");
        spec.denyExternalGroup("org.springframework", "com.example.forbidden");
        spec.springIndependentPublication();
        spec.validate();

        assertThat(spec.getPath()).isEqualTo(":privacy-adapter");
        assertThat(spec.getAutomaticModuleName()).isEqualTo("io.github.example.privacy.adapter");
        assertThat(spec.getPublicationDisplayName()).isEqualTo("Example Privacy Adapter");
        assertThat(spec.getAllowedProjectDependencies())
                .containsExactlyInAnyOrder(":privacy-core", ":privacy-test");
        assertThat(spec.getForbiddenExternalGroupPrefixes())
                .containsExactlyInAnyOrder("org.springframework", "com.example.forbidden");
        assertThat(spec.isSpringIndependentPublication()).isTrue();
    }

    @Test
    void rejectsSelfDependency() {
        PrivacyModuleSpec spec = new PrivacyModuleSpec("privacy-core");
        spec.dependsOnProject("privacy-core");

        assertThatIllegalStateException()
                .isThrownBy(spec::validate)
                .withMessage("privacy-core cannot depend on itself");
    }

    @Test
    void rejectsInvalidAutomaticModuleName() {
        PrivacyModuleSpec spec = new PrivacyModuleSpec("privacy-core");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> spec.publication("privacy-core", "Privacy Core"))
                .withMessageContaining("Invalid Automatic-Module-Name");
    }

    @Test
    void rejectsBlankPublicationDisplayName() {
        PrivacyModuleSpec spec = new PrivacyModuleSpec("privacy-core");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> spec.publication("io.github.example.privacy.core", " "))
                .withMessageContaining("Publication display name must not be blank");
    }
}

package io.github.ultramancode.springai.privacy.autoconfigure;

import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.beans.PropertyDescriptor;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class PrivacyConfigurationPropertyDiagnosticsTest {

    private final ApplicationContextRunner diagnosticsRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PrivacyConfigurationDiagnosticsAutoConfiguration.class,
                    PrivacyGuardrailsAutoConfiguration.class
            ))
            .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of());

    @Test
    void diagnosticsAreRegisteredIndependentlyOfTheEnabledAutoConfiguration() throws Exception {
        try (InputStream input = PrivacyConfigurationPropertyDiagnosticsTest.class.getResourceAsStream(
                "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
        )) {
            assertThat(input).isNotNull();
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .contains(PrivacyConfigurationDiagnosticsAutoConfiguration.class.getName());
        }
    }

    @Test
    void fixedDiagnosticSchemaStaysInSyncWithConfigurationProperties() {
        assertDiagnosticSchemaMatches(
                PrivacyGuardrailsProperties.class,
                PrivacyConfigurationPropertyDiagnostics.ROOT_PROPERTIES
        );
        assertDiagnosticSchemaMatches(
                PrivacyGuardrailsProperties.Output.class,
                PrivacyConfigurationPropertyDiagnostics.OUTPUT_PROPERTIES
        );
        assertDiagnosticSchemaMatches(
                PrivacyGuardrailsProperties.ResponseInspection.class,
                PrivacyConfigurationPropertyDiagnostics.RESPONSE_INSPECTION_PROPERTIES
        );
        assertDiagnosticSchemaMatches(
                PrivacyGuardrailsProperties.Analysis.class,
                PrivacyConfigurationPropertyDiagnostics.ANALYSIS_PROPERTIES
        );
        assertDiagnosticSchemaMatches(
                PrivacyGuardrailsProperties.Regex.class,
                PrivacyConfigurationPropertyDiagnostics.REGEX_PROPERTIES
        );
        assertDiagnosticSchemaMatches(
                PrivacyGuardrailsProperties.Regex.Rule.class,
                PrivacyConfigurationPropertyDiagnostics.REGEX_RULE_PROPERTIES
        );
        assertDiagnosticSchemaMatches(
                PrivacyGuardrailsProperties.Tools.class,
                PrivacyConfigurationPropertyDiagnostics.TOOLS_PROPERTIES
        );
        assertNoMapOrCollectionProperties(PrivacyGuardrailsProperties.class);
        assertNoMapOrCollectionProperties(PrivacyGuardrailsProperties.Output.class);
        assertNoMapOrCollectionProperties(
                PrivacyGuardrailsProperties.ResponseInspection.class
        );
        assertContainerPropertiesMatch(
                PrivacyGuardrailsProperties.Analysis.class,
                PrivacyConfigurationPropertyDiagnostics.ANALYSIS_MAP_PROPERTIES,
                PrivacyConfigurationPropertyDiagnostics.ANALYSIS_INDEXED_SCALAR_PROPERTIES,
                Set.of()
        );
        assertContainerPropertiesMatch(
                PrivacyGuardrailsProperties.Regex.class,
                Set.of(),
                Set.of(),
                PrivacyConfigurationPropertyDiagnostics.REGEX_INDEXED_OBJECT_PROPERTIES
        );
        assertNoMapOrCollectionProperties(PrivacyGuardrailsProperties.Regex.Rule.class);
        assertContainerPropertiesMatch(
                PrivacyGuardrailsProperties.Tools.class,
                PrivacyConfigurationPropertyDiagnostics.TOOLS_MAP_PROPERTIES,
                Set.of(),
                Set.of()
        );
    }

    @Test
    void suggestsTheCanonicalNameForALikelyFixedPropertyTypo(CapturedOutput output) {
        this.diagnosticsRunner
                .withPropertyValues(
                        "spring.ai.privacy.enabled=true",
                        "spring.ai.privacy.output.enabledddd=synthetic-sensitive-value"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PrivacyService.class);
                    assertThat(context.getBean(PrivacyGuardrailsProperties.class)
                            .getOutput()
                            .isEnabled()).isFalse();
                    assertThat(output).contains(
                            "Unrecognized Spring AI Privacy Guardrails configuration property "
                                    + "'spring.ai.privacy.output.enabledddd'"
                    ).contains("Did you mean 'spring.ai.privacy.output.enabled'?")
                            .contains("No configuration value was included in this diagnostic.")
                            .doesNotContain("synthetic-sensitive-value");
                });
    }

    @Test
    void warnsWithoutEchoingDissimilarUnknownNamesInFixedConfigurationAreas(
            CapturedOutput output
    ) {
        this.diagnosticsRunner
                .withPropertyValues(
                        "spring.ai.privacy.output.synthetic-credential-name=synthetic-secret-one",
                        "spring.ai.privacy.response-inspection.synthetic-credential-name="
                                + "synthetic-secret-two",
                        "spring.ai.privacy.analysis.synthetic-credential-name=synthetic-secret-three",
                        "spring.ai.privacy.regex.synthetic-credential-name=synthetic-secret-four",
                        "spring.ai.privacy.tools.synthetic-credential-name=synthetic-secret-five"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                    assertThat(output)
                            .contains("below fixed prefix 'spring.ai.privacy.output'")
                            .contains("below fixed prefix 'spring.ai.privacy.response-inspection'")
                            .contains("below fixed prefix 'spring.ai.privacy.analysis'")
                            .contains("below fixed prefix 'spring.ai.privacy.regex'")
                            .contains("below fixed prefix 'spring.ai.privacy.tools'")
                            .contains("The unrecognized property name and configuration value were "
                                    + "omitted from this diagnostic.")
                            .doesNotContain("synthetic-credential-name")
                            .doesNotContain("synthetic-secret-one")
                            .doesNotContain("synthetic-secret-two")
                            .doesNotContain("synthetic-secret-three")
                            .doesNotContain("synthetic-secret-four")
                            .doesNotContain("synthetic-secret-five");
                });
    }

    @Test
    void warnsWithoutEchoingUnexpectedDescendantsOfFixedLeafProperties(
            CapturedOutput output
    ) {
        this.diagnosticsRunner
                .withPropertyValues(
                        "spring.ai.privacy.output.enabled.synthetic-credential-name="
                                + "synthetic-secret-one",
                        "spring.ai.privacy.analysis.included-entity-types.synthetic-credential-name="
                                + "synthetic-secret-two",
                        "spring.ai.privacy.regex.rules[0].score.synthetic-credential-name="
                                + "synthetic-secret-three"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                    assertThat(output)
                            .contains("below fixed prefix 'spring.ai.privacy.output.enabled'")
                            .contains("below fixed prefix "
                                    + "'spring.ai.privacy.analysis.included-entity-types'")
                            .contains("below fixed prefix 'spring.ai.privacy.regex.rules[0].score'")
                            .doesNotContain("synthetic-credential-name")
                            .doesNotContain("synthetic-secret-one")
                            .doesNotContain("synthetic-secret-two")
                            .doesNotContain("synthetic-secret-three");
                });
    }

    @Test
    void warnsAboutTheGlobalEnabledTypoEvenWhenPrivacyAutoConfigurationIsInactive(
            CapturedOutput output
    ) {
        this.diagnosticsRunner
                .withPropertyValues("spring.ai.privacy.enabledddd=synthetic-sensitive-value")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                    assertThat(output).contains(
                            "'spring.ai.privacy.enabledddd'. Did you mean "
                                    + "'spring.ai.privacy.enabled'?"
                    ).doesNotContain("synthetic-sensitive-value");
                });
    }

    @Test
    void diagnosticsRemainEagerWhenTheHostEnablesGlobalLazyInitialization(
            CapturedOutput output
    ) {
        this.diagnosticsRunner
                .withInitializer(context -> context.addBeanFactoryPostProcessor(
                        new LazyInitializationBeanFactoryPostProcessor()
                ))
                .withPropertyValues("spring.ai.privacy.enabledddd=synthetic-sensitive-value")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                    assertThat(output)
                            .contains("'spring.ai.privacy.enabledddd'. Did you mean ")
                            .contains("'spring.ai.privacy.enabled'?")
                            .doesNotContain("synthetic-sensitive-value");
                });
    }

    @Test
    void recognizesDashedFixedPropertiesFromSystemEnvironmentNames(CapturedOutput output) {
        withSystemEnvironment(Map.of(
                "SPRING_AI_PRIVACY_RESPONSE_INSPECTION_MAX_CHARACTERS", "8",
                "SPRING_AI_PRIVACY_ANALYSIS_MINIMUM_SCORE", "0.5",
                "SPRING_AI_PRIVACY_ANALYSIS_INCLUDED_ENTITY_TYPES_0", "CUSTOMER_ID",
                "SPRING_AI_PRIVACY_ANALYSIS_SUPPLEMENTAL_PROVIDERS_0", "custom-provider",
                "SPRING_AI_PRIVACY_OUTPUT_BLOCK_EXCEPTION_MESSAGE", "safe-message",
                "SPRING_AI_PRIVACY_REGEX_RULES_0_ENTITY_TYPE", "CUSTOMER_ID"
        )).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
            assertThat(output)
                    .doesNotContain("Unrecognized Spring AI Privacy Guardrails configuration property")
                    .doesNotContain("safe-message");
        });
    }

    @Test
    void warnsForDashedFixedPropertyTyposFromSystemEnvironmentNames(
            CapturedOutput output
    ) {
        withSystemEnvironment(Map.of(
                "SPRING_AI_PRIVACY_ENABLEDDDD", "true",
                "SPRING_AI_PRIVACY_RESPONSE_INSPECTION_MAX_CHARACTERSS", "8",
                "SPRING_AI_PRIVACY_OUTPUT_BLOCK_EXCEPTION_MESAGE", "synthetic-sensitive-value",
                "SPRING_AI_PRIVACY_REGEX_RULES_0_CAPTURE_GROPU", "0"
        )).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
            assertThat(output)
                    .contains("'spring.ai.privacy.response-inspection.max-characterss'")
                    .contains("'spring.ai.privacy.response-inspection.max-characters'")
                    .contains("'spring.ai.privacy.output.block-exception-mesage'")
                    .contains("'spring.ai.privacy.output.block-exception-message'")
                    .contains("'spring.ai.privacy.regex.rules[0].capture-gropu'")
                    .contains("'spring.ai.privacy.regex.rules[0].capture-group'")
                    .contains("'spring.ai.privacy.enabledddd'")
                    .contains("'spring.ai.privacy.enabled'")
                    .doesNotContain("synthetic-sensitive-value");
        });
    }

    @Test
    void ignoresProviderExtensionsAndSupportedDynamicSegments(CapturedOutput output) {
        this.diagnosticsRunner
                .withPropertyValues(
                        "spring.ai.privacy.custom-provider.credentials.client-secret=synthetic-secret-one",
                        "spring.ai.privacy.analysis.provider-minimum-scores.custom-provider=0.75",
                        "spring.ai.privacy.analysis.entity-aliases.external-label=CUSTOMER_ID",
                        "spring.ai.privacy.analysis.included-entity-types[0]=CUSTOMER_ID",
                        "spring.ai.privacy.analysis.supplemental-providers[0]=custom-provider",
                        "spring.ai.privacy.tools.disclosures.customer-lookup[0]=CUSTOMER_ID",
                        "spring.ai.privacy.presidio.headers.Authorization=synthetic-secret-two",
                        "spring.ai.privacy.opennlp.entity-models.CUSTOMER_RECORD=classpath:synthetic-model",
                        "spring.ai.privacy.toolz.custom-setting=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                    assertThat(output)
                            .doesNotContain("Unrecognized Spring AI Privacy Guardrails configuration property")
                            .doesNotContain("synthetic-secret-one")
                            .doesNotContain("synthetic-secret-two");
                });
    }

    @Test
    void reportsOnlyTheFixedTypoSegmentBeforePotentiallySensitiveDescendants(
            CapturedOutput output
    ) {
        this.diagnosticsRunner
                .withPropertyValues(
                        "spring.ai.privacy.analysis.provider-minimum-scorez.synthetic-credential-name="
                                + "synthetic-secret"
                )
                .run(context -> assertThat(output)
                        .contains("'spring.ai.privacy.analysis.provider-minimum-scorez'")
                        .contains("'spring.ai.privacy.analysis.provider-minimum-scores'")
                        .doesNotContain("synthetic-credential-name")
                        .doesNotContain("synthetic-secret"));
    }

    @Test
    void ignoresDynamicSystemEnvironmentSegmentsAndTheirValues(CapturedOutput output) {
        withSystemEnvironment(Map.of(
                "SPRING_AI_PRIVACY_ANALYSIS_PROVIDER_MINIMUM_SCORES_CUSTOM_PROVIDER", "0.75",
                "SPRING_AI_PRIVACY_ANALYSIS_ENTITY_ALIASES_EXTERNAL_LABEL", "CUSTOMER_ID",
                "SPRING_AI_PRIVACY_TOOLS_DISCLOSURES_CUSTOMER_LOOKUP_0", "CUSTOMER_ID",
                "SPRING_AI_PRIVACY_PRESIDIO_HEADERS_AUTHORIZATION", "synthetic-secret-one",
                "SPRING_AI_PRIVACY_OPENNLP_ENTITY_MODELS_CUSTOMER_RECORD", "synthetic-secret-two"
        )).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
            assertThat(output)
                    .doesNotContain("Unrecognized Spring AI Privacy Guardrails configuration property")
                    .doesNotContain("synthetic-secret-one")
                    .doesNotContain("synthetic-secret-two");
        });
    }

    @Test
    void skipsOnlyAPropertySourceThatCannotEnumerateItsNames(CapturedOutput output) {
        this.diagnosticsRunner
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new ThrowingEnumerablePropertySource()
                ))
                .withPropertyValues("spring.ai.privacy.enabledddd=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                    assertThat(output)
                            .contains("'spring.ai.privacy.enabledddd'. Did you mean ")
                            .contains("'spring.ai.privacy.enabled'?")
                            .doesNotContain(ThrowingEnumerablePropertySource.SENSITIVE_MESSAGE);
                });
    }

    private ApplicationContextRunner withSystemEnvironment(Map<String, Object> environment) {
        return this.diagnosticsRunner.withInitializer(context ->
                context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                environment
                        )
                )
        );
    }

    private static void assertDiagnosticSchemaMatches(
            Class<?> propertiesType,
            List<String> diagnosticProperties
    ) {
        String description = "diagnostic schema for " + propertiesType.getSimpleName();
        assertThat(diagnosticProperties)
                .as(description)
                .doesNotHaveDuplicates();
        assertThat(diagnosticProperties.stream()
                .map(PrivacyConfigurationPropertyDiagnosticsTest::toJavaBeanPropertyName)
                .collect(Collectors.toSet()))
                .as(description)
                .containsExactlyInAnyOrderElementsOf(javaBeanPropertyNames(propertiesType));
    }

    private static Set<String> javaBeanPropertyNames(Class<?> propertiesType) {
        return Arrays.stream(BeanUtils.getPropertyDescriptors(propertiesType))
                .map(PropertyDescriptor::getName)
                .filter(name -> !name.equals("class"))
                .collect(Collectors.toSet());
    }

    private static void assertNoMapOrCollectionProperties(Class<?> propertiesType) {
        assertContainerPropertiesMatch(
                propertiesType,
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    private static void assertContainerPropertiesMatch(
            Class<?> propertiesType,
            Set<String> mapProperties,
            Set<String> indexedScalarProperties,
            Set<String> indexedObjectProperties
    ) {
        assertPropertiesOfTypeMatch(propertiesType, Map.class, mapProperties);
        Set<String> indexedProperties = Stream.concat(
                indexedScalarProperties.stream(),
                indexedObjectProperties.stream()
        ).collect(Collectors.toSet());
        assertPropertiesOfTypeMatch(propertiesType, Collection.class, indexedProperties);
        assertCollectionElementKindMatches(
                propertiesType,
                true,
                indexedScalarProperties
        );
        assertCollectionElementKindMatches(
                propertiesType,
                false,
                indexedObjectProperties
        );
    }

    private static void assertCollectionElementKindMatches(
            Class<?> propertiesType,
            boolean simpleElementType,
            Set<String> diagnosticProperties
    ) {
        String elementKind = simpleElementType ? "simple" : "object";
        String description = elementKind
                + " indexed properties in the diagnostic schema for "
                + propertiesType.getSimpleName();
        Set<String> actualProperties = Arrays.stream(
                        BeanUtils.getPropertyDescriptors(propertiesType)
                )
                .filter(descriptor -> Collection.class.isAssignableFrom(
                        descriptor.getPropertyType()
                ))
                .filter(descriptor -> BeanUtils.isSimpleValueType(
                        collectionElementType(descriptor)
                ) == simpleElementType)
                .map(PropertyDescriptor::getName)
                .collect(Collectors.toSet());
        assertThat(diagnosticProperties.stream()
                .map(PrivacyConfigurationPropertyDiagnosticsTest::toJavaBeanPropertyName)
                .collect(Collectors.toSet()))
                .as(description)
                .containsExactlyInAnyOrderElementsOf(actualProperties);
    }

    private static Class<?> collectionElementType(PropertyDescriptor descriptor) {
        if (descriptor.getReadMethod() == null) {
            throw new AssertionError("No read method for " + descriptor.getName());
        }
        Type returnType = descriptor.getReadMethod().getGenericReturnType();
        if (!(returnType instanceof ParameterizedType parameterizedType)) {
            throw new AssertionError("No generic element type for " + descriptor.getName());
        }
        Type[] typeArguments = parameterizedType.getActualTypeArguments();
        if (typeArguments.length != 1) {
            throw new AssertionError("Expected one generic element type for "
                    + descriptor.getName());
        }
        Type elementType = typeArguments[0];
        if (elementType instanceof Class<?> elementClass) {
            return elementClass;
        }
        if (elementType instanceof ParameterizedType nestedType
                && nestedType.getRawType() instanceof Class<?> rawClass) {
            return rawClass;
        }
        throw new AssertionError("Unsupported generic element type for "
                + descriptor.getName()
                + ": "
                + elementType.getTypeName());
    }

    private static void assertPropertiesOfTypeMatch(
            Class<?> propertiesType,
            Class<?> propertyType,
            Set<String> diagnosticProperties
    ) {
        String description = propertyType.getSimpleName()
                + " properties in the diagnostic schema for "
                + propertiesType.getSimpleName();
        Set<String> actualProperties = Arrays.stream(
                        BeanUtils.getPropertyDescriptors(propertiesType)
                )
                .filter(descriptor -> propertyType.isAssignableFrom(
                        descriptor.getPropertyType()
                ))
                .map(PropertyDescriptor::getName)
                .collect(Collectors.toSet());
        assertThat(diagnosticProperties.stream()
                .map(PrivacyConfigurationPropertyDiagnosticsTest::toJavaBeanPropertyName)
                .collect(Collectors.toSet()))
                .as(description)
                .containsExactlyInAnyOrderElementsOf(actualProperties);
    }

    private static String toJavaBeanPropertyName(String dashedName) {
        StringBuilder javaBeanName = new StringBuilder(dashedName.length());
        boolean capitalizeNext = false;
        for (int index = 0; index < dashedName.length(); index++) {
            char character = dashedName.charAt(index);
            if (character == '-') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                javaBeanName.append(Character.toUpperCase(character));
                capitalizeNext = false;
            } else {
                javaBeanName.append(character);
            }
        }
        return javaBeanName.toString();
    }

    private static final class ThrowingEnumerablePropertySource
            extends EnumerablePropertySource<Object> {

        private static final String SENSITIVE_MESSAGE = "synthetic-sensitive-value";

        private ThrowingEnumerablePropertySource() {
            super("throwing-enumerable-property-source", new Object());
        }

        @Override
        public String[] getPropertyNames() {
            throw new IllegalStateException(SENSITIVE_MESSAGE);
        }

        @Override
        public boolean containsProperty(String name) {
            return false;
        }

        @Override
        public Object getProperty(String name) {
            return null;
        }

    }

}

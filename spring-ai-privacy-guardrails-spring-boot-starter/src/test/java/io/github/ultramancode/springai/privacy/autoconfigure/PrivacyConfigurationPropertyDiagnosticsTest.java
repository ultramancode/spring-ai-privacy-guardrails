package io.github.ultramancode.springai.privacy.autoconfigure;

import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.PropertySourceInfo;
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

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class PrivacyConfigurationPropertyDiagnosticsTest {

    private final PiiAnalyzer emptyAnalyzer = (text, options, processingLimits) -> List.of();

    private final ApplicationContextRunner diagnosticsRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PrivacyConfigurationDiagnosticsAutoConfiguration.class
            ));

    private final ApplicationContextRunner privacyRunner = this.diagnosticsRunner
            .withConfiguration(AutoConfigurations.of(PrivacyGuardrailsAutoConfiguration.class))
            .withBean(PiiAnalyzer.class, () -> this.emptyAnalyzer);

    @Test
    void diagnosticsAreRegisteredIndependentlyOfThePrivacyAutoConfiguration() throws Exception {
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
                PrivacyGuardrailsProperties.Processing.class,
                PrivacyConfigurationPropertyDiagnostics.PROCESSING_PROPERTIES
        );
        assertDiagnosticSchemaMatches(
                PrivacyGuardrailsProperties.Analysis.class,
                PrivacyConfigurationPropertyDiagnostics.ANALYSIS_PROPERTIES
        );
        assertDiagnosticSchemaMatches(
                PrivacyGuardrailsProperties.Rules.class,
                PrivacyConfigurationPropertyDiagnostics.RULES_PROPERTIES
        );
        assertNoMapOrCollectionProperties(PrivacyGuardrailsProperties.class);
        assertContainerPropertiesMatch(
                PrivacyGuardrailsProperties.Analysis.class,
                PrivacyConfigurationPropertyDiagnostics.ANALYSIS_MAP_PROPERTIES,
                PrivacyConfigurationPropertyDiagnostics.ANALYSIS_INDEXED_SCALAR_PROPERTIES,
                Set.of()
        );
        assertContainerPropertiesMatch(
                PrivacyGuardrailsProperties.Rules.class,
                Set.of(),
                PrivacyConfigurationPropertyDiagnostics.RULES_INDEXED_SCALAR_PROPERTIES,
                Set.of()
        );
    }

    @Test
    void suggestsTheCanonicalNameForALikelyFixedPropertyTypo(CapturedOutput output) {
        this.privacyRunner
            .withPropertyValues(
                "spring.ai.privacy.analysis.languageeee=synthetic-sensitive-value"
            )
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(PrivacyService.class);
                assertThat(context.getBean(PrivacyGuardrailsProperties.class)
                    .getAnalysis()
                    .getLanguage()).isEqualTo("en");
                assertThat(output).contains(
                    "Unrecognized Spring AI Privacy Guardrails configuration property "
                        + "'spring.ai.privacy.analysis.languageeee'"
                ).contains("Did you mean 'spring.ai.privacy.analysis.language'?")
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
                "spring.ai.privacy.processing.synthetic-credential-name="
                    + "synthetic-secret-two",
                "spring.ai.privacy.analysis.synthetic-credential-name=synthetic-secret-three",
                "spring.ai.privacy.rules.synthetic-credential-name=synthetic-secret-four"
            )
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                assertThat(output)
                    .contains("below fixed prefix 'spring.ai.privacy.processing'")
                    .contains("below fixed prefix 'spring.ai.privacy.analysis'")
                    .contains("below fixed prefix 'spring.ai.privacy.rules'")
                    .contains("The unrecognized property name and configuration value were "
                        + "omitted from this diagnostic.")
                    .doesNotContain("synthetic-credential-name")
                    .doesNotContain("synthetic-secret-two")
                    .doesNotContain("synthetic-secret-three")
                    .doesNotContain("synthetic-secret-four");
            });
    }

    @Test
    void warnsWithoutEchoingUnexpectedDescendantsOfFixedLeafProperties(
            CapturedOutput output
    ) {
        this.diagnosticsRunner
            .withPropertyValues(
                "spring.ai.privacy.analysis.language.synthetic-credential-name="
                    + "synthetic-secret-one",
                "spring.ai.privacy.analysis.included-entity-types.synthetic-credential-name="
                    + "synthetic-secret-two",
                "spring.ai.privacy.rules.include[0].synthetic-credential-name="
                    + "synthetic-secret-three"
            )
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                assertThat(output)
                    .contains("below fixed prefix 'spring.ai.privacy.analysis.language'")
                    .contains("below fixed prefix "
                        + "'spring.ai.privacy.analysis.included-entity-types'")
                    .contains("below fixed prefix 'spring.ai.privacy.rules.include'")
                    .doesNotContain("synthetic-credential-name")
                    .doesNotContain("synthetic-secret-one")
                    .doesNotContain("synthetic-secret-two")
                    .doesNotContain("synthetic-secret-three");
            });
    }

    @Test
    void warnsAboutALanguageTypoWithoutPrivacyAutoConfiguration(
        CapturedOutput output
    ) {
        this.diagnosticsRunner
            .withPropertyValues("spring.ai.privacy.analysis.languageeee=synthetic-sensitive-value")
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                assertThat(output).contains(
                    "'spring.ai.privacy.analysis.languageeee'. Did you mean "
                        + "'spring.ai.privacy.analysis.language'?"
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
            .withPropertyValues("spring.ai.privacy.analysis.languageeee=synthetic-sensitive-value")
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                assertThat(output)
                    .contains("'spring.ai.privacy.analysis.languageeee'. Did you mean ")
                    .contains("'spring.ai.privacy.analysis.language'?")
                    .doesNotContain("synthetic-sensitive-value");
            });
    }



    @Test
    void warnsForTyposInCanonicalSystemEnvironmentNames(CapturedOutput output) {
        withSystemEnvironment(Map.of(
            "SPRING_AI_PRIVACY_PROCESSING_MAXTEXTCHARACTERSS", "8",
            "SPRING_AI_PRIVACY_ANALYSIS_PROVIDERMINIMUMSCOREZ_CUSTOMPROVIDER", "0.75",
            "SPRING_AI_PRIVACY_RULES_PRESETSS", "synthetic-sensitive-value"
        )).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
            assertThat(output)
                .contains("'spring.ai.privacy.processing.maxtextcharacterss'")
                .contains("'spring.ai.privacy.processing.max-text-characters'")
                .contains("'spring.ai.privacy.analysis.providerminimumscorez'")
                .contains("'spring.ai.privacy.analysis.provider-minimum-scores'")
                .contains("'spring.ai.privacy.rules.presetss'")
                .contains("'spring.ai.privacy.rules.presets'")
                .doesNotContain("customprovider")
                .doesNotContain("synthetic-sensitive-value");
        });
    }

    @Test
    void recognizesRelaxedDynamicSegments(CapturedOutput output) {
        withSystemEnvironment(Map.of(
            "SPRING_AI_PRIVACY_ANALYSIS_PROVIDERMINIMUMSCORES_ENVPROVIDER", "0.75",
            "SPRING_AI_PRIVACY_ANALYSIS_ENTITYALIASES_EXTERNALLABEL", "CUSTOMER_ID"
        )).withPropertyValues(
            "spring.ai.privacy.analysis.providerMinimumScores.customProvider=0.75",
            "spring.ai.privacy.analysis.entityAliases.externalLabel=CUSTOMER_ID"
        ).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
            assertThat(output)
                .doesNotContain(
                    "Unrecognized Spring AI Privacy Guardrails configuration property"
            )
                .doesNotContain("customProvider")
                .doesNotContain("ENVPROVIDER");
        });
    }

    @Test
    void doesNotTreatDottedPropertyNamesAsEnvironmentNames(CapturedOutput output) {
        this.diagnosticsRunner
            .withPropertyValues(
                "spring.ai.privacy.analysis.minimum.score=0.5",
                "spring.ai.privacy.analysis.primary.provider=synthetic-sensitive-value",
                "spring.ai.privacy.rules.include.ids=CUSTOMER_ID"
            )
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                assertThat(output)
                    .contains("below fixed prefix 'spring.ai.privacy.analysis'")
                    .contains("'spring.ai.privacy.rules.include'")
                    .doesNotContain("synthetic-sensitive-value")
                    .doesNotContain("CUSTOMER_ID");
            });
    }

    @Test
    void warnsForACamelCasePropertyTypo(CapturedOutput output) {
        this.diagnosticsRunner
            .withPropertyValues(
                "spring.ai.privacy.processing.maxTextCharacterss="
                    + "synthetic-sensitive-value"
            )
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                assertThat(output)
                    .contains(
                        "'spring.ai.privacy.processing.maxtextcharacterss'"
                )
                    .contains(
                        "'spring.ai.privacy.processing.max-text-characters'"
                )
                    .doesNotContain("synthetic-sensitive-value");
            });
    }


    @Test
    void recognizesCompactAndUnderscoreSeparatedEnvironmentNamesInTheSameSource(
            CapturedOutput output
    ) {
        withPrivacySystemEnvironment(Map.of(
            "SPRING_AI_PRIVACY_ANALYSIS_LANGUAGE", "ko",
            "SPRING_AI_PRIVACY_PROCESSING_MAXTEXTCHARACTERS", "8",
            "SPRING_AI_PRIVACY_PROCESSING_MAX_OUTPUT_CHARACTERS", "9"
        )).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PrivacyService.class);
            PrivacyGuardrailsProperties properties = context.getBean(
                PrivacyGuardrailsProperties.class
            );
            assertThat(properties.getProcessing().getMaxTextCharacters()).isEqualTo(8);
            assertThat(properties.getProcessing().getMaxOutputCharacters()).isEqualTo(9);
            assertThat(output).doesNotContain(
                "Unrecognized Spring AI Privacy Guardrails configuration property"
            );
        });
    }

    @Test
    void warnsForMixedEnvironmentNamingConventions(CapturedOutput output) {
        withPrivacySystemEnvironment(Map.of(
            "SPRING_AI_PRIVACY_ANALYSIS_LANGUAGE", "ko",
            "SPRING_AI_PRIVACY_PROCESSING_MAXTEXT_CHARACTERS", "87654321"
        )).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PrivacyService.class);
            assertThat(context.getBean(PrivacyGuardrailsProperties.class)
                .getProcessing()
                .getMaxTextCharacters()).isEqualTo(new PrivacyGuardrailsProperties()
                    .getProcessing()
                    .getMaxTextCharacters());
            assertThat(output)
                .contains("Did you mean "
                    + "'spring.ai.privacy.processing.max-text-characters'?")
                .doesNotContain("87654321");
        });
    }

    @Test
    void validAliasDoesNotHideAnInvalidEnvironmentName(CapturedOutput output) {
        withPrivacySystemEnvironment(Map.of(
            "SPRING_AI_PRIVACY_ANALYSIS_LANGUAGE", "ko",
            "SPRING_AI_PRIVACY_PROCESSING_MAX__TEXT_CHARACTERS", "87654321",
            "SPRING_AI_PRIVACY_PROCESSING_MAX_TEXT_CHARACTERS", "8"
        )).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PrivacyService.class);
            assertThat(context.getBean(PrivacyGuardrailsProperties.class)
                .getProcessing()
                .getMaxTextCharacters()).isEqualTo(8);
            assertThat(output)
                .contains("Did you mean "
                    + "'spring.ai.privacy.processing.max-text-characters'?")
                .doesNotContain("87654321");
        });
    }

    @Test
    void doesNotWarnWhenRepeatedSeparatorStillBinds(CapturedOutput output) {
        withPrivacySystemEnvironment(Map.of(
            "SPRING_AI_PRIVACY_ANALYSIS__LANGUAGE", "ko"
        )).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PrivacyService.class);
            assertThat(context.getBean(PrivacyGuardrailsProperties.class).getAnalysis().getLanguage()).isEqualTo("ko");
            assertThat(output).doesNotContain(
                "Unrecognized Spring AI Privacy Guardrails configuration property"
            );
        });
    }

    @Test
    void warnsForDashedFixedPropertyTyposFromSystemEnvironmentNames(
            CapturedOutput output
    ) {
        withSystemEnvironment(Map.of(
            "SPRING_AI_PRIVACY_ANALYSIS_LANGUAGEEEE", "true",
            "SPRING_AI_PRIVACY_PROCESSING_MAX_TEXT_CHARACTERSS", "8",
            "SPRING_AI_PRIVACY_ANALYSIS_PRIMARY_PROVIDE", "synthetic-sensitive-value",
            "SPRING_AI_PRIVACY_RULES_ENABELD", "0"
        )).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
            assertThat(output)
                .contains("'spring.ai.privacy.processing.max-text-characters'")
                .contains("'spring.ai.privacy.analysis.primary-provider'")
                .contains("'spring.ai.privacy.rules.enabled'")
                .contains("'spring.ai.privacy.analysis.languageeee'")
                .contains("'spring.ai.privacy.analysis.language'")
                .doesNotContain(
                    "'spring.ai.privacy.processing.max-text-characterss'"
            )
                .doesNotContain(
                    "'spring.ai.privacy.analysis.primary-provide'"
            )
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
    void ignoresDynamicSystemEnvironmentSegmentsAndTheirValues(
            CapturedOutput output
    ) {
        withSystemEnvironment(Map.of(
                "SPRING_AI_PRIVACY_ANALYSIS_PROVIDER_MINIMUM_SCORES_CUSTOM_PROVIDER", "0.75",
                "SPRING_AI_PRIVACY_ANALYSIS_ENTITY_ALIASES_EXTERNAL_LABEL", "CUSTOMER_ID",
                "SPRING_AI_PRIVACY_PRESIDIO_HEADERS_AUTHORIZATION", "synthetic-secret-one",
                "SPRING_AI_PRIVACY_OPENNLP_ENTITY_MODELS_CUSTOMER_RECORD", "synthetic-secret-two"
        )).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
            assertThat(output)
                    .doesNotContain(
                            "Unrecognized Spring AI Privacy Guardrails configuration property"
                    )
                    .doesNotContain("custom-provider")
                    .doesNotContain("external-label")
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
            .withPropertyValues("spring.ai.privacy.analysis.languageeee=ko")
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                assertThat(output)
                    .contains("'spring.ai.privacy.analysis.languageeee'. Did you mean ")
                    .contains("'spring.ai.privacy.analysis.language'?")
                    .doesNotContain(ThrowingEnumerablePropertySource.SENSITIVE_MESSAGE);
            });
    }

    @Test
    void skipsOnlyAPropertySourceThatCannotBeAdapted(CapturedOutput output) {
        this.diagnosticsRunner
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new ThrowingAdaptationPropertySource()
            ))
            .withPropertyValues("spring.ai.privacy.analysis.languageeee=ko")
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                assertThat(output)
                    .contains("'spring.ai.privacy.analysis.languageeee'. Did you mean ")
                    .contains("'spring.ai.privacy.analysis.language'?")
                    .doesNotContain(ThrowingAdaptationPropertySource.SENSITIVE_MESSAGE);
            });
    }

    @Test
    void discardsPartiallyMappedSystemEnvironmentSource(CapturedOutput output) {
        SystemEnvironmentPropertySource partialSource = new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            Map.of("SPRING_AI_PRIVACY_ANALYSIS_LANGUAGEEEE", "synthetic-sensitive-value")) {
            @Override
            public String[] getPropertyNames() {
                return new String[] {"SPRING_AI_PRIVACY_ANALYSIS_LANGUAGEEEE", null};
            }
        };
        this.diagnosticsRunner
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(partialSource))
            .withPropertyValues("spring.ai.privacy.rules.enabledd=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(output)
                    .contains("'spring.ai.privacy.rules.enabledd'. Did you mean ")
                    .doesNotContain("spring.ai.privacy.analysis.languageeee")
                    .doesNotContain("synthetic-sensitive-value");
            });
    }

    @Test
    void skipsSystemEnvironmentSourcesWithAnApplicationPrefix(CapturedOutput output) {
        this.diagnosticsRunner
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new PrefixedSystemEnvironmentPropertySource(
                    "privacy-app",
                    Map.of(
                        "PRIVACYAPP_SPRING_AI_PRIVACY_ANALYSIS_LANGUAGEEEE",
                        "synthetic-sensitive-value"
                    )
                )
            ))
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(PrivacyService.class);
                assertThat(output)
                    .doesNotContain(
                        "Unrecognized Spring AI Privacy Guardrails "
                            + "configuration property"
                )
                    .doesNotContain("synthetic-sensitive-value");
            });
    }

    private ApplicationContextRunner withPrivacySystemEnvironment(Map<String, Object> environment) {
        return withSystemEnvironment(this.privacyRunner, environment);
    }

    private ApplicationContextRunner withSystemEnvironment(Map<String, Object> environment) {
        return withSystemEnvironment(this.diagnosticsRunner, environment);
    }

    private ApplicationContextRunner withSystemEnvironment(
            ApplicationContextRunner runner, Map<String, Object> environment) {
        return runner.withInitializer(context ->
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

    private static final class ThrowingAdaptationPropertySource
            extends EnumerablePropertySource<Object> {

        private static final String SENSITIVE_MESSAGE = "synthetic-adaptation-detail";

        private ThrowingAdaptationPropertySource() {
            super("throwing-adaptation-property-source", new Object());
        }

        @Override
        public Object getSource() {
            throw new IllegalStateException(SENSITIVE_MESSAGE);
        }

        @Override
        public String[] getPropertyNames() {
            return new String[] {"spring.ai.privacy.analysis.language"};
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

    private static final class PrefixedSystemEnvironmentPropertySource
            extends SystemEnvironmentPropertySource
            implements PropertySourceInfo {

        private final String prefix;

        private PrefixedSystemEnvironmentPropertySource(
                String prefix,
                Map<String, Object> environment
        ) {
            super(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment);
            this.prefix = prefix;
        }

        @Override
        public String getPrefix() {
            return this.prefix;
        }

    }

}

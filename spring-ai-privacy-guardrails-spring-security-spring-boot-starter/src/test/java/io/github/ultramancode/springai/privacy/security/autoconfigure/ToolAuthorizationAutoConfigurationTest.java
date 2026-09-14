package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.security.SpringSecurityToolBoundary;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolAuthorizationAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ToolAuthorizationAutoConfiguration.class,
                    ToolCallingAutoConfiguration.class,
                    ChatClientAutoConfiguration.class
            ))
            .withUserConfiguration(TestPolicyConfiguration.class)
            .withBean(ChatModel.class, () -> mock(ChatModel.class));

    @Test
    void preparesAuthorizationWithoutReplacingTheSpringAiManager() {
        this.contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SpringSecurityToolBoundary.class);
            assertThat(context).hasSingleBean(ToolCallingManager.class);
            assertThat(context).hasSingleBean(ToolAuthorizationChatClientFactory.class);
            assertThat(context).hasSingleBean(ToolCallingAdvisor.Builder.class);
            assertThat(context.getBean(ChatClient.Builder.class)).isNotNull();
            SpringSecurityToolBoundary boundary = context.getBean(SpringSecurityToolBoundary.class);
            assertThat(context.getBean(ToolCallingManager.class))
                    .isInstanceOf(DefaultToolCallingManager.class)
                    .isNotSameAs(boundary.toolCallingManager());
        });
    }

    @Test
    void createsDistinctBuildersForTheSameModel() {
        this.contextRunner.run(context -> {
            ToolAuthorizationChatClientFactory factory = context.getBean(ToolAuthorizationChatClientFactory.class);
            ChatModel model = context.getBean(ChatModel.class);

            ChatClient.Builder firstBuilder = factory.builder(model);
            ChatClient.Builder secondBuilder = factory.builder(model);

            assertThat(firstBuilder).isNotSameAs(secondBuilder);
        });
    }

    @Test
    void startsWithoutPrivacyModulesOnTheClasspath() {
        this.contextRunner
                .withClassLoader(new FilteredClassLoader(
                        "io.github.ultramancode.springai.privacy.autoconfigure",
                        "io.github.ultramancode.springai.privacy.core",
                        "io.github.ultramancode.springai.privacy.springai"
                ))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SpringSecurityToolBoundary.class);
                    assertThat(context).hasSingleBean(ToolAuthorizationChatClientFactory.class);
                    assertThat(context).doesNotHaveBean(PrivacySecurityChatClientFactory.class);
                });
    }

    @Test
    void createsClientsWhenChatClientAutoConfigurationIsDisabled() {
        this.contextRunner.withPropertyValues("spring.ai.chat.client.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ToolCallingAdvisor.Builder.class);
                    ToolAuthorizationChatClientFactory factory = context.getBean(
                            ToolAuthorizationChatClientFactory.class);

                    ChatClient client = factory.builder(context.getBean(ChatModel.class)).build();

                    assertThat(client).isNotNull();
                });
    }

    @Test
    void configuringAClientDoesNotMutateTheSharedToolAdvisorBuilder() {
        this.contextRunner.run(context -> {
            ToolCallingAdvisor.Builder<?> toolAdvisorBuilder = context.getBean(ToolCallingAdvisor.Builder.class);
            ToolCallingManager defaultManager = context.getBean(ToolCallingManager.class);

            context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(ChatModel.class));

            assertThat(ReflectionTestUtils.getField(toolAdvisorBuilder, "toolCallingManager")).isSameAs(defaultManager);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesTheDefaultToolCallingManager(boolean resolverFallbackEnabled) {
        this.contextRunner
                .withPropertyValues(
                        "spring.ai.tools.resolution.fallback.enabled=" + resolverFallbackEnabled
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SpringSecurityToolBoundary boundary = context.getBean(
                            SpringSecurityToolBoundary.class
                    );
                    assertThat(context.getBean(ToolCallingManager.class))
                            .isNotSameAs(boundary.toolCallingManager());
                    assertThat(context.getBeansOfType(ToolCallingManager.class).values())
                            .filteredOn(DefaultToolCallingManager.class::isInstance)
                            .hasSize(1);
                });
    }

    @Test
    void findsTheDefaultManagerByTypeWhenItsBeanNameDiffers() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolAuthorizationAutoConfiguration.class,
                        ChatClientAutoConfiguration.class
                ))
                .withUserConfiguration(
                        TestPolicyConfiguration.class,
                        DefaultManagerWithCustomBeanNameConfiguration.class
                )
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SpringSecurityToolBoundary boundary = context.getBean(
                            SpringSecurityToolBoundary.class
                    );
                    assertThat(context.getBean(ToolCallingManager.class))
                            .isNotSameAs(boundary.toolCallingManager());
                    assertThat(context.getBean(
                            "customNamedDefaultManager",
                            ToolCallingManager.class
                    )).isInstanceOf(DefaultToolCallingManager.class);
                });
    }

    @Test
    void failsStartupInsteadOfSilentlyUsingACustomManager() {
        ToolCallingManager customManager = mock(ToolCallingManager.class);
        this.contextRunner
                .withBean("customToolCallingManager", ToolCallingManager.class, () -> customManager)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining(
                                    "Tool authorization auto-configuration requires a DefaultToolCallingManager bean"
                            );
                });
    }

    @Test
    void acceptsACustomManagerOnlyWithAnExplicitBoundary() {
        ToolCallingManager customManager = mock(ToolCallingManager.class);
        AuthorizationManager<ToolAuthorizationContext> policy =
                (authentication, context) -> new AuthorizationDecision(true);
        SpringSecurityToolBoundary explicitBoundary = SpringSecurityToolBoundary
                .builder(customManager, policy)
                .build();

        this.contextRunner
                .withBean("customToolCallingManager", ToolCallingManager.class, () -> customManager)
                .withBean(SpringSecurityToolBoundary.class, () -> explicitBoundary)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ToolCallingManager.class))
                            .isSameAs(customManager)
                            .isNotSameAs(explicitBoundary.toolCallingManager());
                });
    }

    @Test
    void preservesTheDefaultManagerInTheOpenAiModelAndToolAdvisorBuilder() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolAuthorizationAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class,
                        OpenAiChatAutoConfiguration.class,
                        ChatClientAutoConfiguration.class
                ))
                .withUserConfiguration(TestPolicyConfiguration.class)
                .withPropertyValues(
                        "spring.ai.openai.api-key=test-api-key"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SpringSecurityToolBoundary boundary = context.getBean(
                            SpringSecurityToolBoundary.class
                    );
                    ToolCallingManager defaultManager = context.getBean(ToolCallingManager.class);
                    assertThat(defaultManager).isNotSameAs(boundary.toolCallingManager());
                    OpenAiChatModel chatModel = context.getBean(OpenAiChatModel.class);
                    ToolCallingAdvisor.Builder<?> advisorBuilder = context.getBean(
                            ToolCallingAdvisor.Builder.class
                    );

                    assertThat(ReflectionTestUtils.getField(chatModel, "toolCallingManager"))
                            .isSameAs(defaultManager);
                    assertThat(ReflectionTestUtils.getField(advisorBuilder, "toolCallingManager"))
                            .isSameAs(defaultManager);
                });
    }

    @Test
    void preservesTheDefaultManagerInTheAnthropicModel() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolAuthorizationAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class,
                        AnthropicChatAutoConfiguration.class,
                        ChatClientAutoConfiguration.class
                ))
                .withUserConfiguration(TestPolicyConfiguration.class)
                .withPropertyValues(
                        "spring.ai.anthropic.api-key=test-api-key"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AnthropicChatModel.class);
                    SpringSecurityToolBoundary boundary = context.getBean(
                            SpringSecurityToolBoundary.class
                    );
                    assertThat(context.getBean(ToolCallingManager.class))
                            .isNotSameAs(boundary.toolCallingManager());
                    assertThat(ReflectionTestUtils.getField(
                            context.getBean(AnthropicChatModel.class), "toolCallingManager"))
                            .isSameAs(context.getBean(ToolCallingManager.class));
                });
    }

    @Test
    void startsWithoutAuthorizationInfrastructureWhenNoToolPolicyExists() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolAuthorizationAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SpringSecurityToolBoundary.class);
                    assertThat(context).doesNotHaveBean(ToolAuthorizationChatClientFactory.class);
                });
    }

    @Test
    void unrelatedAuthorizationManagerDoesNotActivateToolAuthorization() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolAuthorizationAutoConfiguration.class))
                .withUserConfiguration(UnrelatedPolicyConfiguration.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(SpringSecurityToolBoundary.class)
                        .doesNotHaveBean(ToolAuthorizationChatClientFactory.class));
    }

    @Test
    void explicitBoundaryPreparesFactoryWithoutASeparatePolicyOrDefaultManager() {
        SpringSecurityToolBoundary boundary = SpringSecurityToolBoundary.builder(
                mock(ToolCallingManager.class),
                (authentication, context) -> new AuthorizationDecision(true)).build();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolAuthorizationAutoConfiguration.class))
                .withBean(SpringSecurityToolBoundary.class, () -> boundary)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(ToolAuthorizationChatClientFactory.class));
    }

    @ParameterizedTest
    @ValueSource(classes = {
            NonAutowireCandidatePolicyConfiguration.class,
            NonDefaultCandidatePolicyConfiguration.class,
            NonAutowireCandidateScopedProxyPolicyConfiguration.class,
            NonDefaultCandidateScopedProxyPolicyConfiguration.class
    })
    void ignoresToolPoliciesExcludedFromInjection(Class<?> policyConfiguration) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolAuthorizationAutoConfiguration.class, ToolCallingAutoConfiguration.class))
                .withUserConfiguration(policyConfiguration)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(SpringSecurityToolBoundary.class)
                        .doesNotHaveBean(ToolAuthorizationChatClientFactory.class));
    }

    @ParameterizedTest
    @ValueSource(classes = {
            NonAutowireCandidatePolicyConfiguration.class,
            NonDefaultCandidatePolicyConfiguration.class,
            NonAutowireCandidateScopedProxyPolicyConfiguration.class,
            NonDefaultCandidateScopedProxyPolicyConfiguration.class
    })
    void ignoresParentToolPoliciesExcludedFromInjection(Class<?> policyConfiguration) {
        new ApplicationContextRunner().withUserConfiguration(policyConfiguration).run(parent ->
                new ApplicationContextRunner()
                        .withParent(parent)
                        .withConfiguration(AutoConfigurations.of(
                                ToolAuthorizationAutoConfiguration.class, ToolCallingAutoConfiguration.class))
                        .run(context -> assertThat(context)
                                .hasNotFailed()
                                .doesNotHaveBean(SpringSecurityToolBoundary.class)
                                .doesNotHaveBean(ToolAuthorizationChatClientFactory.class)));
    }

    @Test
    void preparesAuthorizationFromParentPolicyAndManagerBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .withUserConfiguration(TestPolicyConfiguration.class)
                .run(parent -> new ApplicationContextRunner()
                        .withParent(parent)
                        .withConfiguration(AutoConfigurations.of(
                                ToolAuthorizationAutoConfiguration.class, ToolCallingAutoConfiguration.class))
                        .run(context -> assertThat(context)
                                .hasNotFailed()
                                .hasSingleBean(SpringSecurityToolBoundary.class)
                                .hasSingleBean(ToolAuthorizationChatClientFactory.class)));
    }

    @Test
    void preparesAuthorizationFromParentPolicyWithManagerInCurrentContext() {
        new ApplicationContextRunner().withUserConfiguration(TestPolicyConfiguration.class).run(parent ->
                new ApplicationContextRunner()
                        .withParent(parent)
                        .withConfiguration(AutoConfigurations.of(
                                ToolAuthorizationAutoConfiguration.class, ToolCallingAutoConfiguration.class))
                        .run(context -> assertThat(context)
                                .hasNotFailed()
                                .hasSingleBean(SpringSecurityToolBoundary.class)
                                .hasSingleBean(ToolAuthorizationChatClientFactory.class)));
    }

    @Test
    void preparesAuthorizationFromAScopedProxyPolicy() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolAuthorizationAutoConfiguration.class, ToolCallingAutoConfiguration.class))
                .withUserConfiguration(ScopedProxyPolicyConfiguration.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(SpringSecurityToolBoundary.class)
                        .hasSingleBean(ToolAuthorizationChatClientFactory.class));
    }

    @Test
    void preparesAuthorizationFromAParentScopedProxyPolicy() {
        new ApplicationContextRunner().withUserConfiguration(ScopedProxyPolicyConfiguration.class).run(parent ->
                new ApplicationContextRunner()
                        .withParent(parent)
                        .withConfiguration(AutoConfigurations.of(
                                ToolAuthorizationAutoConfiguration.class, ToolCallingAutoConfiguration.class))
                        .run(context -> assertThat(context)
                                .hasNotFailed()
                                .hasSingleBean(SpringSecurityToolBoundary.class)
                                .hasSingleBean(ToolAuthorizationChatClientFactory.class)));
    }

    @Configuration(proxyBeanMethods = false)
    static class ScopedProxyPolicyConfiguration {

        @Bean
        @Scope(value = "prototype", proxyMode = ScopedProxyMode.INTERFACES)
        AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
            return (authentication, context) -> new AuthorizationDecision(true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonAutowireCandidateScopedProxyPolicyConfiguration {

        @Bean(autowireCandidate = false)
        @Scope(value = "prototype", proxyMode = ScopedProxyMode.INTERFACES)
        AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
            return (authentication, context) -> new AuthorizationDecision(true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonDefaultCandidateScopedProxyPolicyConfiguration {

        @Bean(defaultCandidate = false)
        @Scope(value = "prototype", proxyMode = ScopedProxyMode.INTERFACES)
        AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
            return (authentication, context) -> new AuthorizationDecision(true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonAutowireCandidatePolicyConfiguration {

        @Bean(autowireCandidate = false)
        AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
            return (authentication, context) -> new AuthorizationDecision(true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonDefaultCandidatePolicyConfiguration {

        @Bean(defaultCandidate = false)
        AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
            return (authentication, context) -> new AuthorizationDecision(true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnrelatedPolicyConfiguration {

        @Bean
        AuthorizationManager<String> unrelatedAuthorizationManager() {
            return (authentication, context) -> new AuthorizationDecision(true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestPolicyConfiguration {

        @Bean
        AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
            return (authentication, context) -> new AuthorizationDecision(true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DefaultManagerWithCustomBeanNameConfiguration {

        @Bean("customNamedDefaultManager")
        ToolCallingManager customNamedDefaultManager() {
            return ToolCallingManager.builder().build();
        }
    }
}

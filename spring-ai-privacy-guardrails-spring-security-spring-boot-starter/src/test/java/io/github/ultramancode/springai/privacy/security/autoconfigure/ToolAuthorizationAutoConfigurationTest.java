package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.security.SpringSecurityToolBoundary;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import org.junit.jupiter.api.Test;
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
            .withBean(ChatModel.class, () -> mock(ChatModel.class))
            .withPropertyValues("spring.ai.privacy.security.enabled=true");

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
            assertThat(context.getBeansOfType(ToolCallingManager.class).values())
                    .filteredOn(DefaultToolCallingManager.class::isInstance)
                    .singleElement()
                    .isNotSameAs(boundary.toolCallingManager());
        });
    }

    @Test
    void createsIndependentBuildersForTheSameModel() {
        this.contextRunner.run(context -> {
            ToolAuthorizationChatClientFactory factory = context.getBean(ToolAuthorizationChatClientFactory.class);
            ChatModel model = context.getBean(ChatModel.class);
            assertThat(factory.builder(model)).isNotSameAs(factory.builder(model));
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
    void supportsManuallyBuiltClientsWhenChatClientAutoConfigurationIsDisabled() {
        this.contextRunner.withPropertyValues("spring.ai.chat.client.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ToolCallingAdvisor.Builder.class);
                    ToolAuthorizationChatClientFactory factory = context.getBean(
                            ToolAuthorizationChatClientFactory.class);
                    assertThat(factory.builder(context.getBean(ChatModel.class)).build()).isNotNull();
                });
    }

    @Test
    void configuringAClientDoesNotMutateTheSharedToolAdvisorBuilder() {
        this.contextRunner.run(context -> {
            ToolCallingAdvisor.Builder<?> toolAdvisorBuilder = context.getBean(ToolCallingAdvisor.Builder.class);
            ToolCallingManager upstreamManager = context.getBean(ToolCallingManager.class);

            context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(ChatModel.class));

            assertThat(ReflectionTestUtils.getField(toolAdvisorBuilder, "toolCallingManager")).isSameAs(upstreamManager);
        });
    }

    @Test
    void leavesTheApplicationManagerUnchangedForBothResolverFallbackSettings() {
        for (boolean enabled : new boolean[]{false, true}) {
            this.contextRunner
                    .withPropertyValues(
                            "spring.ai.tools.resolution.fallback.enabled=" + enabled
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
                        RenamedDefaultManagerConfiguration.class
                )
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .withPropertyValues("spring.ai.privacy.security.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SpringSecurityToolBoundary boundary = context.getBean(
                            SpringSecurityToolBoundary.class
                    );
                    assertThat(context.getBean(ToolCallingManager.class))
                            .isNotSameAs(boundary.toolCallingManager());
                    assertThat(context.getBean(
                            "renamedUpstreamManager",
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
                                    "Tool authorization requires Spring AI's auto-configured"
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
    void leavesTheActualOpenAiModelAndDefaultToolAdvisorOnTheUpstreamManager() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolAuthorizationAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class,
                        OpenAiChatAutoConfiguration.class,
                        ChatClientAutoConfiguration.class
                ))
                .withUserConfiguration(TestPolicyConfiguration.class)
                .withPropertyValues(
                        "spring.ai.openai.api-key=test-api-key",
                        "spring.ai.privacy.security.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SpringSecurityToolBoundary boundary = context.getBean(
                            SpringSecurityToolBoundary.class
                    );
                    ToolCallingManager upstreamManager = context.getBean(ToolCallingManager.class);
                    assertThat(upstreamManager).isNotSameAs(boundary.toolCallingManager());
                    OpenAiChatModel chatModel = context.getBean(OpenAiChatModel.class);
                    ToolCallingAdvisor.Builder<?> advisorBuilder = context.getBean(
                            ToolCallingAdvisor.Builder.class
                    );

                    assertThat(ReflectionTestUtils.getField(chatModel, "toolCallingManager"))
                            .isSameAs(upstreamManager);
                    assertThat(ReflectionTestUtils.getField(advisorBuilder, "toolCallingManager"))
                            .isSameAs(upstreamManager);
                });
    }

    @Test
    void leavesTheActualAnthropicModelOnTheUpstreamManager() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolAuthorizationAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class,
                        AnthropicChatAutoConfiguration.class,
                        ChatClientAutoConfiguration.class
                ))
                .withUserConfiguration(TestPolicyConfiguration.class)
                .withPropertyValues(
                        "spring.ai.anthropic.api-key=test-api-key",
                        "spring.ai.privacy.security.enabled=true"
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
    void remainsInactiveUnlessExplicitlyEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolAuthorizationAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SpringSecurityToolBoundary.class);
                    assertThat(context).doesNotHaveBean(ToolAuthorizationChatClientFactory.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestPolicyConfiguration {

        @Bean
        AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
            return (authentication, context) -> new AuthorizationDecision(true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RenamedDefaultManagerConfiguration {

        @Bean("renamedUpstreamManager")
        ToolCallingManager renamedUpstreamManager() {
            return ToolCallingManager.builder().build();
        }
    }
}

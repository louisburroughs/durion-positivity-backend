package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * #1691: with failover enabled the primary executor beans are the failover wrapper, so every
 * caller that injects {@code chatModel} or {@code streamingChatModel} gets primary-to-secondary
 * failover without further wiring. Building the Ollama beans opens no connection.
 */
class ModelFallbackWiringTest {

    // The Ollama configuration binds "180s" into Duration parameters; a bare runner has no converter
    // for that, so Boot's conversion service is registered under the name @Value conversion uses.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(
                    "conversionService",
                    org.springframework.boot.convert.ApplicationConversionService.class,
                    org.springframework.boot.convert.ApplicationConversionService::new)
            .withUserConfiguration(OllamaChatModelConfiguration.class, ModelFallbackConfiguration.class);

    @Test
    @DisplayName("failover enabled: chatModel and streamingChatModel are wrapped, the secondary is not")
    void enabled_wrapsThePrimaryBeans() {
        runner.withPropertyValues(
                        "mcp.model.fallback.enabled=true",
                        "mcp.model.fallback.secondary-model-name=deepseek-v4-pro:0813")
                .run(ctx -> {
                    assertThat(ctx.getBean("chatModel", ChatModel.class))
                            .isInstanceOfSatisfying(
                                    FailoverChatModel.class,
                                    wrapper -> assertThat(wrapper.secondaryModelName())
                                            .isEqualTo("deepseek-v4-pro:0813"));
                    assertThat(ctx.getBean("streamingChatModel", StreamingChatModel.class))
                            .isInstanceOf(FailoverChatModel.class);
                    assertThat(ctx.getBean("fallbackChatModel", ChatModel.class))
                            .isNotInstanceOf(FailoverChatModel.class);
                });
    }

    @Test
    @DisplayName("failover disabled (the shipped default): the primary beans are untouched and no secondary exists")
    void disabled_leavesThePrimaryBeansAlone() {
        runner.run(ctx -> {
            assertThat(ctx.getBean("chatModel", ChatModel.class)).isNotInstanceOf(FailoverChatModel.class);
            assertThat(ctx.containsBean("fallbackChatModel")).isFalse();
        });
    }
}

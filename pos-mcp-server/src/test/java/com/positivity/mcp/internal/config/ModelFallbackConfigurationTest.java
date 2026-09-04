package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * Unit tests for {@link ModelFallbackConfiguration}.
 *
 * <p>
 * The configuration class is instantiated directly to verify the factory
 * method without requiring a Spring application context boot or a live Ollama
 * server. {@code OllamaChatModel.builder().build()} configures a RestClient
 * but does not open any network connections at construction time.
 */
class ModelFallbackConfigurationTest {

    private final ModelFallbackConfiguration configuration = new ModelFallbackConfiguration();

    @Test
    @DisplayName("fallbackChatModel returns a non-null ChatModel when called with valid parameters")
    void fallbackChatModel_isConfigured_whenCalled() {
        ChatModel model = configuration.fallbackChatModel(
                "http://localhost:11434", "mistral:7b", "", Duration.ofMinutes(3), 0.0d, 32768);

        assertThat(model).isNotNull();
    }

    @Test
    @DisplayName("fallbackChatModel returns a distinct instance per invocation")
    void fallbackChatModel_returnsDistinctInstances() {
        ChatModel first = configuration.fallbackChatModel(
                "http://localhost:11434", "mistral:7b", "", Duration.ofMinutes(3), 0.0d, 32768);
        ChatModel second = configuration.fallbackChatModel(
                "http://localhost:11434", "llama3:8b", "", Duration.ofMinutes(3), 0.0d, 32768);

        assertThat(first).isNotSameAs(second);
    }

    /**
     * #1683: the secondary model inherits the primary's temperature and context window, so failover
     * cannot silently change determinism or truncate the prompt. Asserted on the built options
     * because deleting {@code .numCtx(numCtx)} from the bean otherwise breaks nothing — the two
     * parameters were threaded through the call sites with no assertion behind them.
     *
     * <p>The bean is currently unreachable ({@code docs/alerts/nlti-alerts.md} records that
     * {@code fallbackChatModel} is never injected into a call path), so this guards the wiring
     * against the day it is, not behaviour in production today.
     */
    @Test
    @DisplayName("fallbackChatModel inherits the primary's temperature and context window")
    void fallbackChatModel_inheritsDeterminismAndContextWindow() {
        ChatModel model = configuration.fallbackChatModel(
                "http://localhost:11434", "mistral:7b", "", Duration.ofMinutes(3), 0.0d, 32768);

        assertThat(model.getOptions()).isInstanceOf(OllamaChatOptions.class);
        OllamaChatOptions options = (OllamaChatOptions) model.getOptions();
        assertThat(options.getNumCtx()).isEqualTo(32768);
        assertThat(options.getTemperature()).isEqualTo(0.0d);
    }
}

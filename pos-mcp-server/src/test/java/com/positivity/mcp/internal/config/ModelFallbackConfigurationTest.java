package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

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
}

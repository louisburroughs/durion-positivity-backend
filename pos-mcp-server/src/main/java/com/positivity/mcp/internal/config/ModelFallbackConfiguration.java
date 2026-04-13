package com.positivity.mcp.internal.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "mcp.model.fallback.enabled", havingValue = "true")
public class ModelFallbackConfiguration {

  @Bean("fallbackChatModel")
  public @NonNull ChatModel fallbackChatModel(
      @Value("${OLLAMA_BASE_URL:http://localhost:11434}") @NonNull String baseUrl,
      @Value("${mcp.model.fallback.secondary-model-name:mistral:7b}") @NonNull String modelName) {
    return OllamaChatModel.builder()
        .baseUrl(baseUrl)
        .modelName(modelName)
        .temperature(0.2)
        .timeout(Duration.ofSeconds(60))
        .build();
  }
}
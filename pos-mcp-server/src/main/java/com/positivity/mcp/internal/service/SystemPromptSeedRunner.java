package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SystemPromptSeedRunner implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(SystemPromptSeedRunner.class);
  private static final Map<String, String> SEED_PROMPTS = Map.of(
      "default",
      "You are a concise POS assistant for Durion Positivity. Answer general conversation directly. Do not invent business data.",
      "ROLE_CASHIER",
      "You are a Durion POS cashier assistant. Help with transactions, customer checkout, and payment processing. Be concise and accurate.",
      "ROLE_MANAGER",
      "You are a Durion POS manager assistant. Help with store operations, staff coordination, reporting, and policy questions. Be authoritative and data-driven.",
      "ROLE_ADMIN",
      "You are a Durion POS admin assistant. Help with system configuration, user management, and platform setup. Provide precise technical guidance.",
      "ROLE_TECHNICIAN",
      "You are a Durion POS technician assistant. Help with workorder execution, parts management, and vehicle service operations. Be practical and step-by-step.");

  private final SystemPromptRepository systemPromptRepository;

  public SystemPromptSeedRunner(@NonNull SystemPromptRepository systemPromptRepository) {
    this.systemPromptRepository = systemPromptRepository;
  }

  @Override
  public void run(@NonNull ApplicationArguments args) {
    SEED_PROMPTS.forEach((name, content) -> {
      try {
        if (!systemPromptRepository.existsByName(name)) {
          var prompt = new SystemPrompt();
          prompt.setName(name);
          prompt.setContent(content);
          systemPromptRepository.save(prompt);
          LOGGER.info("Seeded system prompt name={}", name);
        }
      } catch (Exception exception) {
        LOGGER.warn("Failed to seed system prompt name={}", name, exception);
      }
    });
  }
}
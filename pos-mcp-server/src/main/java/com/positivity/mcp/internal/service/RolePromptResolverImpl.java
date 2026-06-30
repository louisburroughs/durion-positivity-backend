package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import com.positivity.mcp.service.RolePromptResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RolePromptResolverImpl implements RolePromptResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(RolePromptResolverImpl.class);
    private static final String MASTER_PROMPT_NAME = SystemPromptDefaults.MASTER_PROMPT_NAME;
    private static final String BUILT_IN_PROMPT = SystemPromptDefaults.DEFAULT_PROMPT_TEXT;

    private final SystemPromptRepository systemPromptRepository;

    public RolePromptResolverImpl(@NonNull SystemPromptRepository systemPromptRepository) {
        this.systemPromptRepository = systemPromptRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull String resolvePrompt(@NonNull String promptName) {
        return systemPromptRepository
                .findByName(promptName)
                .map(SystemPrompt::getContent)
                .or(() -> {
                    LOGGER.warn(
                            "MCP no agent system prompt found promptName={}; falling back to master prompt",
                            promptName);
                    return systemPromptRepository.findByName(MASTER_PROMPT_NAME).map(SystemPrompt::getContent);
                })
                .orElseGet(() -> {
                    LOGGER.warn(
                            "MCP no system prompt found promptName={} name={}; using built-in prompt",
                            promptName,
                            MASTER_PROMPT_NAME);
                    return BUILT_IN_PROMPT;
                });
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull AssembledPrompt assemble(@NonNull String role, @NonNull String ragScope) {
        List<String> layers = new ArrayList<>(4);
        StringBuilder text = new StringBuilder();

        // BASE — master operating rules (built-in fallback if unseeded).
        String base = systemPromptRepository
                .findByName(MASTER_PROMPT_NAME)
                .map(SystemPrompt::getContent)
                .orElse(BUILT_IN_PROMPT);
        text.append(base);
        layers.add("BASE");

        // ROLE — persona overlay, resolved by the caller's role (role-first). Persona only.
        Optional<String> rolePersona = systemPromptRepository.findByName(role).map(SystemPrompt::getContent);
        if (rolePersona.isPresent()) {
            text.append("\n\n").append(rolePersona.get());
            layers.add("ROLE");
        } else {
            LOGGER.warn("MCP no role persona seeded role={}; assembling without ROLE layer", role);
        }

        // DOMAIN — existing domain prompt, keyed by RAG scope. Skipped for master/shared scope.
        String domainPromptName = SystemPromptDefaults.promptNameForRagScope(ragScope);
        if (!MASTER_PROMPT_NAME.equals(domainPromptName)) {
            Optional<String> domain =
                    systemPromptRepository.findByName(domainPromptName).map(SystemPrompt::getContent);
            if (domain.isPresent()) {
                text.append("\n\n").append(domain.get());
                layers.add("DOMAIN");
            }
        }

        // TOOL-USE — argument-grounding contract, always present.
        text.append("\n\n").append(SystemPromptDefaults.TOOL_USE_LAYER_TEXT);
        layers.add("TOOL_USE");

        return new AssembledPrompt(text.toString(), List.copyOf(layers));
    }
}

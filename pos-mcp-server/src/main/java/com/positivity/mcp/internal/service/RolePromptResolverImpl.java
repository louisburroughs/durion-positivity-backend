package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import com.positivity.mcp.service.RolePromptResolver;
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
                            "MCP no agent system prompt found promptName={}; falling back to master prompt", promptName);
                    return systemPromptRepository
                            .findByName(MASTER_PROMPT_NAME)
                            .map(SystemPrompt::getContent);
                })
                .orElseGet(() -> {
                    LOGGER.warn(
                            "MCP no system prompt found promptName={} name={}; using built-in prompt",
                            promptName,
                            MASTER_PROMPT_NAME);
                    return BUILT_IN_PROMPT;
                });
    }
}

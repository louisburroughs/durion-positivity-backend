package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a single {@code system_prompts} row in its own transaction (#1613).
 *
 * <p>A separate bean, not a private method on {@link RolePersonaRefresher}, for two reasons that
 * both come down to transaction boundaries.
 *
 * <p>{@code REQUIRES_NEW} is load-bearing: the on-miss fetch runs inside
 * {@code RolePromptResolverImpl.assemble}, which is {@code @Transactional(readOnly = true)}. A save
 * on that transaction would not flush at all under Hibernate's read-only flush mode, so the persona
 * would be resolved for the current request and silently never persisted — every later request would
 * miss and re-fetch it. Suspending into a new transaction is what makes the write actually land.
 *
 * <p>And it has to be a different bean: Spring's transaction proxy is bypassed by self-invocation,
 * so a {@code @Transactional} method called from a sibling method of the same class does nothing at
 * all — the annotation would look right and have no effect.
 *
 * <p>Per row rather than per batch, so one bad persona cannot roll back the rest of a sync.
 */
@Component
public class SystemPromptWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemPromptWriter.class);

    private final SystemPromptRepository systemPromptRepository;

    public SystemPromptWriter(@NonNull SystemPromptRepository systemPromptRepository) {
        this.systemPromptRepository = systemPromptRepository;
    }

    /** Fail-soft: a row that cannot be written is logged, never propagated to the caller. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(@NonNull String name, @NonNull String content) {
        try {
            Optional<SystemPrompt> existing = systemPromptRepository.findByName(name);
            if (existing.isPresent()) {
                SystemPrompt prompt = existing.get();
                if (!content.equals(prompt.getContent())) {
                    prompt.setContent(content);
                    systemPromptRepository.save(prompt);
                    LOGGER.info("Updated role persona prompt name={}", name);
                }
                return;
            }

            SystemPrompt prompt = new SystemPrompt();
            prompt.setName(name);
            prompt.setContent(content);
            systemPromptRepository.save(prompt);
            LOGGER.info("Seeded role persona prompt name={}", name);
        } catch (Exception exception) {
            LOGGER.warn("Failed to persist role persona prompt name={}", name, exception);
        }
    }
}

package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import io.micrometer.core.instrument.MeterRegistry;
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

    /** Incremented whenever prompt resolution falls back past the requested prompt (#639). */
    static final String METRIC_PROMPT_FALLBACK = "mcp.prompt.fallback";

    static final String REASON_MASTER_PROMPT = "master-prompt";
    static final String REASON_BUILT_IN = "built-in";
    static final String REASON_MISSING_ROLE_LAYER = "missing-role-layer";

    private final SystemPromptRepository systemPromptRepository;
    private final MeterRegistry meterRegistry;

    public RolePromptResolverImpl(
            @NonNull SystemPromptRepository systemPromptRepository, @NonNull MeterRegistry meterRegistry) {
        this.systemPromptRepository = systemPromptRepository;
        this.meterRegistry = meterRegistry;
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
                    recordFallback(REASON_MASTER_PROMPT, promptName);
                    return systemPromptRepository.findByName(MASTER_PROMPT_NAME).map(SystemPrompt::getContent);
                })
                .orElseGet(() -> {
                    LOGGER.warn(
                            "MCP no system prompt found promptName={} name={}; using built-in prompt",
                            promptName,
                            MASTER_PROMPT_NAME);
                    recordFallback(REASON_BUILT_IN, promptName);
                    return BUILT_IN_PROMPT;
                });
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull AssembledPrompt assemble(
            @NonNull String role, @NonNull String ragScope, boolean writeCapableToolsPresent) {
        List<String> layers = new ArrayList<>(5);
        StringBuilder text = new StringBuilder();

        // BASE — master operating rules (built-in fallback if unseeded).
        Optional<String> base =
                systemPromptRepository.findByName(MASTER_PROMPT_NAME).map(SystemPrompt::getContent);
        if (base.isEmpty()) {
            LOGGER.warn("MCP no master prompt seeded name={}; using built-in BASE layer", MASTER_PROMPT_NAME);
            recordFallback(REASON_BUILT_IN, MASTER_PROMPT_NAME);
        }
        text.append(base.orElse(BUILT_IN_PROMPT));
        layers.add("BASE");

        // ROLE — persona overlay, resolved by the caller's role (role-first). Persona only.
        Optional<String> rolePersona = systemPromptRepository.findByName(role).map(SystemPrompt::getContent);
        if (rolePersona.isPresent()) {
            text.append("\n\n").append(rolePersona.get());
            layers.add("ROLE");
        } else {
            LOGGER.warn("MCP no role persona seeded role={}; assembling without ROLE layer", role);
            recordFallback(REASON_MISSING_ROLE_LAYER, role);
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

        // WRITE-GATE (Gate 6, #1193) — only when a write-capable tool is in the candidate set.
        if (writeCapableToolsPresent) {
            text.append("\n\n").append(SystemPromptDefaults.WRITE_GATE_LAYER_TEXT);
            layers.add("WRITE_GATE");
        }

        return new AssembledPrompt(text.toString(), List.copyOf(layers));
    }

    /**
     * Counts a prompt-resolution fallback. {@code requested} is the prompt/role name that missed;
     * both tag values come from the bounded role/domain prompt-name sets, so cardinality stays low.
     */
    private void recordFallback(@NonNull String reason, @NonNull String requested) {
        meterRegistry
                .counter(METRIC_PROMPT_FALLBACK, "reason", reason, "requested", requested)
                .increment();
    }
}

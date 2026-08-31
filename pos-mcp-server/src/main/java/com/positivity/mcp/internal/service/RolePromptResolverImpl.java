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

    /**
     * #1613 splits the old {@code missing-role-layer} reason in two, because it conflated a designed
     * state with a defect and so could not be alerted on.
     *
     * <p>{@code persona-ineligible} is a role deliberately excluded from persona resolution: expected,
     * permanent, and not a fault. It was the majority of the old counter's volume — every
     * external-facing request from a CUSTOMER or SELF_SERVICE_CUSTOMER caller produced one.
     */
    static final String REASON_PERSONA_INELIGIBLE = "persona-ineligible";

    /**
     * A role the sync has never delivered, after an on-miss fetch also failed to find it. This is the
     * one that should alert: it means a role exists upstream that this service cannot see.
     */
    static final String REASON_UNKNOWN_ROLE = "unknown-role";

    private final SystemPromptRepository systemPromptRepository;
    private final MeterRegistry meterRegistry;
    private final RolePersonaSnapshotHolder snapshotHolder;
    private final RolePersonaRefresher personaRefresher;

    public RolePromptResolverImpl(
            @NonNull SystemPromptRepository systemPromptRepository,
            @NonNull MeterRegistry meterRegistry,
            @NonNull RolePersonaSnapshotHolder snapshotHolder,
            @NonNull RolePersonaRefresher personaRefresher) {
        this.systemPromptRepository = systemPromptRepository;
        this.meterRegistry = meterRegistry;
        this.snapshotHolder = snapshotHolder;
        this.personaRefresher = personaRefresher;
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
        Optional<String> rolePersona = resolveRolePersona(role);
        if (rolePersona.isPresent()) {
            text.append("\n\n").append(rolePersona.get());
            layers.add("ROLE");
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
     * The ROLE layer for a caller's role, empty when there is none to assemble.
     *
     * <p>A miss is not automatically a fault, which is why this does more than one lookup (#1613):
     *
     * <ol>
     *   <li>the persisted row, the normal path and the one that keeps working while sync is down;
     *   <li>the eligibility flag — a role excluded by design has no persona and never will, so
     *       fetching it would be pointless and counting it as a failure is misleading;
     *   <li>a single-role fetch, which is what lets a role created after boot work without a restart.
     * </ol>
     *
     * <p>Only after the fetch also fails is this a real sync gap.
     */
    private @NonNull Optional<String> resolveRolePersona(@NonNull String role) {
        Optional<String> persisted = systemPromptRepository.findByName(role).map(SystemPrompt::getContent);
        if (persisted.isPresent()) {
            return persisted;
        }

        if (snapshotHolder.get().isIneligible(role)) {
            LOGGER.debug("MCP role excluded from persona resolution role={}; assembling without ROLE layer", role);
            recordFallback(REASON_PERSONA_INELIGIBLE, role);
            return Optional.empty();
        }

        if (personaRefresher.refreshRole(role)) {
            Optional<String> fetched = snapshotHolder.get().personaText(role);
            if (fetched.isPresent()) {
                LOGGER.info("MCP role persona resolved by on-miss fetch role={}", role);
                return fetched;
            }
            // The fetch succeeded and told us the role is not persona-eligible.
            recordFallback(REASON_PERSONA_INELIGIBLE, role);
            return Optional.empty();
        }

        LOGGER.warn("MCP no role persona for role={} and on-miss fetch failed; assembling without ROLE layer", role);
        recordFallback(REASON_UNKNOWN_ROLE, role);
        return Optional.empty();
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

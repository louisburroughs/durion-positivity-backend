package com.positivity.mcp.internal.config;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-0069 §9: rollout switches and caps for the scope graph.
 *
 * <p>{@code mode} is one value for the whole graph; {@code enforce} names the consumers that act on
 * the scope once the mode is {@code enforce}, because §9 promotes each consumer separately and one
 * mode value cannot express that. {@code mode: enforce} with an empty list behaves as {@code shadow}.
 *
 * <p>A bare {@code off} in YAML is the boolean {@code false}, not the string, so the YAML files
 * write the mode quoted or through a placeholder; {@link Mode} is bound from the string.
 *
 * @param mode {@code off} (nothing is built or resolved), {@code shadow} or {@code enforce}
 * @param enforce consumers that act when {@code mode} is {@code enforce}
 * @param maxNodes §5.2 cap on the expanded scope
 * @param addedToolSlots §6 cap on scope-added tools, facade and discovered together
 * @param cardTokenBudget §7 cap on the scope card
 */
@ConfigurationProperties(prefix = "mcp.scope-graph")
public record ScopeGraphProperties(
        @Nullable Mode mode, @Nullable List<Consumer> enforce, int maxNodes, int addedToolSlots, int cardTokenBudget) {

    private static final int DEFAULT_MAX_NODES = 60;
    private static final int DEFAULT_ADDED_TOOL_SLOTS = 8;
    private static final int DEFAULT_CARD_TOKEN_BUDGET = 400;

    /** ADR-0069 §9 rollout mode. */
    public enum Mode {
        OFF,
        SHADOW,
        ENFORCE
    }

    /** The consumers of §6 that are promoted one at a time. */
    public enum Consumer {
        RAG,
        TOOLS,
        CARD,
        /**
         * ADR-0069 §6 row 3 / ADR-0068 spec §2.7: the lexicon lookups. Entity seeds supply the
         * tag-added facade tools (instead of the inventory / order keyword tags) and, for an {@code
         * ACTION} intent, the heuristic workflow state through the lexicon's {@code workflow_state}.
         */
        LOOKUPS
    }

    public ScopeGraphProperties {
        mode = mode == null ? Mode.OFF : mode;
        enforce = enforce == null ? List.of() : enforce.stream().distinct().toList();
        if (maxNodes <= 0) {
            maxNodes = DEFAULT_MAX_NODES;
        }
        if (addedToolSlots <= 0) {
            addedToolSlots = DEFAULT_ADDED_TOOL_SLOTS;
        }
        if (cardTokenBudget <= 0) {
            cardTokenBudget = DEFAULT_CARD_TOKEN_BUDGET;
        }
    }

    /** The defaults: mode {@code off}, nothing enforced. */
    public static @NonNull ScopeGraphProperties off() {
        return new ScopeGraphProperties(Mode.OFF, List.of(), 0, 0, 0);
    }

    /** True in {@code shadow} and {@code enforce}: the graph is built and the scope is resolved. */
    public boolean enabled() {
        return mode != Mode.OFF;
    }

    /** True when {@code consumer} acts on the scope: mode {@code enforce} and the consumer is listed. */
    public boolean enforces(@NonNull Consumer consumer) {
        return mode == Mode.ENFORCE && enforce.contains(consumer);
    }
}

package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Confidence;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ADR-0069 §6 / §9: the one switch every consumer of the scope reads, plus the card rendering and
 * the fallback meter they share.
 *
 * <p>A consumer acts only when {@code mcp.scope-graph.mode} is {@code enforce} <em>and</em> the
 * consumer is in {@code mcp.scope-graph.enforce}; with {@code mode: enforce} and an empty list every
 * consumer is a no-op and the behaviour is exactly {@code shadow}. Which confidence each acts on
 * (spec §2.7): the RAG filter and the card on {@code HIGH} only, the tool slots on {@code HIGH} and
 * {@code LOW}. A consumer that is enforced but does not act on a turn is a fallback, counted under
 * {@code mcp.scope.fallback{consumer}}.
 */
@Component
public class ScopeConsumers {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScopeConsumers.class);

    private final ScopeGraphProperties properties;
    private final ScopeGraphHolder holder;
    private final ScopeMetrics metrics;

    public ScopeConsumers(
            @NonNull ScopeGraphProperties properties, @NonNull ScopeGraphHolder holder, @NonNull ScopeMetrics metrics) {
        this.properties = properties;
        this.holder = holder;
        this.metrics = metrics;
    }

    public @NonNull ScopeGraphProperties properties() {
        return properties;
    }

    /** True when {@code consumer} acts on the scope: mode {@code enforce} and the consumer is listed. */
    public boolean enforces(@NonNull Consumer consumer) {
        return properties.enforces(consumer);
    }

    /** §6: the one cap facade and discovered additions share per turn. */
    public int addedToolSlots() {
        return properties.addedToolSlots();
    }

    /** The slots left after {@code used} tools were already added this turn. */
    public int remainingSlots(int used) {
        return Math.max(0, addedToolSlots() - used);
    }

    /** Whether the tool slots act on {@code scope}: enforced, and confidence {@code HIGH} or {@code LOW}. */
    public boolean toolsActOn(@Nullable ScopeSet scope) {
        return enforces(Consumer.TOOLS) && scope != null && scope.confidence() != Confidence.NONE;
    }

    /** Whether the RAG filter acts on {@code scope}: enforced, and confidence {@code HIGH}. */
    public boolean ragActsOn(@Nullable ScopeSet scope) {
        return enforces(Consumer.RAG) && scope != null && scope.confidence() == Confidence.HIGH;
    }

    /** Counts a turn on which an enforced consumer fell back to today's behaviour. */
    public void recordFallback(@NonNull Consumer consumer) {
        metrics.recordFallback(consumer);
    }

    /**
     * §7: the scope card for this turn, or empty when the card consumer is not enforced, the
     * confidence is below {@code HIGH}, nothing in the scope qualifies, or the graph has been rebuilt
     * since the scope was resolved (the scope's node keys may no longer mean the same thing). Every
     * empty result under enforcement is a fallback.
     *
     * @param callerPermissionCodes used only to choose which of a scope tool's own codes to print
     */
    public @NonNull Optional<String> renderCard(@Nullable ScopeSet scope, @NonNull Set<String> callerPermissionCodes) {
        if (!enforces(Consumer.CARD)) {
            return Optional.empty();
        }
        if (scope == null || scope.confidence() != Confidence.HIGH) {
            recordFallback(Consumer.CARD);
            return Optional.empty();
        }
        ScopeGraph graph = holder.current();
        Optional<String> card = Optional.empty();
        if (graph.contentHash().equals(scope.graphHash())) {
            try {
                card = ScopeCardRenderer.render(scope, graph, callerPermissionCodes, properties.cardTokenBudget());
            } catch (RuntimeException exception) {
                // The exception type only: nothing here may quote the message, and the card never does.
                LOGGER.warn(
                        "Scope card rendering failed; the turn continues without a card: {}",
                        exception.getClass().getSimpleName());
            }
        } else {
            LOGGER.debug("Scope graph rebuilt since the scope was resolved; no card for this turn");
        }
        if (card.isEmpty()) {
            recordFallback(Consumer.CARD);
        }
        return card;
    }
}

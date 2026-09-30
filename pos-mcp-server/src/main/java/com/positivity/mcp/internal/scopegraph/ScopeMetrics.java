package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Confidence;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The per-turn scope meters (spec §2.11). Every tag is a closed, small set: a confidence, a kind of
 * scope member, or a boolean. Nothing a caller typed and no tool, entity or document name is ever a
 * tag value.
 *
 * <p>With {@code mcp.scope-graph.mode: off} nothing is registered and every method is a no-op.
 */
@Component
public class ScopeMetrics {

    static final String RESOLVED = "mcp.scope.resolved";
    static final String SIZE = "mcp.scope.size";
    static final String CALLED_TOOL = "mcp.scope.called_tool";
    static final String RETRIEVED_DOC = "mcp.scope.retrieved_doc";
    static final String ERRORS = "mcp.scope.errors";
    static final String FALLBACK = "mcp.scope.fallback";

    private static final String IN_SCOPE = "in_scope";

    /** Null in mode {@code off}. */
    @Nullable
    private final Meters meters;

    public ScopeMetrics(@NonNull ScopeGraphProperties properties, @NonNull MeterRegistry registry) {
        this.meters = properties.enabled() ? new Meters(registry) : null;
    }

    /** One resolved turn: its confidence and the size of each part of the scope. */
    public void recordResolved(@NonNull ScopeSet scope) {
        if (meters == null) {
            return;
        }
        meters.resolved.get(scope.confidence()).increment();
        meters.entities.record(scope.seeds().size() + scope.reachedEntities().size());
        meters.tools.record(scope.tools().size());
        meters.documents.record(scope.documentIds().size());
        meters.screens.record(scope.screenKeys().size());
    }

    /** The resolver threw; the turn carried on with an empty scope. */
    public void recordError() {
        if (meters != null) {
            meters.errors.increment();
        }
    }

    /**
     * ADR-0069 §6: {@code consumer} is in {@code enforce} but fell back to today's behaviour on this
     * turn (confidence below what it acts on, or nothing to add).
     */
    public void recordFallback(@NonNull Consumer consumer) {
        if (meters != null) {
            meters.fallback.get(consumer).increment();
        }
    }

    /** At turn completion: how many of the tools the model called were inside the scope. */
    public void recordCalledTools(int inScope, int total) {
        if (meters != null && total > 0) {
            meters.calledToolInScope.increment(inScope);
            meters.calledToolOutOfScope.increment((double) total - inScope);
        }
    }

    /** At turn completion: how many of the documents retrieval handed the model were inside the scope. */
    public void recordRetrievedDocuments(int inScope, int total) {
        if (meters != null && total > 0) {
            meters.retrievedDocInScope.increment(inScope);
            meters.retrievedDocOutOfScope.increment((double) total - inScope);
        }
    }

    private static final class Meters {

        private final Map<Confidence, Counter> resolved = new EnumMap<>(Confidence.class);
        private final DistributionSummary entities;
        private final DistributionSummary tools;
        private final DistributionSummary documents;
        private final DistributionSummary screens;
        private final Counter calledToolInScope;
        private final Counter calledToolOutOfScope;
        private final Counter retrievedDocInScope;
        private final Counter retrievedDocOutOfScope;
        private final Counter errors;
        private final Map<Consumer, Counter> fallback = new EnumMap<>(Consumer.class);

        private Meters(MeterRegistry registry) {
            for (Consumer consumer : Consumer.values()) {
                fallback.put(
                        consumer,
                        Counter.builder(FALLBACK)
                                .description(
                                        "Turns on which an enforced consumer fell back to today's behaviour (ADR-0069 §6)")
                                .tag("consumer", consumer.name().toLowerCase(java.util.Locale.ROOT))
                                .register(registry));
            }
            for (Confidence confidence : Confidence.values()) {
                resolved.put(
                        confidence,
                        Counter.builder(RESOLVED)
                                .description("Turns whose scope was resolved, by confidence (ADR-0069 §5.4)")
                                .tag("confidence", confidence.name())
                                .register(registry));
            }
            this.entities = size(registry, "entities");
            this.tools = size(registry, "tools");
            this.documents = size(registry, "documents");
            this.screens = size(registry, "screens");
            this.calledToolInScope = share(registry, CALLED_TOOL, "Tool calls the model made", true);
            this.calledToolOutOfScope = share(registry, CALLED_TOOL, "Tool calls the model made", false);
            this.retrievedDocInScope = share(registry, RETRIEVED_DOC, "Documents retrieval returned", true);
            this.retrievedDocOutOfScope = share(registry, RETRIEVED_DOC, "Documents retrieval returned", false);
            this.errors = Counter.builder(ERRORS)
                    .description("Scope resolutions that threw; the turn used an empty scope")
                    .register(registry);
        }

        private static DistributionSummary size(MeterRegistry registry, String kind) {
            return DistributionSummary.builder(SIZE)
                    .description("Members of a resolved scope after the caller filter, by kind")
                    .tag("kind", kind)
                    .register(registry);
        }

        private static Counter share(MeterRegistry registry, String name, String what, boolean inScope) {
            return Counter.builder(name)
                    .description(what + ", by whether the turn's scope contained them")
                    .tag(IN_SCOPE, Boolean.toString(inScope))
                    .register(registry);
        }
    }
}

package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.scopegraph.NodeAttributes.ToolSource;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Confidence;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Relation;
import com.positivity.mcp.internal.scopegraph.ScopeSet.ScopeTool;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Seed;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ADR-0069 §5: resolves one turn's {@link ScopeSet}. Seed from the message, expand two hops over a
 * fixed edge whitelist, cap, then filter by caller.
 *
 * <p>Pure and in memory: no I/O, no model call, no clock. The walk does not depend on the caller;
 * the filter runs after it, so a permitted node reached through an unpermitted one stays in scope
 * and the unpermitted one appears nowhere in the result (§5.3).
 *
 * <p>{@link #resolve} never throws. A failure is counted ({@code mcp.scope.errors}) and the turn
 * carries on with {@link ScopeSet#empty()}, which is today's behaviour for every consumer (spec
 * §2.5). With {@code mcp.scope-graph.mode: off} nothing is resolved, measured or logged.
 */
@Component
public class ScopeResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScopeResolver.class);

    private static final int FIRST_HOP = 1;
    private static final int SECOND_HOP = 2;

    private final ScopeGraphHolder holder;
    private final ScopeGraphProperties properties;
    private final ScopeMetrics metrics;

    /** The matcher of the snapshot it was built from; replaced when the holder swaps the snapshot. */
    private volatile SnapshotMatcher snapshotMatcher = new SnapshotMatcher(ScopeGraph.empty());

    public ScopeResolver(
            @NonNull ScopeGraphHolder holder, @NonNull ScopeGraphProperties properties, @NonNull ScopeMetrics metrics) {
        this.holder = holder;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** True in {@code shadow} and {@code enforce}: a turn's scope is resolved and recorded. */
    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * The scope of one turn.
     *
     * @param callerPermissionCodes the caller's bare permission codes, {@code AUTHENTICATED} included
     * @param workflowState the turn's workflow state; facade tools must be valid in it
     * @return the caller-filtered scope; {@link ScopeSet#empty()} in mode {@code off} or on failure
     */
    public @NonNull ScopeSet resolve(
            @NonNull String message, @NonNull Set<String> callerPermissionCodes, @NonNull WorkflowState workflowState) {
        if (!properties.enabled()) {
            return ScopeSet.empty();
        }
        try {
            SnapshotMatcher current = matcherFor(holder.current());
            ScopeSet scope = resolve(
                    current.graph(),
                    current.matcher(),
                    message,
                    callerPermissionCodes,
                    workflowState.name(),
                    properties.maxNodes());
            metrics.recordResolved(scope);
            return scope;
        } catch (RuntimeException exception) {
            metrics.recordError();
            // The exception type only: a message could quote the input, and the scope never holds user text.
            LOGGER.warn(
                    "Scope resolution failed; the turn continues with an empty scope: {}",
                    exception.getClass().getSimpleName());
            return ScopeSet.empty();
        }
    }

    /** The resolution itself, as a function of its inputs. */
    static @NonNull ScopeSet resolve(
            @NonNull ScopeGraph graph,
            @NonNull TermMatcher matcher,
            @NonNull String message,
            @NonNull Set<String> callerPermissionCodes,
            @NonNull String workflowState,
            int maxNodes) {
        if (graph.isEmpty()) {
            return ScopeSet.none(graph);
        }
        List<Seed> seeds = matcher.match(message);
        if (seeds.isEmpty()) {
            return ScopeSet.none(graph);
        }
        Map<NodeId, Integer> reached = expand(graph, seeds, maxNodes);
        return assemble(graph, seeds, reached, callerPermissionCodes, workflowState);
    }

    /**
     * Spec §2.8: breadth-first from the seeds, two hops, each hop in a fixed order so the cap cuts
     * deterministically. Seeds are always kept; the cap bounds the whole scope, so what is left of it
     * after the seeds goes to the first hop, then the second.
     *
     * @return the non-seed nodes in scope, in expansion order, each with its hop
     */
    static @NonNull Map<NodeId, Integer> expand(@NonNull ScopeGraph graph, @NonNull List<Seed> seeds, int maxNodes) {
        Set<NodeId> seedIds = new TreeSet<>();
        seeds.forEach(seed -> seedIds.add(NodeId.of(NodeType.ENTITY, seed.entity())));
        int budget = Math.max(0, maxNodes - seedIds.size());
        Map<NodeId, Integer> reached = new LinkedHashMap<>();

        Set<NodeId> firstHop = new TreeSet<>(hopOrder(graph));
        for (NodeId seed : seedIds) {
            sources(graph, seed, EdgeType.RELATES_TO, firstHop);
            targets(graph, seed, EdgeType.RELATES_TO, firstHop);
            targets(graph, seed, EdgeType.OWNED_BY, firstHop);
            sources(graph, seed, EdgeType.ACTS_ON, firstHop);
            sources(graph, seed, EdgeType.ABOUT, firstHop);
            sources(graph, seed, EdgeType.SHOWS, firstHop);
            targets(graph, seed, EdgeType.HAS_STATE, firstHop);
        }
        firstHop.removeAll(seedIds);
        for (NodeId node : firstHop) {
            if (reached.size() >= budget) {
                return reached;
            }
            reached.put(node, FIRST_HOP);
        }

        Set<NodeId> secondHop = new TreeSet<>(hopOrder(graph));
        for (NodeId node : firstHop) {
            if (node.type() == NodeType.ENTITY) {
                // Not RELATES_TO: there is no third ring of entities.
                targets(graph, node, EdgeType.OWNED_BY, secondHop);
                sources(graph, node, EdgeType.ACTS_ON, secondHop);
                sources(graph, node, EdgeType.ABOUT, secondHop);
                sources(graph, node, EdgeType.SHOWS, secondHop);
            } else if (node.type() == NodeType.TOOL) {
                sources(graph, node, EdgeType.PRODUCES_INPUT_FOR, secondHop);
                targets(graph, node, EdgeType.PRODUCES_INPUT_FOR, secondHop);
            }
        }
        secondHop.removeAll(seedIds);
        secondHop.removeAll(firstHop);
        for (NodeId node : secondHop) {
            if (reached.size() >= budget) {
                return reached;
            }
            reached.put(node, SECOND_HOP);
        }
        return reached;
    }

    /** §5.3 then §5.4: drop what the caller cannot reach, and lay the rest out as a {@link ScopeSet}. */
    private static ScopeSet assemble(
            ScopeGraph graph,
            List<Seed> seeds,
            Map<NodeId, Integer> reached,
            Set<String> callerPermissionCodes,
            String workflowState) {
        List<String> entities = new ArrayList<>();
        List<String> domains = new ArrayList<>();
        List<ScopeTool> tools = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        List<String> screens = new ArrayList<>();
        List<String> states = new ArrayList<>();
        reached.forEach((node, hop) -> {
            if (!ScopeCallerFilter.permitted(graph, node, callerPermissionCodes, workflowState)) {
                return;
            }
            switch (node.type()) {
                case ENTITY -> entities.add(node.key());
                case DOMAIN -> domains.add(node.key());
                case TOOL -> tools.add(new ScopeTool(node.key(), sourceOf(graph, node), hop, accessOf(graph, node)));
                case RAG_DOC -> documents.add(node.key());
                case SCREEN -> screens.add(node.key());
                case LIFECYCLE_STATE -> states.add(node.key());
                default -> {
                    // Term, IdentifierPattern, Permission and WorkflowState nodes are never entered.
                }
            }
        });

        Set<String> inScope = new TreeSet<>(entities);
        seeds.forEach(seed -> inScope.add(seed.entity()));
        List<Relation> relations = new ArrayList<>();
        for (String entity : inScope) {
            for (Edge edge : graph.outgoing(NodeId.of(NodeType.ENTITY, entity), EdgeType.RELATES_TO)) {
                if (inScope.contains(edge.to().key())) {
                    relations.add(new Relation(entity, edge.label(), edge.to().key()));
                }
            }
        }
        Confidence confidence = seeds.stream().anyMatch(seed -> seed.kind().high()) ? Confidence.HIGH : Confidence.LOW;
        return new ScopeSet(
                seeds,
                entities,
                domains,
                tools,
                documents,
                screens,
                states,
                relations,
                confidence,
                graph.contentHash(),
                graph.builtAt());
    }

    /**
     * Spec §2.8, order within a hop: Entity, Domain, RagDoc, facade Tool, Screen, discovered Tool
     * ({@code reads} before {@code writes}), LifecycleState; then by key.
     */
    private static Comparator<NodeId> hopOrder(ScopeGraph graph) {
        // A tool's rank reads its edges; one entity can have hundreds of tools, so it is computed once.
        Map<NodeId, Integer> ranks = new HashMap<>();
        return Comparator.<NodeId>comparingInt(node -> ranks.computeIfAbsent(node, id -> rank(graph, id)))
                .thenComparing(NodeId::key);
    }

    private static int rank(ScopeGraph graph, NodeId node) {
        return switch (node.type()) {
            case ENTITY -> 0;
            case DOMAIN -> 1;
            case RAG_DOC -> 2;
            case SCREEN -> 4;
            case TOOL -> {
                if (sourceOf(graph, node) == ToolSource.FACADE) {
                    yield 3;
                }
                yield accessOf(graph, node) == Access.READS ? 5 : 6;
            }
            case LIFECYCLE_STATE -> 7;
            default -> 8;
        };
    }

    private static ToolSource sourceOf(ScopeGraph graph, NodeId tool) {
        return graph.attributes(tool, NodeAttributes.Tool.class)
                .map(NodeAttributes.Tool::source)
                .orElse(ToolSource.DISCOVERED);
    }

    /**
     * {@code WRITES} when any of the tool's {@code ACTS_ON} edges writes. A tool with no such edge
     * (reached as a prerequisite only) reads when it is a facade or a GET, and writes otherwise.
     */
    private static Access accessOf(ScopeGraph graph, NodeId tool) {
        List<Edge> edges = graph.outgoing(tool, EdgeType.ACTS_ON);
        if (!edges.isEmpty()) {
            return edges.stream().anyMatch(edge -> edge.access() == Access.WRITES) ? Access.WRITES : Access.READS;
        }
        return graph.attributes(tool, NodeAttributes.Tool.class)
                .filter(attributes -> attributes.source() == ToolSource.DISCOVERED)
                .map(attributes ->
                        attributes.httpMethod() == null ? Access.WRITES : Access.ofHttpMethod(attributes.httpMethod()))
                .orElse(Access.READS);
    }

    private static void targets(ScopeGraph graph, NodeId from, EdgeType type, Set<NodeId> into) {
        graph.outgoing(from, type).forEach(edge -> into.add(edge.to()));
    }

    private static void sources(ScopeGraph graph, NodeId to, EdgeType type, Set<NodeId> into) {
        graph.incoming(to, type).forEach(edge -> into.add(edge.from()));
    }

    /** Builds the matcher once per snapshot; a racing turn at worst builds it twice, never uses a stale one. */
    private SnapshotMatcher matcherFor(ScopeGraph graph) {
        SnapshotMatcher current = snapshotMatcher;
        if (current.graph() != graph) {
            current = new SnapshotMatcher(graph);
            snapshotMatcher = current;
        }
        return current;
    }

    private record SnapshotMatcher(
            @NonNull ScopeGraph graph, @NonNull TermMatcher matcher) {
        private SnapshotMatcher(ScopeGraph graph) {
            this(graph, TermMatcher.of(graph));
        }
    }
}

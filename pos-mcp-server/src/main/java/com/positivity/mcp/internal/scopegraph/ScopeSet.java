package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.scopegraph.NodeAttributes.ToolSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;

/**
 * ADR-0069 §5.4: the immutable result of resolving one turn's scope, already filtered by the caller
 * (§5.3). Everything in it is a platform definition (§8): entity keys, tool names, {@code
 * document_id}s, screen keys. It never holds user text; a seed records the entity and how it was
 * recognised, not what matched.
 *
 * <p>Entities, domains, lifecycle states and relations are not permission-bearing and are kept as
 * expanded; tools, documents and screens are only the ones the caller passed §5.3 for.
 *
 * @param seeds the entities recognised in the message, by entity key
 * @param reachedEntities entities reached from a seed over {@code RELATES_TO}, seeds excluded
 * @param tools facade and discovered tools, in expansion order
 * @param lifecycleStates {@code <entity>.<state>} keys of the seed entities
 * @param relations {@code RELATES_TO} edges whose two entities are both in scope
 * @param graphHash the content hash of the snapshot this scope was resolved against
 */
public record ScopeSet(
        @NonNull List<Seed> seeds,
        @NonNull List<String> reachedEntities,
        @NonNull List<String> domains,
        @NonNull List<ScopeTool> tools,
        @NonNull List<String> documentIds,
        @NonNull List<String> screenKeys,
        @NonNull List<String> lifecycleStates,
        @NonNull List<Relation> relations,
        @NonNull Confidence confidence,
        @NonNull String graphHash,
        @NonNull Instant graphBuiltAt) {

    private static final ScopeSet EMPTY = none(ScopeGraph.empty());

    /** ADR-0069 §5.4. */
    public enum Confidence {
        /** No seed: every consumer falls back to today's behaviour. */
        NONE,
        /** Seeds, but none from an identifier or an unambiguous exact lexicon term. */
        LOW,
        /** At least one seed from an identifier pattern or an exact term. */
        HIGH
    }

    /** @param kind how the entity was recognised; the matched text is never kept */
    public record Seed(@NonNull String entity, @NonNull MatchKind kind) {}

    /**
     * @param hop 1 when the tool acts on a seed entity, 2 when it was reached through a related
     *     entity or a prerequisite
     * @param access {@code WRITES} when any of the tool's {@code ACTS_ON} edges writes
     */
    public record ScopeTool(
            @NonNull String name,
            @NonNull ToolSource source,
            int hop,
            @NonNull Access access) {}

    /** One {@code RELATES_TO} edge between two in-scope entities. */
    public record Relation(
            @NonNull String from,
            @NonNull String label,
            @NonNull String to) {}

    public ScopeSet {
        seeds = List.copyOf(seeds);
        reachedEntities = List.copyOf(reachedEntities);
        domains = List.copyOf(domains);
        tools = List.copyOf(tools);
        documentIds = List.copyOf(documentIds);
        screenKeys = List.copyOf(screenKeys);
        lifecycleStates = List.copyOf(lifecycleStates);
        relations = List.copyOf(relations);
    }

    /** No seeds, confidence {@code NONE}, tied to no graph: the scope of a turn that resolved nothing. */
    public static @NonNull ScopeSet empty() {
        return EMPTY;
    }

    /** No seeds, confidence {@code NONE}, stamped with the snapshot that was consulted. */
    public static @NonNull ScopeSet none(@NonNull ScopeGraph graph) {
        return new ScopeSet(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Confidence.NONE,
                graph.contentHash(),
                graph.builtAt());
    }

    /** Seed entities first, then the reached ones. */
    public @NonNull List<String> entities() {
        List<String> entities = new ArrayList<>(seeds.size() + reachedEntities.size());
        seeds.forEach(seed -> entities.add(seed.entity()));
        entities.addAll(reachedEntities);
        return List.copyOf(entities);
    }

    public @NonNull List<ScopeTool> facadeTools() {
        return tools.stream().filter(tool -> tool.source() == ToolSource.FACADE).toList();
    }

    public @NonNull List<ScopeTool> discoveredTools() {
        return tools.stream()
                .filter(tool -> tool.source() == ToolSource.DISCOVERED)
                .toList();
    }

    /** The {@code mcp_tool.name} of every tool in scope, facade and discovered. */
    public @NonNull Set<String> toolNames() {
        return tools.stream().map(ScopeTool::name).collect(Collectors.toUnmodifiableSet());
    }
}

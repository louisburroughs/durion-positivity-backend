package com.positivity.mcp.internal.scopegraph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * ADR-0069 §4: an immutable in-memory snapshot of the scope graph, held as plain adjacency maps.
 *
 * <p>The graph holds platform definitions only (§8): entity types, terms, tools, documents, screens,
 * permissions and states. It is derived entirely from its sources, so it is never persisted; {@link
 * #contentHash()} and {@link #builtAt()} tie a turn to the version it used. The hash covers every
 * node with its attributes, every edge with its label, and the domain scopes, so two graphs that
 * filter or render differently never share it.
 *
 * <p>Nodes and edges are kept sorted, so iteration order, and with it everything computed from the
 * graph, does not depend on the order the sources were read in.
 */
public final class ScopeGraph {

    private static final int HASH_LENGTH = 16;
    private static final ScopeGraph EMPTY = new Builder().build(Instant.EPOCH);

    private final Map<NodeId, ScopeNode> nodes;
    private final Map<NodeId, List<Edge>> outgoing;
    private final Map<NodeId, List<Edge>> incoming;
    private final Map<String, String> domainScopes;
    private final int edgeCount;
    private final String contentHash;
    private final Instant builtAt;
    private final List<String> entityOptions;
    private final List<String> domainOptions;

    private ScopeGraph(
            TreeMap<NodeId, ScopeNode> nodes, TreeSet<Edge> edges, Map<String, String> domainScopes, Instant builtAt) {
        this.nodes = java.util.Collections.unmodifiableMap(nodes);
        this.outgoing = adjacency(edges, true);
        this.incoming = adjacency(edges, false);
        this.domainScopes = java.util.Collections.unmodifiableMap(new TreeMap<>(domainScopes));
        this.edgeCount = edges.size();
        this.contentHash = hash(nodes.values(), edges, this.domainScopes);
        this.builtAt = builtAt;
        this.entityOptions = keysOf(NodeType.ENTITY);
        this.domainOptions = keysOf(NodeType.DOMAIN);
    }

    /** The graph before the first build, and the graph of {@code mode: off}: no nodes, no edges. */
    public static @NonNull ScopeGraph empty() {
        return EMPTY;
    }

    public static @NonNull Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edgeCount;
    }

    /** Every node, sorted by type then key. */
    public @NonNull Collection<ScopeNode> nodes() {
        return nodes.values();
    }

    public boolean contains(@NonNull NodeId id) {
        return nodes.containsKey(id);
    }

    public @NonNull Optional<ScopeNode> node(@NonNull NodeId id) {
        return Optional.ofNullable(nodes.get(id));
    }

    /** The node's attributes when it exists and carries attributes of {@code type}. */
    public <T extends NodeAttributes> @NonNull Optional<T> attributes(@NonNull NodeId id, @NonNull Class<T> type) {
        ScopeNode node = nodes.get(id);
        return node != null && type.isInstance(node.attributes())
                ? Optional.of(type.cast(node.attributes()))
                : Optional.empty();
    }

    /** Every node of one type, sorted by key. */
    public @NonNull List<ScopeNode> nodesOfType(@NonNull NodeType type) {
        return nodes.values().stream().filter(node -> node.id().type() == type).toList();
    }

    /** Edges leaving {@code id}, sorted; empty for an unknown node. */
    public @NonNull List<Edge> outgoing(@NonNull NodeId id) {
        return outgoing.getOrDefault(id, List.of());
    }

    public @NonNull List<Edge> outgoing(@NonNull NodeId id, @NonNull EdgeType type) {
        return outgoing(id).stream().filter(edge -> edge.type() == type).toList();
    }

    /** Edges arriving at {@code id}, sorted; empty for an unknown node. */
    public @NonNull List<Edge> incoming(@NonNull NodeId id) {
        return incoming.getOrDefault(id, List.of());
    }

    public @NonNull List<Edge> incoming(@NonNull NodeId id, @NonNull EdgeType type) {
        return incoming(id).stream().filter(edge -> edge.type() == type).toList();
    }

    /** Every edge, sorted. */
    public @NonNull List<Edge> edges() {
        return outgoing.values().stream().flatMap(List::stream).sorted().toList();
    }

    /**
     * ADR-0069 §2, Domain row: the tool catalog and the RAG corpus spell some domains differently
     * ({@code shop-manager} vs {@code shopmanager}). Returns the RAG scope of a tool-catalog domain,
     * which is the domain itself unless the lexicon maps it.
     */
    public @NonNull String ragScopeOf(@NonNull String domain) {
        return domainScopes.getOrDefault(domain, domain);
    }

    /** The lexicon's explicit tool-catalog domain → RAG scope map. */
    public @NonNull Map<String, String> domainScopes() {
        return domainScopes;
    }

    /**
     * SHA-256 over the sorted node and edge keys, first 16 hex characters. Two graphs with the same
     * nodes and edges hash the same whatever order they were built in.
     */
    public @NonNull String contentHash() {
        return contentHash;
    }

    /** When this snapshot was built, from the injected clock (ADR-0024). */
    public @NonNull Instant builtAt() {
        return builtAt;
    }

    /**
     * The closed option set for the ADR-0068 {@code entity} Choice question: every entity key,
     * sorted. Global, never filtered per caller (ADR-0069 §5.1).
     */
    public @NonNull List<String> entityOptions() {
        return entityOptions;
    }

    /** The closed option set for the ADR-0068 {@code domain} Choice question: every domain, sorted. */
    public @NonNull List<String> domainOptions() {
        return domainOptions;
    }

    private List<String> keysOf(NodeType type) {
        return nodes.keySet().stream()
                .filter(id -> id.type() == type)
                .map(NodeId::key)
                .toList();
    }

    private static Map<NodeId, List<Edge>> adjacency(TreeSet<Edge> edges, boolean bySource) {
        TreeMap<NodeId, List<Edge>> grouped = new TreeMap<>();
        for (Edge edge : edges) {
            grouped.computeIfAbsent(bySource ? edge.from() : edge.to(), ignored -> new ArrayList<>())
                    .add(edge);
        }
        grouped.replaceAll((ignored, list) -> List.copyOf(list));
        return java.util.Collections.unmodifiableMap(grouped);
    }

    private static String hash(Collection<ScopeNode> nodes, Collection<Edge> edges, Map<String, String> domainScopes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ScopeNode node : nodes) {
                digest.update(("N|" + node.id() + "|" + canonical(node.attributes()) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            for (Edge edge : edges) {
                digest.update(("E|" + edge.key() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            domainScopes.forEach((domain, scope) ->
                    digest.update(("S|" + domain + "|" + scope + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest()).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is required of every Java platform; reaching this is a broken runtime.
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * A node's attributes in one canonical form: the collections a record holds are copied without
     * an iteration order, so they are sorted here and the text does not depend on the JVM run.
     */
    private static String canonical(@Nullable NodeAttributes attributes) {
        return switch (attributes) {
            case null -> "";
            case NodeAttributes.Tool tool -> {
                StringBuilder text = new StringBuilder("tool|")
                        .append(tool.source())
                        .append('|')
                        .append(tool.domain())
                        .append('|')
                        .append(tool.httpMethod());
                new TreeMap<>(tool.permissionGroups())
                        .forEach((group, codes) ->
                                text.append('|').append(group).append('=').append(new TreeSet<>(codes)));
                yield text.toString();
            }
            case NodeAttributes.RagDoc doc ->
                "rag|" + doc.ragScope() + "|" + doc.requiredPermissions() + "|" + doc.platformWide();
            case NodeAttributes.Screen screen ->
                "screen|" + screen.title() + "|" + screen.urlTemplate() + "|" + screen.domain() + "|"
                        + screen.requiredPerm();
            case NodeAttributes.Term term -> "term|" + term.language() + "|" + term.phrase() + "|" + term.glossary();
            case NodeAttributes.IdentifierPattern pattern -> "id|" + pattern.pattern();
        };
    }

    /**
     * Accumulates nodes and edges for one snapshot. An edge may only join nodes that were added, so a
     * dangling reference is a programming error in the caller, not a silently half-connected graph.
     */
    public static final class Builder {

        private final TreeMap<NodeId, ScopeNode> nodes = new TreeMap<>();
        private final TreeSet<Edge> edges = new TreeSet<>();
        private final TreeMap<String, String> domainScopes = new TreeMap<>();

        private Builder() {}

        /** Adds an identity-only node; keeps an existing node and its attributes. */
        public @NonNull NodeId node(@NonNull NodeType type, @NonNull String key) {
            NodeId id = NodeId.of(type, key);
            nodes.putIfAbsent(id, new ScopeNode(id, null));
            return id;
        }

        /** Adds a node with attributes, replacing the attributes of an existing node. */
        public @NonNull NodeId node(@NonNull NodeType type, @NonNull String key, @Nullable NodeAttributes attributes) {
            NodeId id = NodeId.of(type, key);
            nodes.put(id, new ScopeNode(id, attributes));
            return id;
        }

        public boolean contains(@NonNull NodeId id) {
            return nodes.containsKey(id);
        }

        public @NonNull Builder edge(@NonNull EdgeType type, @NonNull NodeId from, @NonNull NodeId to) {
            return edge(type, from, to, "");
        }

        public @NonNull Builder edge(
                @NonNull EdgeType type, @NonNull NodeId from, @NonNull NodeId to, @NonNull String label) {
            if (!nodes.containsKey(from) || !nodes.containsKey(to)) {
                throw new IllegalArgumentException(
                        type + " edge " + from + " -> " + to + " references a node that was not added");
            }
            edges.add(new Edge(type, from, to, label));
            return this;
        }

        public @NonNull Builder domainScope(@NonNull String domain, @NonNull String ragScope) {
            domainScopes.put(domain, ragScope);
            return this;
        }

        public @NonNull ScopeGraph build(@NonNull Instant builtAt) {
            return new ScopeGraph(new TreeMap<>(nodes), new TreeSet<>(edges), domainScopes, builtAt);
        }
    }
}

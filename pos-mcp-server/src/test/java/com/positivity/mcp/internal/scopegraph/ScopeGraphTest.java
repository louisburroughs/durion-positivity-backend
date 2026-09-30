package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScopeGraphTest {

    private static final Instant BUILT_AT = Instant.parse("2026-09-30T10:00:00Z");

    @Test
    @DisplayName("the empty graph has no nodes, no options and a stable hash")
    void emptyGraph() {
        ScopeGraph empty = ScopeGraph.empty();

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.nodeCount()).isZero();
        assertThat(empty.edgeCount()).isZero();
        assertThat(empty.entityOptions()).isEmpty();
        assertThat(empty.domainOptions()).isEmpty();
        assertThat(empty.contentHash())
                .hasSize(16)
                .isEqualTo(ScopeGraph.builder().build(BUILT_AT).contentHash());
        assertThat(ScopeGraph.empty()).isSameAs(empty);
    }

    @Test
    @DisplayName("adjacency is kept in both directions, with the edge label")
    void adjacencyBothDirections() {
        ScopeGraph graph = sample(false).build(BUILT_AT);
        NodeId workorder = NodeId.of(NodeType.ENTITY, "workorder");
        NodeId tool = NodeId.of(NodeType.TOOL, "WorkorderFacadeTool");

        assertThat(graph.outgoing(tool, EdgeType.ACTS_ON)).singleElement().satisfies(edge -> {
            assertThat(edge.to()).isEqualTo(workorder);
            assertThat(edge.access()).isEqualTo(Access.READS);
        });
        assertThat(graph.incoming(workorder, EdgeType.ACTS_ON))
                .extracting(Edge::from)
                .containsExactly(tool);
        assertThat(graph.outgoing(NodeId.of(NodeType.ENTITY, "estimate"), EdgeType.RELATES_TO))
                .extracting(Edge::label)
                .containsExactly("promotes_to");
        assertThat(graph.outgoing(NodeId.of(NodeType.ENTITY, "nothing"))).isEmpty();
        assertThat(graph.edgeCount()).isEqualTo(graph.edges().size()).isEqualTo(4);
        assertThat(graph.builtAt()).isEqualTo(BUILT_AT);
    }

    @Test
    @DisplayName("entity and domain options are the sorted node keys")
    void options() {
        ScopeGraph graph = sample(false).build(BUILT_AT);

        assertThat(graph.entityOptions()).containsExactly("estimate", "workorder");
        assertThat(graph.domainOptions()).containsExactly("shop-manager", "workorder");
        assertThat(graph.ragScopeOf("shop-manager")).isEqualTo("shopmanager");
        assertThat(graph.ragScopeOf("workorder")).isEqualTo("workorder");
    }

    @Test
    @DisplayName("typed attributes are returned only for a node that carries that type")
    void typedAttributes() {
        ScopeGraph graph = sample(false).build(BUILT_AT);

        assertThat(graph.attributes(NodeId.of(NodeType.TOOL, "WorkorderFacadeTool"), NodeAttributes.Tool.class))
                .hasValueSatisfying(tool -> assertThat(tool.permissionGroups())
                        .containsEntry("getWorkorder", Set.of("workorder:workorder:view")));
        assertThat(graph.attributes(NodeId.of(NodeType.TOOL, "WorkorderFacadeTool"), NodeAttributes.Screen.class))
                .isEmpty();
        assertThat(graph.attributes(NodeId.of(NodeType.ENTITY, "workorder"), NodeAttributes.Tool.class))
                .isEmpty();
    }

    @Test
    @DisplayName("the content hash does not depend on the order nodes and edges were added in")
    void hashIsStableAcrossInputOrdering() {
        ScopeGraph forward = sample(false).build(BUILT_AT);
        ScopeGraph reversed = sample(true).build(Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(reversed.contentHash()).isEqualTo(forward.contentHash());
        assertThat(reversed.edges()).isEqualTo(forward.edges());
        assertThat(forward.contentHash()).matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("the content hash changes when an edge, an edge label or a node changes")
    void hashChangesWithContent() {
        String base = sample(false).build(BUILT_AT).contentHash();

        ScopeGraph.Builder extraEdge = sample(false);
        extraEdge.edge(
                EdgeType.RELATES_TO,
                NodeId.of(NodeType.ENTITY, "workorder"),
                NodeId.of(NodeType.ENTITY, "estimate"),
                "promoted_from");
        ScopeGraph.Builder extraNode = sample(false);
        extraNode.node(NodeType.ENTITY, "invoice");

        assertThat(extraEdge.build(BUILT_AT).contentHash()).isNotEqualTo(base);
        assertThat(extraNode.build(BUILT_AT).contentHash()).isNotEqualTo(base);
        assertThat(sampleWithAccess(Access.WRITES).build(BUILT_AT).contentHash())
                .isNotEqualTo(sampleWithAccess(Access.READS).build(BUILT_AT).contentHash());
    }

    @Test
    @DisplayName("the content hash changes when a node's attributes or a domain scope change, not only its identity")
    void hashChangesWithAttributes() {
        String base = sample(false).build(BUILT_AT).contentHash();
        NodeId tool = NodeId.of(NodeType.TOOL, "WorkorderFacadeTool");

        // The same permission codes regrouped: identical REQUIRES edges, a different AND structure.
        ScopeGraph.Builder regrouped = sample(false);
        regrouped.node(
                NodeType.TOOL,
                "WorkorderFacadeTool",
                new NodeAttributes.Tool(
                        NodeAttributes.ToolSource.FACADE,
                        "workorder",
                        null,
                        Map.of("getWorkorder", Set.of(), "listWorkorders", Set.of("workorder:workorder:view"))));
        ScopeGraph.Builder discovered = sample(false);
        discovered.node(
                NodeType.TOOL,
                "WorkorderFacadeTool",
                new NodeAttributes.Tool(
                        NodeAttributes.ToolSource.DISCOVERED,
                        "workorder",
                        null,
                        Map.of("getWorkorder", Set.of("workorder:workorder:view"))));
        ScopeGraph.Builder pattern = sample(false);
        pattern.node(NodeType.IDENTIFIER_PATTERN, "workorder-number", new NodeAttributes.IdentifierPattern("WO-\\d+"));
        ScopeGraph.Builder otherPattern = sample(false);
        otherPattern.node(
                NodeType.IDENTIFIER_PATTERN, "workorder-number", new NodeAttributes.IdentifierPattern("WO-\\d{4}"));
        ScopeGraph.Builder scope = sample(false);
        scope.domainScope("shop-manager", "shop-manager");

        assertThat(regrouped.build(BUILT_AT).contentHash()).isNotEqualTo(base);
        assertThat(discovered.build(BUILT_AT).contentHash()).isNotEqualTo(base);
        assertThat(pattern.build(BUILT_AT).contentHash())
                .isNotEqualTo(base)
                .isNotEqualTo(otherPattern.build(BUILT_AT).contentHash());
        assertThat(scope.build(BUILT_AT).contentHash()).isNotEqualTo(base);
        assertThat(sample(true).build(BUILT_AT).attributes(tool, NodeAttributes.Tool.class))
                .isPresent();
    }

    @Test
    @DisplayName("an edge between node types its type does not join is rejected")
    void edgeTypesAreClosed() {
        ScopeGraph.Builder builder = ScopeGraph.builder();
        NodeId entity = builder.node(NodeType.ENTITY, "workorder");
        NodeId domain = builder.node(NodeType.DOMAIN, "workorder");

        assertThatThrownBy(() -> builder.edge(EdgeType.OWNED_BY, domain, entity))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.edge(EdgeType.REQUIRES, entity, builder.node(NodeType.PERMISSION, "p")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.edge(EdgeType.OWNED_BY, entity, NodeId.of(NodeType.DOMAIN, "missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not added");
    }

    @Test
    @DisplayName("the snapshot is immutable")
    void snapshotIsImmutable() {
        ScopeGraph graph = sample(false).build(BUILT_AT);

        assertThatThrownBy(() -> graph.nodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> graph.entityOptions().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> graph.domainScopes().put("a", "b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() ->
                        graph.outgoing(NodeId.of(NodeType.ENTITY, "estimate")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ScopeGraph.Builder sample(boolean reversed) {
        return sample(reversed, Access.READS);
    }

    private static ScopeGraph.Builder sampleWithAccess(Access access) {
        return sample(false, access);
    }

    private static ScopeGraph.Builder sample(boolean reversed, Access access) {
        ScopeGraph.Builder builder = ScopeGraph.builder();
        List<Runnable> nodes = List.of(
                () -> builder.node(NodeType.ENTITY, "workorder"),
                () -> builder.node(NodeType.ENTITY, "estimate"),
                () -> builder.node(NodeType.DOMAIN, "workorder"),
                () -> builder.node(NodeType.DOMAIN, "shop-manager"),
                () -> builder.node(
                        NodeType.TOOL,
                        "WorkorderFacadeTool",
                        new NodeAttributes.Tool(
                                NodeAttributes.ToolSource.FACADE,
                                "workorder",
                                null,
                                Map.of("getWorkorder", Set.of("workorder:workorder:view")))));
        NodeId workorder = NodeId.of(NodeType.ENTITY, "workorder");
        NodeId estimate = NodeId.of(NodeType.ENTITY, "estimate");
        NodeId domain = NodeId.of(NodeType.DOMAIN, "workorder");
        NodeId tool = NodeId.of(NodeType.TOOL, "WorkorderFacadeTool");
        List<Runnable> edges = List.of(
                () -> builder.edge(EdgeType.OWNED_BY, workorder, domain),
                () -> builder.edge(EdgeType.OWNED_BY, estimate, domain),
                () -> builder.edge(EdgeType.RELATES_TO, estimate, workorder, "promotes_to"),
                () -> builder.edge(EdgeType.ACTS_ON, tool, workorder, access.label()));
        (reversed ? nodes.reversed() : nodes).forEach(Runnable::run);
        (reversed ? edges.reversed() : edges).forEach(Runnable::run);
        builder.domainScope("shop-manager", "shopmanager");
        return builder;
    }
}

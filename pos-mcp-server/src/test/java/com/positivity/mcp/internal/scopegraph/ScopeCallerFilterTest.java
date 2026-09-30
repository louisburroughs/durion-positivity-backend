package com.positivity.mcp.internal.scopegraph;

import static com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures.ANALYTICS_VIEW;
import static com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures.AUTHENTICATED;
import static com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures.LOCATION_READ;
import static com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures.WORKORDER_LIST;
import static com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures.WORKORDER_VIEW;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec §2.9: one predicate per source, each a mirror of the predicate that source already enforces. */
class ScopeCallerFilterTest {

    private static final String IDLE = "IDLE";

    private final ScopeGraph graph = ScopeResolverFixtures.graph();

    private boolean tool(String name, Set<String> codes, String workflowState) {
        return ScopeCallerFilter.toolPermitted(graph, NodeId.of(NodeType.TOOL, name), codes, workflowState);
    }

    @Test
    @DisplayName("facade: the caller must hold every code of at least one permission group")
    void facadeAndGroups() {
        // Group getWorkorder = {view}.
        assertThat(tool("WorkorderFacadeTool", Set.of(WORKORDER_VIEW), IDLE)).isTrue();
        // Group getLaborAnalytics = {analytics, location}: both, or nothing.
        assertThat(tool("WorkorderFacadeTool", Set.of(ANALYTICS_VIEW, LOCATION_READ), IDLE))
                .isTrue();
        assertThat(tool("WorkorderFacadeTool", Set.of(ANALYTICS_VIEW), IDLE)).isFalse();
        assertThat(tool("WorkorderFacadeTool", Set.of(LOCATION_READ), IDLE)).isFalse();
        // Half of one group plus an unrelated code is still not a whole group.
        assertThat(tool("WorkorderFacadeTool", Set.of(ANALYTICS_VIEW, AUTHENTICATED), IDLE))
                .isFalse();
    }

    @Test
    @DisplayName("facade: no permission rows means excluded for every caller, and no codes means no tool")
    void facadeNoRows() {
        assertThat(tool("NoRowsFacadeTool", Set.of(WORKORDER_VIEW, AUTHENTICATED), IDLE))
                .isFalse();
        assertThat(tool("WorkorderFacadeTool", Set.of(), IDLE)).isFalse();
    }

    @Test
    @DisplayName("facade: the tool must be VALID_IN the turn's workflow state")
    void facadeWorkflowState() {
        assertThat(tool("WorkorderFacadeTool", Set.of(WORKORDER_VIEW), "CREATING_PO"))
                .isFalse();
        assertThat(tool("InvoiceFacadeTool", Set.of(ScopeResolverFixtures.INVOICE_VIEW), "CREATING_PO"))
                .isTrue();
        assertThat(tool("InvoiceFacadeTool", Set.of(ScopeResolverFixtures.INVOICE_VIEW), "RECEIVING_ASN"))
                .isFalse();
    }

    @Test
    @DisplayName("discovered: any one code qualifies, no rows is excluded, and the state is fixed to IDLE")
    void discovered() {
        // Two single-code groups: either code is enough.
        assertThat(tool("workorder_listworkorders", Set.of(WORKORDER_LIST), IDLE))
                .isTrue();
        assertThat(tool("workorder_listworkorders", Set.of(WORKORDER_VIEW), IDLE))
                .isTrue();
        assertThat(tool("workorder_listworkorders", Set.of(AUTHENTICATED), IDLE))
                .isFalse();
        assertThat(tool("workorder_listworkorders", Set.of(), IDLE)).isFalse();
        // The turn's own state is irrelevant to a discovered operation.
        assertThat(tool("workorder_listworkorders", Set.of(WORKORDER_LIST), "CREATING_PO"))
                .isTrue();
        // Valid in CREATING_PO only: never in scope, even in a CREATING_PO turn.
        assertThat(tool("workorder_notidle", Set.of(WORKORDER_VIEW), IDLE)).isFalse();
        assertThat(tool("workorder_notidle", Set.of(WORKORDER_VIEW), "CREATING_PO"))
                .isFalse();

        NodeAttributes.Tool noRows =
                new NodeAttributes.Tool(NodeAttributes.ToolSource.DISCOVERED, "workorder", "GET", java.util.Map.of());
        assertThat(ScopeCallerFilter.discoveredToolPermitted(noRows, Set.of(IDLE), Set.of(WORKORDER_VIEW)))
                .isFalse();
    }

    @Test
    @DisplayName("a node that is not a tool of the graph is not a permitted tool")
    void unknownTool() {
        assertThat(tool("no_such_tool", Set.of(WORKORDER_VIEW), IDLE)).isFalse();
        assertThat(ScopeCallerFilter.toolPermitted(
                        graph, NodeId.of(NodeType.ENTITY, "workorder"), Set.of(WORKORDER_VIEW), IDLE))
                .isFalse();
    }

    @Test
    @DisplayName(
            "RAG document: public when nothing is required, AUTHENTICATED needs the sentinel, otherwise any shared code")
    void ragDocument() {
        assertThat(ScopeCallerFilter.ragDocumentVisible(List.of(), Set.of())).isTrue();
        assertThat(ScopeCallerFilter.ragDocumentVisible(List.of(AUTHENTICATED), Set.of(AUTHENTICATED)))
                .isTrue();
        assertThat(ScopeCallerFilter.ragDocumentVisible(List.of(AUTHENTICATED), Set.of()))
                .isFalse();
        // The sentinel decides on its own: another shared code does not stand in for it.
        assertThat(ScopeCallerFilter.ragDocumentVisible(List.of(AUTHENTICATED, WORKORDER_VIEW), Set.of(WORKORDER_VIEW)))
                .isFalse();
        assertThat(ScopeCallerFilter.ragDocumentVisible(
                        List.of(WORKORDER_VIEW, WORKORDER_LIST), Set.of(WORKORDER_LIST)))
                .isTrue();
        assertThat(ScopeCallerFilter.ragDocumentVisible(List.of(WORKORDER_VIEW), Set.of(AUTHENTICATED)))
                .isFalse();
    }

    @Test
    @DisplayName("screen: no required_perm is visible to all, otherwise the caller must hold it")
    void screen() {
        assertThat(ScopeCallerFilter.screenVisible(null, Set.of())).isTrue();
        assertThat(ScopeCallerFilter.screenVisible(WORKORDER_VIEW, Set.of(WORKORDER_VIEW)))
                .isTrue();
        assertThat(ScopeCallerFilter.screenVisible(WORKORDER_VIEW, Set.of(AUTHENTICATED)))
                .isFalse();
    }
}

package com.positivity.mcp.internal.scopegraph;

import static com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures.ANALYTICS_VIEW;
import static com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures.AUTHENTICATED;
import static com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures.INVOICE_VIEW;
import static com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures.LOCATION_READ;
import static com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures.WORKORDER_CREATE;
import static com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures.WORKORDER_LIST;
import static com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures.WORKORDER_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.scopegraph.NodeAttributes.ToolSource;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Confidence;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Relation;
import com.positivity.mcp.internal.scopegraph.ScopeSet.ScopeTool;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Seed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0069 §5: seed, expand two hops, cap, then filter by caller. */
class ScopeResolverTest {

    private static final Set<String> EVERYTHING = Set.of(
            AUTHENTICATED,
            WORKORDER_VIEW,
            WORKORDER_CREATE,
            WORKORDER_LIST,
            ANALYTICS_VIEW,
            LOCATION_READ,
            INVOICE_VIEW,
            "billing:admin");

    private final ScopeGraph graph = ScopeResolverFixtures.graph();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ScopeResolver resolver(ScopeGraphProperties properties) {
        return new ScopeResolver(
                ScopeResolverFixtures.holderOf(graph, properties, meters),
                properties,
                new ScopeMetrics(properties, meters));
    }

    private ScopeSet resolve(String message, Set<String> codes) {
        return resolver(ScopeResolverFixtures.shadow(60)).resolve(message, codes, WorkflowState.IDLE);
    }

    private static List<String> names(List<ScopeTool> tools) {
        return tools.stream().map(ScopeTool::name).toList();
    }

    // ── seeding and confidence ──────────────────────────────────────────────

    @Test
    @DisplayName(
            "confidence is HIGH for an identifier or an exact term, LOW for folded, ambiguous or glossary seeds, NONE without seeds")
    void confidence() {
        assertThat(resolve("status of WO-20391", EVERYTHING).confidence()).isEqualTo(Confidence.HIGH);
        assertThat(resolve("show the invoice", EVERYTHING).confidence()).isEqualTo(Confidence.HIGH);
        assertThat(resolve("show the invoices", EVERYTHING).confidence()).isEqualTo(Confidence.LOW);
        assertThat(resolve("open the ticket", EVERYTHING).confidence()).isEqualTo(Confidence.LOW);
        assertThat(resolve("our best customers", EVERYTHING).confidence()).isEqualTo(Confidence.LOW);
        // One HIGH seed is enough, whatever the others are.
        assertThat(resolve("the invoices of this work order", EVERYTHING).confidence())
                .isEqualTo(Confidence.HIGH);

        ScopeSet none = resolve("what time do you close?", EVERYTHING);
        assertThat(none.confidence()).isEqualTo(Confidence.NONE);
        assertThat(none.seeds()).isEmpty();
        assertThat(none.tools()).isEmpty();
        // Still stamped with the snapshot that was consulted.
        assertThat(none.graphHash()).isEqualTo(graph.contentHash());
    }

    @Test
    @DisplayName("the scope carries the graph's hash and build time, and seeds as entity key plus match kind only")
    void stampedWithGraphVersionAndNoUserText() {
        String message = "Zanzibar-42: is work order WO-20391 billed?";

        ScopeSet scope = resolve(message, EVERYTHING);

        assertThat(scope.graphHash()).isEqualTo(graph.contentHash());
        assertThat(scope.graphBuiltAt()).isEqualTo(ScopeResolverFixtures.BUILT_AT);
        assertThat(scope.seeds()).containsExactly(new Seed("workorder", MatchKind.IDENTIFIER));
        // Nothing the caller typed survives into the scope: not the identifier, not a stray word.
        assertThat(scope.toString()).doesNotContain("WO-20391", "Zanzibar", "billed?");
    }

    // ── expansion ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("hop 1 reaches the seed's domain, related entities both ways, tools, documents, screens and states")
    void firstHop() {
        ScopeSet scope = resolve("the work order", EVERYTHING);

        assertThat(scope.seeds()).containsExactly(new Seed("workorder", MatchKind.EXACT_TERM));
        // estimate -promotes_to-> workorder (incoming) and workorder -billed_by-> invoice (outgoing).
        assertThat(scope.reachedEntities()).containsExactly("estimate", "invoice");
        assertThat(scope.domains()).containsExactly("workorder", "billing");
        assertThat(scope.lifecycleStates()).containsExactly("workorder.COMPLETED", "workorder.DRAFT");
        assertThat(scope.screenKeys()).containsExactly("workorders.list", "workorders.wip");
        assertThat(scope.relations())
                .containsExactly(
                        new Relation("estimate", "promotes_to", "workorder"),
                        new Relation("workorder", "billed_by", "invoice"));
        assertThat(scope.entities()).containsExactly("workorder", "estimate", "invoice");
    }

    @Test
    @DisplayName(
            "hop 2 reaches the tools, documents and domain of a related entity, and the prerequisites of a hop-1 tool")
    void secondHop() {
        ScopeSet scope = resolve("the work order", EVERYTHING);

        assertThat(scope.tools())
                .contains(
                        new ScopeTool("WorkorderFacadeTool", ToolSource.FACADE, 1, Access.WRITES),
                        new ScopeTool("workorder_getworkorder", ToolSource.DISCOVERED, 1, Access.READS),
                        new ScopeTool("workorder_createworkorder", ToolSource.DISCOVERED, 1, Access.WRITES),
                        // Through the related entity `invoice`.
                        new ScopeTool("InvoiceFacadeTool", ToolSource.FACADE, 2, Access.READS),
                        // Through PRODUCES_INPUT_FOR of a hop-1 tool; it acts on no entity.
                        new ScopeTool("location_getprimary", ToolSource.DISCOVERED, 2, Access.READS));
        assertThat(scope.documentIds())
                .containsExactly(
                        "workorder.public", "workorder.status-lifecycle", "billing.invoices", "billing.secret");
        assertThat(names(scope.facadeTools())).containsExactly("WorkorderFacadeTool", "InvoiceFacadeTool");
        assertThat(scope.toolNames()).contains("workorder_listworkorders", "location_getprimary");
    }

    @Test
    @DisplayName(
            "there is no third ring: an entity two relations away, its states and a related entity's states stay out")
    void hopLimit() {
        ScopeSet scope = resolve("the work order", EVERYTHING);

        // customer is invoice's relation, two RELATES_TO edges from the seed.
        assertThat(scope.entities()).doesNotContain("customer");
        assertThat(scope.domains()).doesNotContain("crm");
        // HAS_STATE is followed from seeds only.
        assertThat(scope.lifecycleStates()).doesNotContain("invoice.PAID");
        // A domain is never expanded to its tools; `order` shares nothing with the seed.
        assertThat(scope.entities()).doesNotContain("order", "part");
    }

    @Test
    @DisplayName(
            "within a hop the order is Entity, Domain, RagDoc, facade Tool, Screen, discovered reads, discovered writes, LifecycleState, then key")
    void deterministicOrder() {
        List<Seed> seeds = List.of(new Seed("workorder", MatchKind.EXACT_TERM));

        Map<NodeId, Integer> reached = ScopeResolver.expand(graph, seeds, 60);

        List<String> order = new ArrayList<>();
        reached.forEach((node, hop) -> order.add(hop + " " + node));
        assertThat(order)
                .containsExactly(
                        "1 ENTITY:estimate",
                        "1 ENTITY:invoice",
                        "1 DOMAIN:workorder",
                        "1 RAG_DOC:workorder.public",
                        "1 RAG_DOC:workorder.status-lifecycle",
                        "1 TOOL:NoRowsFacadeTool",
                        "1 TOOL:WorkorderFacadeTool",
                        "1 SCREEN:workorders.list",
                        "1 SCREEN:workorders.wip",
                        "1 TOOL:workorder_getworkorder",
                        "1 TOOL:workorder_listworkorders",
                        "1 TOOL:workorder_notidle",
                        "1 TOOL:workorder_createworkorder",
                        "1 LIFECYCLE_STATE:workorder.COMPLETED",
                        "1 LIFECYCLE_STATE:workorder.DRAFT",
                        "2 DOMAIN:billing",
                        "2 RAG_DOC:billing.invoices",
                        "2 RAG_DOC:billing.secret",
                        "2 TOOL:InvoiceFacadeTool",
                        "2 TOOL:location_getprimary");
        // The same inputs always cut the same way.
        assertThat(ScopeResolver.expand(graph, seeds, 60)).isEqualTo(reached);
    }

    @Test
    @DisplayName("the cap bounds the whole scope, keeps every seed, and cuts the tail of the fixed order")
    void nodeCap() {
        List<Seed> seeds = List.of(new Seed("workorder", MatchKind.EXACT_TERM));

        // 1 seed + 4 others = 5.
        assertThat(ScopeResolver.expand(graph, seeds, 5).keySet().stream().map(NodeId::toString))
                .containsExactly("ENTITY:estimate", "ENTITY:invoice", "DOMAIN:workorder", "RAG_DOC:workorder.public");
        // A cut inside hop 1 leaves no room for hop 2.
        assertThat(ScopeResolver.expand(graph, seeds, 5).values()).containsOnly(1);
        // A cap at or below the number of seeds keeps the seeds and nothing else.
        assertThat(ScopeResolver.expand(graph, seeds, 1)).isEmpty();

        ScopeSet capped =
                resolver(ScopeResolverFixtures.shadow(1)).resolve("the work order", EVERYTHING, WorkflowState.IDLE);
        assertThat(capped.seeds()).containsExactly(new Seed("workorder", MatchKind.EXACT_TERM));
        assertThat(capped.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(capped.reachedEntities()).isEmpty();
        assertThat(capped.tools()).isEmpty();
        assertThat(capped.documentIds()).isEmpty();

        // Two seeds and a cap of one: both seeds stay.
        ScopeSet twoSeeds = resolver(ScopeResolverFixtures.shadow(1))
                .resolve("the work order and its invoice", EVERYTHING, WorkflowState.IDLE);
        assertThat(twoSeeds.seeds())
                .containsExactly(
                        new Seed("invoice", MatchKind.EXACT_TERM), new Seed("workorder", MatchKind.EXACT_TERM));
    }

    // ── caller filter, after expansion ──────────────────────────────────────

    @Test
    @DisplayName(
            "a permitted node reachable only through an unpermitted one stays; the unpermitted one appears nowhere")
    void filterRunsAfterExpansion() {
        // May read locations, may not create work orders: location_getprimary is reached only as the
        // producer of an input of workorder_createworkorder.
        ScopeSet scope = resolve("the work order", Set.of(AUTHENTICATED, LOCATION_READ));

        assertThat(scope.tools())
                .containsExactly(new ScopeTool("location_getprimary", ToolSource.DISCOVERED, 2, Access.READS));
        assertThat(scope.toString()).doesNotContain("workorder_createworkorder", "WorkorderFacadeTool");
        assertThat(scope.toolNames()).doesNotContain("workorder_createworkorder");
        // The walk did not depend on the caller: the entities, domains and states are all still there.
        assertThat(scope.reachedEntities()).containsExactly("estimate", "invoice");
        assertThat(scope.lifecycleStates()).containsExactly("workorder.COMPLETED", "workorder.DRAFT");
    }

    @Test
    @DisplayName("documents and screens the caller cannot see are dropped; public and AUTHENTICATED ones stay")
    void documentsAndScreensAreFiltered() {
        ScopeSet scope = resolve("the work order", Set.of(AUTHENTICATED));

        assertThat(scope.documentIds()).containsExactly("workorder.public", "billing.invoices");
        assertThat(scope.screenKeys()).containsExactly("workorders.wip");
        assertThat(scope.tools()).isEmpty();
        assertThat(scope.toString()).doesNotContain("workorder.status-lifecycle", "billing.secret", "workorders.list");
    }

    @Test
    @DisplayName("a caller with no codes sees only public documents and permission-free screens")
    void anonymousCaller() {
        ScopeSet scope = resolve("the work order", Set.of());

        assertThat(scope.tools()).isEmpty();
        assertThat(scope.documentIds()).containsExactly("workorder.public");
        assertThat(scope.screenKeys()).containsExactly("workorders.wip");
    }

    @Test
    @DisplayName("facade tools follow the turn's workflow state; discovered tools are fixed to IDLE")
    void workflowState() {
        ScopeResolver resolver = resolver(ScopeResolverFixtures.shadow(60));

        ScopeSet idle = resolver.resolve("the work order", EVERYTHING, WorkflowState.IDLE);
        ScopeSet creatingPo = resolver.resolve("the work order", EVERYTHING, WorkflowState.CREATING_PO);

        assertThat(names(idle.facadeTools())).containsExactly("WorkorderFacadeTool", "InvoiceFacadeTool");
        // WorkorderFacadeTool is VALID_IN IDLE only; InvoiceFacadeTool is valid in both.
        assertThat(names(creatingPo.facadeTools())).containsExactly("InvoiceFacadeTool");
        // The discovered set does not move with the turn's state, and never holds the non-IDLE one.
        assertThat(names(creatingPo.discoveredTools())).isEqualTo(names(idle.discoveredTools()));
        assertThat(idle.toolNames()).doesNotContain("workorder_notidle", "NoRowsFacadeTool");
    }

    // ── mode, errors, metrics ───────────────────────────────────────────────

    @Test
    @DisplayName("mode off resolves nothing and registers no meter")
    void offResolvesNothing() {
        ScopeResolver resolver = resolver(ScopeGraphProperties.off());

        assertThat(resolver.enabled()).isFalse();
        assertThat(resolver.resolve("the work order WO-20391", EVERYTHING, WorkflowState.IDLE))
                .isSameAs(ScopeSet.empty());
        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    @DisplayName("a resolver failure returns the empty scope and counts mcp.scope.errors; it never reaches the caller")
    void errorYieldsEmptyScope() {
        ScopeGraphProperties properties = ScopeResolverFixtures.shadow(60);
        ScopeGraphHolder broken = mock(ScopeGraphHolder.class);
        when(broken.current()).thenThrow(new IllegalStateException("snapshot unavailable"));
        ScopeResolver resolver = new ScopeResolver(broken, properties, new ScopeMetrics(properties, meters));

        ScopeSet scope = resolver.resolve("the work order", EVERYTHING, WorkflowState.IDLE);

        assertThat(scope).isSameAs(ScopeSet.empty());
        assertThat(scope.confidence()).isEqualTo(Confidence.NONE);
        assertThat(meters.get(ScopeMetrics.ERRORS).counter().count()).isEqualTo(1.0);
        assertThat(meters.get(ScopeMetrics.RESOLVED).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    @DisplayName("each resolved turn counts its confidence and records the size of each part of the scope")
    void metrics() {
        ScopeResolver resolver = resolver(ScopeResolverFixtures.shadow(60));

        ScopeSet high = resolver.resolve("the work order", EVERYTHING, WorkflowState.IDLE);
        resolver.resolve("the invoices", EVERYTHING, WorkflowState.IDLE);
        resolver.resolve("nothing to see", EVERYTHING, WorkflowState.IDLE);

        assertThat(resolvedCount("HIGH")).isEqualTo(1.0);
        assertThat(resolvedCount("LOW")).isEqualTo(1.0);
        assertThat(resolvedCount("NONE")).isEqualTo(1.0);
        assertThat(meters.get(ScopeMetrics.SIZE).tag("kind", "tools").summary().count())
                .isEqualTo(3);
        assertThat(meters.get(ScopeMetrics.SIZE).tag("kind", "tools").summary().max())
                .isEqualTo(high.tools().size());
        assertThat(meters.get(ScopeMetrics.SIZE)
                        .tag("kind", "entities")
                        .summary()
                        .max())
                .isEqualTo(3.0);
        assertThat(meters.get(ScopeMetrics.SIZE)
                        .tag("kind", "documents")
                        .summary()
                        .totalAmount())
                .isPositive();
        assertThat(meters.get(ScopeMetrics.SIZE)
                        .tag("kind", "screens")
                        .summary()
                        .count())
                .isEqualTo(3);
        assertThat(meters.get(ScopeMetrics.ERRORS).counter().count()).isZero();
        // Low cardinality: the only tag keys are the closed ones.
        assertThat(meters.getMeters().stream()
                        .filter(meter -> meter.getId().getName().startsWith("mcp.scope."))
                        .flatMap(meter -> meter.getId().getTags().stream())
                        .map(io.micrometer.core.instrument.Tag::getKey))
                .containsOnly("confidence", "kind", "in_scope", "consumer");
    }

    @Test
    @DisplayName("the matcher is built once per snapshot and rebuilt when the holder swaps the graph")
    void matcherFollowsTheSnapshot() {
        ScopeGraphProperties properties = ScopeResolverFixtures.shadow(60);
        ScopeGraphHolder holder = mock(ScopeGraphHolder.class);
        when(holder.current()).thenReturn(ScopeGraph.empty());
        ScopeResolver resolver = new ScopeResolver(holder, properties, new ScopeMetrics(properties, meters));

        // Before the first build the graph is empty: nothing to seed from.
        assertThat(resolver.resolve("the work order", EVERYTHING, WorkflowState.IDLE)
                        .confidence())
                .isEqualTo(Confidence.NONE);

        when(holder.current()).thenReturn(graph);
        assertThat(resolver.resolve("the work order", EVERYTHING, WorkflowState.IDLE)
                        .confidence())
                .isEqualTo(Confidence.HIGH);

        ScopeGraph.Builder other = ScopeGraph.builder();
        NodeId vehicle = other.node(NodeType.ENTITY, "vehicle");
        ScopeResolverFixtures.term(other, "en", "vehicle", false, vehicle);
        when(holder.current()).thenReturn(other.build(Instant.parse("2026-10-01T00:00:00Z")));
        assertThat(resolver.resolve("the work order", EVERYTHING, WorkflowState.IDLE)
                        .seeds())
                .isEmpty();
        assertThat(resolver.resolve("which vehicle?", EVERYTHING, WorkflowState.IDLE)
                        .seeds())
                .containsExactly(new Seed("vehicle", MatchKind.EXACT_TERM));
    }

    private double resolvedCount(String confidence) {
        return meters.get(ScopeMetrics.RESOLVED)
                .tag("confidence", confidence)
                .counter()
                .count();
    }
}

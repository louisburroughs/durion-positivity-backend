package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ScreenRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolPrerequisiteRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphFinding.Kind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ScopeGraphBuilderTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final ScopeGraphBuilder BUILDER = new ScopeGraphBuilder(Clock.fixed(NOW, ZoneOffset.UTC));

    private static NodeId entity(String key) {
        return NodeId.of(NodeType.ENTITY, key);
    }

    private static NodeId tool(String name) {
        return NodeId.of(NodeType.TOOL, name);
    }

    private static ScopeGraphBuildResult build(ScopeGraphSources sources) {
        return BUILDER.build(sources);
    }

    private static ScopeGraphSources withLexicon(EntityLexicon lexicon) {
        ScopeGraphSources base = ScopeGraphTestFixtures.sources();
        return new ScopeGraphSources(lexicon, base.catalog(), base.ragDocs(), base.schemaIndex(), base.glossaryTerms());
    }

    private static ScopeGraphSources withCatalog(ScopeGraphCatalog catalog) {
        ScopeGraphSources base = ScopeGraphTestFixtures.sources();
        return new ScopeGraphSources(base.lexicon(), catalog, base.ragDocs(), base.schemaIndex(), base.glossaryTerms());
    }

    private static ScopeGraphSources withRagDocs(List<StaticDocEntry> docs) {
        ScopeGraphSources base = ScopeGraphTestFixtures.sources();
        return new ScopeGraphSources(base.lexicon(), base.catalog(), docs, base.schemaIndex(), base.glossaryTerms());
    }

    private static ScopeGraphCatalog catalogWithTool(ToolRow extra) {
        ScopeGraphCatalog base = ScopeGraphTestFixtures.catalog();
        List<ToolRow> tools = new ArrayList<>(base.tools());
        tools.add(extra);
        return new ScopeGraphCatalog(
                tools, base.toolPermissions(), base.toolWorkflowStates(), base.toolPrerequisites(), base.screens());
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        private final ScopeGraphBuildResult result = build(ScopeGraphTestFixtures.sources());
        private final ScopeGraph graph = result.graph();

        @Test
        @DisplayName("sources in which everything resolves produce no finding")
        void noFindings() {
            assertThat(result.findings()).isEmpty();
            assertThat(result.strictFindings()).isEmpty();
            assertThat(result.unmappedTools()).isZero();
            assertThat(graph.builtAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("entities are owned by their domain and related with a label")
        void entities() {
            assertThat(graph.entityOptions()).containsExactly("estimate", "workorder");
            assertThat(graph.outgoing(entity("workorder"), EdgeType.OWNED_BY))
                    .extracting(Edge::to)
                    .containsExactly(NodeId.of(NodeType.DOMAIN, "workorder"));
            assertThat(graph.outgoing(entity("estimate"), EdgeType.RELATES_TO))
                    .singleElement()
                    .satisfies(edge -> {
                        assertThat(edge.to()).isEqualTo(entity("workorder"));
                        assertThat(edge.label()).isEqualTo("promotes_to");
                    });
        }

        @Test
        @DisplayName("terms denote their entity per language, and identifier patterns identify it")
        void termsAndIdentifiers() {
            assertThat(graph.incoming(entity("workorder"), EdgeType.DENOTES))
                    .extracting(edge -> edge.from().key())
                    .contains("en:work order", "en:workorder", "fr:bon de travail", "es:orden de trabajo");
            assertThat(graph.attributes(NodeId.of(NodeType.TERM, "fr:devis"), NodeAttributes.Term.class))
                    .hasValue(new NodeAttributes.Term("fr", "devis", false));
            assertThat(graph.incoming(entity("workorder"), EdgeType.IDENTIFIES))
                    .extracting(edge -> edge.from().key())
                    .containsExactly("workorder-number");
            assertThat(graph.attributes(
                            NodeId.of(NodeType.IDENTIFIER_PATTERN, "workorder-number"),
                            NodeAttributes.IdentifierPattern.class))
                    .hasValue(new NodeAttributes.IdentifierPattern("\\bWO-\\d{4}-\\d{4,}\\b"));
        }

        @Test
        @DisplayName("a facade acts on its lexicon entities with the declared access")
        void facadeActsOn() {
            assertThat(graph.outgoing(tool("WorkorderFacadeTool"), EdgeType.ACTS_ON))
                    .extracting(Edge::to, Edge::access)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(entity("workorder"), Access.READS),
                            org.assertj.core.groups.Tuple.tuple(entity("estimate"), Access.WRITES));
        }

        @Test
        @DisplayName("a discovered operation acts on the entity of its schemas: GET reads, anything else writes")
        void discoveredActsOn() {
            assertThat(graph.outgoing(tool("workorder_getworkorder"), EdgeType.ACTS_ON))
                    .extracting(Edge::to, Edge::access)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(entity("workorder"), Access.READS));
            // Matched through the page envelope's content element.
            assertThat(graph.outgoing(tool("workorder_listworkorders"), EdgeType.ACTS_ON))
                    .extracting(Edge::to, Edge::access)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(entity("workorder"), Access.READS));
            assertThat(graph.outgoing(tool("workorder_createworkorder"), EdgeType.ACTS_ON))
                    .extracting(Edge::to, Edge::access)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(entity("workorder"), Access.WRITES));
            // Matched by schema_patterns only.
            assertThat(graph.outgoing(tool("workorder_listworkorderparts"), EdgeType.ACTS_ON))
                    .extracting(Edge::to)
                    .containsExactly(entity("workorder"));
            assertThat(graph.outgoing(tool("workorder_updateestimate"), EdgeType.ACTS_ON))
                    .extracting(Edge::to, Edge::access)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(entity("estimate"), Access.WRITES));
        }

        @Test
        @DisplayName("tool attributes keep the source and the permission groups; disabled tools are not nodes")
        void toolAttributes() {
            NodeAttributes.Tool facade = graph.attributes(tool("WorkorderFacadeTool"), NodeAttributes.Tool.class)
                    .orElseThrow();
            assertThat(facade.source()).isEqualTo(NodeAttributes.ToolSource.FACADE);
            assertThat(facade.domain()).isEqualTo("workorder");
            assertThat(facade.permissionGroups())
                    .containsEntry("getWorkorder", Set.of("workorder:workorder:view"))
                    .containsEntry("getLaborAnalytics", Set.of("workorder:analytics:view", "location:read"));

            NodeAttributes.Tool discovered = graph.attributes(
                            tool("workorder_createworkorder"), NodeAttributes.Tool.class)
                    .orElseThrow();
            assertThat(discovered.source()).isEqualTo(NodeAttributes.ToolSource.DISCOVERED);
            assertThat(discovered.httpMethod()).isEqualTo("POST");
            // No permission rows: the tool stays excluded for every caller (ADR-0069 section 5.3).
            assertThat(discovered.permissionGroups()).isEmpty();

            assertThat(graph.contains(tool("RetiredFacadeTool"))).isFalse();
            assertThat(graph.contains(NodeId.of(NodeType.PERMISSION, "retired:thing:view")))
                    .isFalse();
        }

        @Test
        @DisplayName("tools require their permissions, are valid in their workflow states and feed each other")
        void toolEdges() {
            assertThat(graph.outgoing(tool("WorkorderFacadeTool"), EdgeType.REQUIRES))
                    .extracting(edge -> edge.to().key())
                    .containsExactlyInAnyOrder("workorder:workorder:view", "workorder:analytics:view", "location:read");
            assertThat(graph.outgoing(tool("WorkorderFacadeTool"), EdgeType.VALID_IN))
                    .extracting(edge -> edge.to().key())
                    .containsExactly("IDLE");
            assertThat(graph.outgoing(tool("workorder_listworkorders"), EdgeType.PRODUCES_INPUT_FOR))
                    .singleElement()
                    .satisfies(edge -> {
                        assertThat(edge.to()).isEqualTo(tool("workorder_getworkorder"));
                        assertThat(edge.label()).isEqualTo("id");
                    });
        }

        @Test
        @DisplayName("lifecycle states come from the status and state enums of the entity's listed schemas")
        void lifecycleStates() {
            assertThat(graph.outgoing(entity("workorder"), EdgeType.HAS_STATE))
                    .extracting(edge -> edge.to().key())
                    .containsExactlyInAnyOrder(
                            "workorder.DRAFT",
                            "workorder.APPROVED",
                            "workorder.IN_PROGRESS",
                            "workorder.COMPLETED",
                            "workorder.UNPAID",
                            "workorder.PAID");
            assertThat(graph.outgoing(entity("estimate"), EdgeType.HAS_STATE))
                    .extracting(edge -> edge.to().key())
                    .containsExactlyInAnyOrder(
                            "estimate.DRAFT", "estimate.OPEN", "estimate.APPROVED", "estimate.DECLINED");
        }

        @Test
        @DisplayName("TRANSITIONS_TO stays empty until lifecycles.yaml exists")
        void noTransitions() {
            assertThat(graph.edges()).noneMatch(edge -> edge.type() == EdgeType.TRANSITIONS_TO);
        }

        @Test
        @DisplayName("RAG documents are about their entities, require their permissions and carry their scope")
        void ragDocs() {
            NodeId doc = NodeId.of(NodeType.RAG_DOC, "workorder.status-lifecycle");
            assertThat(graph.outgoing(doc, EdgeType.ABOUT))
                    .extracting(Edge::to)
                    .containsExactly(entity("estimate"), entity("workorder"));
            assertThat(graph.outgoing(doc, EdgeType.REQUIRES))
                    .extracting(edge -> edge.to().key())
                    .containsExactly("workorder:workorder:view");
            assertThat(graph.attributes(doc, NodeAttributes.RagDoc.class))
                    .hasValue(new NodeAttributes.RagDoc("workorder", List.of("workorder:workorder:view"), false));
        }

        @Test
        @DisplayName("entities: [none] is a platform-wide document: no ABOUT edge and no finding")
        void platformWideDocument() {
            NodeId glossary = NodeId.of(NodeType.RAG_DOC, "glossary.identifiers");

            assertThat(graph.outgoing(glossary, EdgeType.ABOUT)).isEmpty();
            assertThat(graph.attributes(glossary, NodeAttributes.RagDoc.class)).hasValueSatisfying(attributes -> {
                assertThat(attributes.platformWide()).isTrue();
                assertThat(attributes.ragScope()).isEqualTo("master");
            });
            assertThat(graph.contains(entity("none"))).isFalse();
        }

        @Test
        @DisplayName("a screen shows the entities that list it, else the entities of its own domain")
        void screens() {
            assertThat(graph.outgoing(NodeId.of(NodeType.SCREEN, "workorders.list"), EdgeType.SHOWS))
                    .extracting(Edge::to)
                    .containsExactly(entity("workorder"));
            assertThat(graph.outgoing(NodeId.of(NodeType.SCREEN, "workorders.wip"), EdgeType.SHOWS))
                    .extracting(Edge::to)
                    .containsExactly(entity("estimate"), entity("workorder"));
            assertThat(graph.outgoing(NodeId.of(NodeType.SCREEN, "workorders.list"), EdgeType.REQUIRES))
                    .extracting(edge -> edge.to().key())
                    .containsExactly("workorder:workorder:view");
            assertThat(graph.outgoing(NodeId.of(NodeType.SCREEN, "workorders.wip"), EdgeType.REQUIRES))
                    .isEmpty();
            assertThat(graph.attributes(NodeId.of(NodeType.SCREEN, "workorders.list"), NodeAttributes.Screen.class))
                    .hasValue(new NodeAttributes.Screen(
                            "Work Orders", "/workorders", "workorder", "workorder:workorder:view"));
        }

        @Test
        @DisplayName("domains cover both vocabularies and the lexicon maps between them")
        void domains() {
            assertThat(graph.domainOptions()).contains("workorder", "date-window", "shopmanager", "master");
            assertThat(graph.ragScopeOf("shop-manager")).isEqualTo("shopmanager");
            assertThat(graph.ragScopeOf("workorder")).isEqualTo("workorder");
        }

        @Test
        @DisplayName("a glossary phrase is a term that denotes the entity whose own term it contains")
        void glossaryTerms() {
            NodeId phrase = NodeId.of(NodeType.TERM, "en:open work orders running late");
            assertThat(graph.outgoing(phrase, EdgeType.DENOTES))
                    .extracting(Edge::to)
                    .containsExactly(entity("workorder"));
            assertThat(graph.attributes(phrase, NodeAttributes.Term.class))
                    .hasValueSatisfying(term -> assertThat(term.glossary()).isTrue());
            NodeId unrelated = NodeId.of(NodeType.TERM, "en:who owes us the most money");
            assertThat(graph.contains(unrelated)).isTrue();
            assertThat(graph.outgoing(unrelated)).isEmpty();
        }

        @Test
        @DisplayName("the same sources read in another order build the same graph")
        void hashIsStableAcrossSourceOrdering() {
            ScopeGraphSources base = ScopeGraphTestFixtures.sources();
            ScopeGraphCatalog catalog = base.catalog();
            ScopeGraphSources reversed = new ScopeGraphSources(
                    new EntityLexicon(
                            base.lexicon().domainScopes(),
                            base.lexicon().entities().reversed(),
                            base.lexicon().unscopedTools()),
                    new ScopeGraphCatalog(
                            catalog.tools().reversed(),
                            catalog.toolPermissions().reversed(),
                            catalog.toolWorkflowStates().reversed(),
                            catalog.toolPrerequisites().reversed(),
                            catalog.screens().reversed()),
                    base.ragDocs().reversed(),
                    base.schemaIndex(),
                    base.glossaryTerms().reversed());

            assertThat(build(reversed).graph().contentHash()).isEqualTo(graph.contentHash());
        }

        @Test
        @DisplayName("changing one edge of the sources changes the hash")
        void hashChangesWhenAnEdgeChanges() {
            EntityLexicon changed = ScopeGraphTestFixtures.lexiconWith(
                    "{tool: WorkorderFacadeTool, access: reads}", "{tool: WorkorderFacadeTool, access: writes}");

            assertThat(build(withLexicon(changed)).graph().contentHash()).isNotEqualTo(graph.contentHash());
        }
    }

    @Nested
    @DisplayName("validation findings: reported, never thrown")
    class Findings {

        @Test
        @DisplayName("FACADE_TOOL_WITHOUT_ENTITY: an enabled facade acts on nothing and is not unscoped")
        void facadeToolWithoutEntity() {
            ScopeGraphBuildResult result =
                    build(withCatalog(catalogWithTool(ScopeGraphTestFixtures.facade("InvoiceFacadeTool", "invoice"))));

            assertThat(result.findings())
                    .containsExactly(new ScopeGraphFinding(Kind.FACADE_TOOL_WITHOUT_ENTITY, "InvoiceFacadeTool"));
            assertThat(result.strictFindings()).hasSize(1);
            assertThat(result.graph().contains(tool("InvoiceFacadeTool"))).isTrue();
        }

        @Test
        @DisplayName("DISCOVERED_TOOL_UNMAPPED: kept with its domain only, counted, not strict")
        void discoveredToolUnmapped() {
            ScopeGraphBuildResult result = build(withCatalog(
                    catalogWithTool(ScopeGraphTestFixtures.discovered("workorder_listtechnicians", "GET"))));

            assertThat(result.findings())
                    .containsExactly(new ScopeGraphFinding(Kind.DISCOVERED_TOOL_UNMAPPED, "workorder_listtechnicians"));
            assertThat(result.strictFindings()).isEmpty();
            assertThat(result.unmappedTools()).isEqualTo(1);
            assertThat(result.graph().outgoing(tool("workorder_listtechnicians"), EdgeType.ACTS_ON))
                    .isEmpty();
            assertThat(result.graph().attributes(tool("workorder_listtechnicians"), NodeAttributes.Tool.class))
                    .hasValueSatisfying(
                            attributes -> assertThat(attributes.domain()).isEqualTo("workorder"));
        }

        @Test
        @DisplayName("before discovery has indexed any spec, every discovered operation is unmapped, not an error")
        void emptySchemaIndexDegrades() {
            ScopeGraphSources base = ScopeGraphTestFixtures.sources();

            ScopeGraphBuildResult result = build(new ScopeGraphSources(
                    base.lexicon(), base.catalog(), base.ragDocs(), OpenApiSchemaIndex.empty(), base.glossaryTerms()));

            assertThat(result.count(Kind.DISCOVERED_TOOL_UNMAPPED)).isEqualTo(6);
            assertThat(result.count(Kind.UNKNOWN_SCHEMA)).isEqualTo(2);
            assertThat(result.count(Kind.SCHEMA_PATTERN_WITHOUT_MATCH)).isEqualTo(1);
            assertThat(result.graph().nodesOfType(NodeType.TOOL)).hasSize(8);
            // The facade edges come from the lexicon alone and survive.
            assertThat(result.graph().outgoing(tool("WorkorderFacadeTool"), EdgeType.ACTS_ON))
                    .hasSize(2);
        }

        @Test
        @DisplayName("RAG_DOC_WITHOUT_ENTITIES: a document declares neither an entity nor [none]")
        void ragDocWithoutEntities() {
            List<StaticDocEntry> docs = new ArrayList<>(ScopeGraphTestFixtures.ragDocs());
            docs.add(new StaticDocEntry("order.guide", "classpath:rag/order.md", "workorder", List.of()));

            ScopeGraphBuildResult result = build(withRagDocs(docs));

            assertThat(result.findings())
                    .containsExactly(new ScopeGraphFinding(Kind.RAG_DOC_WITHOUT_ENTITIES, "order.guide"));
            assertThat(result.graph().contains(NodeId.of(NodeType.RAG_DOC, "order.guide")))
                    .isTrue();
        }

        @Test
        @DisplayName("UNKNOWN_ENTITY: a document names an entity the lexicon does not define; the edge is dropped")
        void unknownEntityOnRagDoc() {
            List<StaticDocEntry> docs = List.of(
                    new StaticDocEntry(
                            "order.guide",
                            "classpath:rag/order.md",
                            "workorder",
                            List.of(),
                            List.of("workorder", "invoice")),
                    new StaticDocEntry(
                            "shopmanager.guide",
                            "classpath:rag/shop.md",
                            "shopmanager",
                            List.of(),
                            List.of("none", "x")));

            ScopeGraphBuildResult result = build(withRagDocs(docs));

            assertThat(result.findings())
                    .containsExactly(
                            new ScopeGraphFinding(Kind.UNKNOWN_ENTITY, "order.guide -> invoice"),
                            new ScopeGraphFinding(Kind.UNKNOWN_ENTITY, "shopmanager.guide -> none"),
                            new ScopeGraphFinding(Kind.UNKNOWN_ENTITY, "shopmanager.guide -> x"));
            assertThat(result.graph().outgoing(NodeId.of(NodeType.RAG_DOC, "order.guide"), EdgeType.ABOUT))
                    .extracting(Edge::to)
                    .containsExactly(entity("workorder"));
        }

        @Test
        @DisplayName("UNKNOWN_ENTITY: a relation points at an entity the lexicon does not define")
        void unknownEntityOnRelation() {
            ScopeGraphBuildResult result = build(withLexicon(ScopeGraphTestFixtures.lexiconWith(
                    "{entity: estimate, label: promoted_from}", "{entity: invoice, label: billed_by}")));

            assertThat(result.findings())
                    .containsExactly(new ScopeGraphFinding(Kind.UNKNOWN_ENTITY, "workorder -> invoice"));
            assertThat(result.graph().outgoing(entity("workorder"), EdgeType.RELATES_TO))
                    .isEmpty();
        }

        @Test
        @DisplayName("UNKNOWN_DOMAIN: an entity's domain, or a domain_scopes key, is no known domain")
        void unknownDomain() {
            ScopeGraphBuildResult entityDomain = build(withLexicon(ScopeGraphTestFixtures.lexiconWith(
                    "  - key: estimate\n    domain: workorder", "  - key: estimate\n    domain: estimating")));
            assertThat(entityDomain.findings())
                    .containsExactly(new ScopeGraphFinding(Kind.UNKNOWN_DOMAIN, "estimate -> estimating"));
            assertThat(entityDomain.graph().outgoing(entity("estimate"), EdgeType.OWNED_BY))
                    .isEmpty();

            ScopeGraphBuildResult scopeKey = build(withLexicon(
                    ScopeGraphTestFixtures.lexiconWith("shop-manager: shopmanager", "shopmgr: shopmanager")));
            assertThat(scopeKey.findings())
                    .containsExactly(new ScopeGraphFinding(Kind.UNKNOWN_DOMAIN, "domain_scopes -> shopmgr"));
        }

        @Test
        @DisplayName("UNKNOWN_RAG_SCOPE: a domain_scopes value is no rag_scope of a preload document")
        void unknownRagScope() {
            ScopeGraphBuildResult result = build(
                    withLexicon(ScopeGraphTestFixtures.lexiconWith("shop-manager: shopmanager", "shop-manager: shop")));

            assertThat(result.findings())
                    .containsExactly(new ScopeGraphFinding(Kind.UNKNOWN_RAG_SCOPE, "shop-manager -> shop"));
            assertThat(result.graph().ragScopeOf("shop-manager")).isEqualTo("shop-manager");
        }

        @Test
        @DisplayName("UNKNOWN_SCHEMA: a listed schema is in no indexed spec, which also costs its states")
        void unknownSchema() {
            ScopeGraphBuildResult result = build(withLexicon(ScopeGraphTestFixtures.lexiconWith(
                    "schemas: ['workorder:EstimateResponse']", "schemas: ['workorder:EstimateDto']")));

            assertThat(result.strictFindings())
                    .containsExactly(new ScopeGraphFinding(Kind.UNKNOWN_SCHEMA, "estimate -> workorder:EstimateDto"));
            assertThat(result.graph().outgoing(entity("estimate"), EdgeType.HAS_STATE))
                    .isEmpty();
            // The operations that exchanged the renamed DTO detach and degrade to their domain.
            assertThat(result.findings())
                    .contains(
                            new ScopeGraphFinding(Kind.DISCOVERED_TOOL_UNMAPPED, "workorder_getestimate"),
                            new ScopeGraphFinding(Kind.DISCOVERED_TOOL_UNMAPPED, "workorder_updateestimate"));
        }

        @Test
        @DisplayName("SCHEMA_PATTERN_WITHOUT_MATCH: a pattern matches no schema")
        void schemaPatternWithoutMatch() {
            ScopeGraphBuildResult result = build(withLexicon(ScopeGraphTestFixtures.lexiconWith(
                    "schema_patterns: ['workorder:Workorder(Create|Part).*']",
                    "schema_patterns: ['workorder:Job.*']")));

            assertThat(result.strictFindings())
                    .containsExactly(
                            new ScopeGraphFinding(Kind.SCHEMA_PATTERN_WITHOUT_MATCH, "workorder -> workorder:Job.*"));
        }

        @Test
        @DisplayName("UNKNOWN_FACADE_TOOL: a facade_tools entry is no enabled facade (absent, disabled or discovered)")
        void unknownFacadeTool() {
            for (String name : List.of("JobFacadeTool", "RetiredFacadeTool", "workorder_getworkorder")) {
                ScopeGraphBuildResult result = build(withLexicon(ScopeGraphTestFixtures.lexiconWith(
                        "{tool: WorkorderFacadeTool, access: writes}", "{tool: " + name + ", access: writes}")));

                assertThat(result.findings())
                        .as(name)
                        .containsExactly(new ScopeGraphFinding(Kind.UNKNOWN_FACADE_TOOL, "estimate -> " + name));
            }
        }

        @Test
        @DisplayName("UNKNOWN_UNSCOPED_TOOL: an unscoped_tools entry is no enabled tool")
        void unknownUnscopedTool() {
            ScopeGraphBuildResult result = build(withLexicon(ScopeGraphTestFixtures.lexiconWith(
                    "unscoped_tools: [DateWindowFacadeTool]",
                    "unscoped_tools: [DateWindowFacadeTool, ExaWebSearchTool]")));

            assertThat(result.findings())
                    .containsExactly(new ScopeGraphFinding(Kind.UNKNOWN_UNSCOPED_TOOL, "ExaWebSearchTool"));
        }

        @Test
        @DisplayName("UNKNOWN_SCREEN: a screens entry is no registered screen")
        void unknownScreen() {
            ScopeGraphBuildResult result = build(withLexicon(
                    ScopeGraphTestFixtures.lexiconWith("screens: [workorders.list]", "screens: [workorders.board]")));

            assertThat(result.findings())
                    .containsExactly(new ScopeGraphFinding(Kind.UNKNOWN_SCREEN, "workorder -> workorders.board"));
        }

        @Test
        @DisplayName("MISSING_LANGUAGE_TERMS: an entity lacks a term in one of en, fr, es")
        void missingLanguageTerms() {
            ScopeGraphBuildResult result = build(withLexicon(ScopeGraphTestFixtures.lexiconWith(
                    "      fr: [devis]\n      es: [presupuesto]\n", "      fr: []\n")));

            assertThat(result.findings())
                    .containsExactly(
                            new ScopeGraphFinding(Kind.MISSING_LANGUAGE_TERMS, "estimate:es"),
                            new ScopeGraphFinding(Kind.MISSING_LANGUAGE_TERMS, "estimate:fr"));
        }

        @Test
        @DisplayName("PREREQUISITE_TOOL_MISSING: a prerequisite names a tool that is not enabled; not strict")
        void prerequisiteToolMissing() {
            ScopeGraphCatalog base = ScopeGraphTestFixtures.catalog();
            ScopeGraphBuildResult result = build(withCatalog(new ScopeGraphCatalog(
                    base.tools(),
                    base.toolPermissions(),
                    base.toolWorkflowStates(),
                    List.of(new ToolPrerequisiteRow(
                            "workorder_listwip", "locationId", "location_getcurrentuserprimarylocation")),
                    base.screens())));

            assertThat(result.findings())
                    .containsExactly(new ScopeGraphFinding(
                            Kind.PREREQUISITE_TOOL_MISSING,
                            "location_getcurrentuserprimarylocation -> workorder_listwip"));
            assertThat(result.strictFindings()).isEmpty();
        }

        @Test
        @DisplayName("every finding kind is exercised by this class")
        void everyKindHasATest() {
            // A new Kind without a test here fails, so the list cannot drift from the enum.
            assertThat(Kind.values())
                    .containsExactlyInAnyOrder(
                            Kind.FACADE_TOOL_WITHOUT_ENTITY,
                            Kind.DISCOVERED_TOOL_UNMAPPED,
                            Kind.RAG_DOC_WITHOUT_ENTITIES,
                            Kind.UNKNOWN_ENTITY,
                            Kind.UNKNOWN_DOMAIN,
                            Kind.UNKNOWN_RAG_SCOPE,
                            Kind.UNKNOWN_SCHEMA,
                            Kind.SCHEMA_PATTERN_WITHOUT_MATCH,
                            Kind.UNKNOWN_FACADE_TOOL,
                            Kind.UNKNOWN_UNSCOPED_TOOL,
                            Kind.UNKNOWN_SCREEN,
                            Kind.MISSING_LANGUAGE_TERMS,
                            Kind.PREREQUISITE_TOOL_MISSING);
        }

        @Test
        @DisplayName("a screen of a domain with no entity shows nothing and is not a finding")
        void screenWithoutEntities() {
            ScopeGraphCatalog base = ScopeGraphTestFixtures.catalog();
            List<ScreenRow> screens = new ArrayList<>(base.screens());
            screens.add(new ScreenRow("invoices.list", "invoice", null, "/invoices", "Invoices"));

            ScopeGraphBuildResult result = build(withCatalog(new ScopeGraphCatalog(
                    base.tools(),
                    base.toolPermissions(),
                    base.toolWorkflowStates(),
                    base.toolPrerequisites(),
                    screens)));

            assertThat(result.findings()).isEmpty();
            assertThat(result.graph().outgoing(NodeId.of(NodeType.SCREEN, "invoices.list")))
                    .isEmpty();
        }
    }
}

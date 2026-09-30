package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.scopegraph.NodeAttributes.ToolSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A hand-drawn graph for the resolver, matcher and caller-filter tests. It is built with the graph's
 * own builder, not from the lexicon fixture, so each test can see every node and edge it relies on.
 */
public final class ScopeResolverFixtures {

    public static final Instant BUILT_AT = Instant.parse("2026-09-30T12:00:00Z");

    public static final String WORKORDER_VIEW = "workorder:workorder:view";
    public static final String WORKORDER_CREATE = "workorder:workorder:create";
    public static final String WORKORDER_LIST = "workorder:workorder:list";
    public static final String ANALYTICS_VIEW = "workorder:analytics:view";
    public static final String LOCATION_READ = "location:read";
    public static final String INVOICE_VIEW = "billing:invoice:view";
    public static final String AUTHENTICATED = "AUTHENTICATED";

    private ScopeResolverFixtures() {}

    public static ScopeGraphProperties shadow(int maxNodes) {
        return new ScopeGraphProperties(ScopeGraphProperties.Mode.SHADOW, List.of(), maxNodes, 0, 0);
    }

    /** A holder whose current snapshot is {@code graph}, built on the caller's thread. */
    public static ScopeGraphHolder holderOf(ScopeGraph graph, ScopeGraphProperties properties, MeterRegistry meters) {
        ScopeGraphHolder holder = new ScopeGraphHolder(
                properties, () -> new ScopeGraphBuildResult(graph, List.of()), Runnable::run, meters);
        holder.rebuild();
        return holder;
    }

    /** A resolver in {@code properties}' mode over the fixture graph. */
    public static ScopeResolver resolver(ScopeGraphProperties properties, MeterRegistry meters) {
        return new ScopeResolver(
                holderOf(graph(), properties, meters), properties, new ScopeMetrics(properties, meters));
    }

    public static ScopeGraph graph() {
        ScopeGraph.Builder graph = ScopeGraph.builder();

        NodeId workorder = entity(graph, "workorder", "workorder");
        NodeId estimate = entity(graph, "estimate", "workorder");
        NodeId invoice = entity(graph, "invoice", "billing");
        NodeId customer = entity(graph, "customer", "crm");
        NodeId order = entity(graph, "order", "orders");
        NodeId part = entity(graph, "part", "inventory");

        term(graph, "en", "work order", false, workorder);
        term(graph, "en", "workorder", false, workorder);
        term(graph, "fr", "bon de travail", false, workorder);
        term(graph, "fr", "ordre de réparation", false, workorder);
        term(graph, "es", "orden de trabajo", false, workorder);
        term(graph, "es", "orden de reparación", false, workorder);
        term(graph, "en", "estimate", false, estimate);
        term(graph, "en", "quote", false, estimate);
        term(graph, "fr", "devis", false, estimate);
        term(graph, "es", "presupuesto", false, estimate);
        term(graph, "en", "invoice", false, invoice);
        term(graph, "fr", "facture", false, invoice);
        term(graph, "es", "factura", false, invoice);
        term(graph, "en", "customer", false, customer);
        term(graph, "fr", "client", false, customer);
        term(graph, "es", "cliente", false, customer);
        term(graph, "en", "order", false, order);
        term(graph, "fr", "commande", false, order);
        term(graph, "es", "pedido", false, order);
        term(graph, "en", "part", false, part);
        term(graph, "fr", "pièce", false, part);
        term(graph, "es", "pieza", false, part);
        term(graph, "fr", "prix", false, part);
        // One phrase, two entities.
        term(graph, "en", "ticket", false, workorder, invoice);
        // Glossary phrases: one that names an entity, one that names none.
        term(graph, "en", "best customers", true, customer);
        term(graph, "en", "who owes us the most money", true);

        graph.edge(
                EdgeType.IDENTIFIES,
                graph.node(
                        NodeType.IDENTIFIER_PATTERN,
                        "workorder-number",
                        new NodeAttributes.IdentifierPattern("\\bWO-\\d{4,}\\b")),
                workorder);

        graph.edge(EdgeType.RELATES_TO, estimate, workorder, "promotes_to");
        graph.edge(EdgeType.RELATES_TO, workorder, invoice, "billed_by");
        graph.edge(EdgeType.RELATES_TO, invoice, customer, "billed_to");

        NodeId idle = graph.node(NodeType.WORKFLOW_STATE, "IDLE");
        NodeId creatingPo = graph.node(NodeType.WORKFLOW_STATE, "CREATING_PO");

        NodeId workorderFacade = tool(
                graph,
                "WorkorderFacadeTool",
                ToolSource.FACADE,
                "workorder",
                null,
                groups(
                        "getWorkorder",
                        Set.of(WORKORDER_VIEW),
                        "getLaborAnalytics",
                        Set.of(ANALYTICS_VIEW, LOCATION_READ)),
                idle);
        graph.edge(EdgeType.ACTS_ON, workorderFacade, workorder, "reads");
        graph.edge(EdgeType.ACTS_ON, workorderFacade, estimate, "writes");

        NodeId invoiceFacade = tool(
                graph,
                "InvoiceFacadeTool",
                ToolSource.FACADE,
                "billing",
                null,
                groups("getInvoice", Set.of(INVOICE_VIEW)),
                idle,
                creatingPo);
        graph.edge(EdgeType.ACTS_ON, invoiceFacade, invoice, "reads");

        NodeId noRowsFacade = tool(graph, "NoRowsFacadeTool", ToolSource.FACADE, "workorder", null, Map.of(), idle);
        graph.edge(EdgeType.ACTS_ON, noRowsFacade, workorder, "reads");

        NodeId getWorkorder = tool(
                graph,
                "workorder_getworkorder",
                ToolSource.DISCOVERED,
                "workorder",
                "GET",
                groups(WORKORDER_VIEW, Set.of(WORKORDER_VIEW)),
                idle);
        graph.edge(EdgeType.ACTS_ON, getWorkorder, workorder, "reads");

        NodeId createWorkorder = tool(
                graph,
                "workorder_createworkorder",
                ToolSource.DISCOVERED,
                "workorder",
                "POST",
                groups(WORKORDER_CREATE, Set.of(WORKORDER_CREATE)),
                idle);
        graph.edge(EdgeType.ACTS_ON, createWorkorder, workorder, "writes");

        NodeId listWorkorders = tool(
                graph,
                "workorder_listworkorders",
                ToolSource.DISCOVERED,
                "workorder",
                "GET",
                groups(WORKORDER_LIST, Set.of(WORKORDER_LIST), WORKORDER_VIEW, Set.of(WORKORDER_VIEW)),
                idle);
        graph.edge(EdgeType.ACTS_ON, listWorkorders, workorder, "reads");
        graph.edge(EdgeType.PRODUCES_INPUT_FOR, listWorkorders, getWorkorder, "id");

        // Acts on no entity: reachable only as the producer of an input of the create operation.
        NodeId getPrimaryLocation = tool(
                graph,
                "location_getprimary",
                ToolSource.DISCOVERED,
                "location",
                "GET",
                groups(LOCATION_READ, Set.of(LOCATION_READ)),
                idle);
        graph.edge(EdgeType.PRODUCES_INPUT_FOR, getPrimaryLocation, createWorkorder, "locationId");

        // Discovered, but not valid in IDLE: the discovered path fixes IDLE, so it is never in scope.
        NodeId notIdle = tool(
                graph,
                "workorder_notidle",
                ToolSource.DISCOVERED,
                "workorder",
                "GET",
                groups(WORKORDER_VIEW, Set.of(WORKORDER_VIEW)),
                creatingPo);
        graph.edge(EdgeType.ACTS_ON, notIdle, workorder, "reads");

        ragDoc(graph, "workorder.status-lifecycle", "workorder", List.of(WORKORDER_VIEW), workorder);
        ragDoc(graph, "workorder.public", "workorder", List.of(), workorder);
        ragDoc(graph, "billing.invoices", "billing", List.of(AUTHENTICATED), invoice);
        ragDoc(graph, "billing.secret", "billing", List.of("billing:admin"), invoice);

        screen(graph, "workorders.list", "Work Orders", "/workorders", WORKORDER_VIEW, workorder);
        screen(graph, "workorders.wip", "Work In Progress", "/workorders/wip", null, workorder);

        graph.edge(EdgeType.HAS_STATE, workorder, graph.node(NodeType.LIFECYCLE_STATE, "workorder.DRAFT"));
        graph.edge(EdgeType.HAS_STATE, workorder, graph.node(NodeType.LIFECYCLE_STATE, "workorder.COMPLETED"));
        graph.edge(EdgeType.HAS_STATE, invoice, graph.node(NodeType.LIFECYCLE_STATE, "invoice.PAID"));

        return graph.build(BUILT_AT);
    }

    private static NodeId entity(ScopeGraph.Builder graph, String key, String domain) {
        NodeId entity = graph.node(NodeType.ENTITY, key);
        graph.edge(EdgeType.OWNED_BY, entity, graph.node(NodeType.DOMAIN, domain));
        return entity;
    }

    static void term(ScopeGraph.Builder graph, String language, String phrase, boolean glossary, NodeId... entities) {
        NodeId term = graph.node(
                NodeType.TERM,
                ScopeGraphBuilder.termKey(language, phrase),
                new NodeAttributes.Term(language, phrase, glossary));
        for (NodeId entity : entities) {
            graph.edge(EdgeType.DENOTES, term, entity);
        }
    }

    private static NodeId tool(
            ScopeGraph.Builder graph,
            String name,
            ToolSource source,
            String domain,
            String httpMethod,
            Map<String, Set<String>> groups,
            NodeId... validIn) {
        NodeId tool = graph.node(NodeType.TOOL, name, new NodeAttributes.Tool(source, domain, httpMethod, groups));
        graph.node(NodeType.DOMAIN, domain);
        groups.values()
                .forEach(codes -> codes.forEach(
                        code -> graph.edge(EdgeType.REQUIRES, tool, graph.node(NodeType.PERMISSION, code))));
        for (NodeId state : validIn) {
            graph.edge(EdgeType.VALID_IN, tool, state);
        }
        return tool;
    }

    private static Map<String, Set<String>> groups(Object... groupThenCodes) {
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        for (int index = 0; index < groupThenCodes.length; index += 2) {
            @SuppressWarnings("unchecked")
            Set<String> codes = (Set<String>) groupThenCodes[index + 1];
            groups.put((String) groupThenCodes[index], codes);
        }
        return groups;
    }

    private static void ragDoc(
            ScopeGraph.Builder graph, String id, String scope, List<String> permissions, NodeId about) {
        NodeId doc = graph.node(NodeType.RAG_DOC, id, new NodeAttributes.RagDoc(scope, permissions, false));
        permissions.forEach(code -> graph.edge(EdgeType.REQUIRES, doc, graph.node(NodeType.PERMISSION, code)));
        graph.edge(EdgeType.ABOUT, doc, about);
    }

    private static void screen(
            ScopeGraph.Builder graph, String key, String title, String url, String requiredPerm, NodeId shows) {
        NodeId screen =
                graph.node(NodeType.SCREEN, key, new NodeAttributes.Screen(title, url, "workorder", requiredPerm));
        if (requiredPerm != null) {
            graph.edge(EdgeType.REQUIRES, screen, graph.node(NodeType.PERMISSION, requiredPerm));
        }
        graph.edge(EdgeType.SHOWS, screen, shows);
    }
}

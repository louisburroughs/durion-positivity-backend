package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.scopegraph.NodeAttributes.ToolSource;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Relation;
import com.positivity.mcp.internal.scopegraph.ScopeSet.ScopeTool;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;

/**
 * ADR-0069 §7 (spec §2.10): renders the scope card, a compact plain-text block of platform
 * definitions appended to the system prompt as the {@value #LAYER} layer.
 *
 * <p>The card is rendered from the caller-filtered {@link ScopeSet} and graph lookups only. It
 * never sees the message, so it cannot echo user text; it names no {@code Term} or {@code
 * IdentifierPattern}; and an entity, relation or lifecycle state appears only when at least one
 * Tool, RagDoc or Screen the caller passed §5.3 for connects to it (a relation only when both
 * entities qualify). A permission code is printed only for a tool in the scope set, and only the
 * codes the caller holds, so the card never names a permission the caller lacks. Screens are
 * printed with their {@code url_template}.
 *
 * <p>Budget: {@code mcp.scope-graph.card-token-budget}, estimated with the module's own token count
 * ({@link SimpleChatRuleCatalog#tokenize}), applied by dropping whole lines from the end. The
 * card orients the model; it grants nothing.
 */
public final class ScopeCardRenderer {

    /** The system-prompt layer name reported in {@code promptLayers}. */
    public static final String LAYER = "SCOPE_CARD";

    static final String HEADER = "SCOPE (platform definitions for this question; orientation only, grants nothing)";
    private static final String CONTINUATION = "  ";
    private static final String LINE_SEPARATOR = "\n";

    private ScopeCardRenderer() {}

    /**
     * The card for {@code scope}, or empty when no entity qualifies or the budget leaves no room
     * for the entities line. Confidence is the caller's concern ({@link ScopeConsumers}).
     *
     * @param graph the snapshot the scope was resolved against
     * @param callerPermissionCodes the caller's codes, used only to choose which of a tool's own
     *     codes to print
     * @param tokenBudget the largest estimated token count the card may have
     */
    public static @NonNull Optional<String> render(
            @NonNull ScopeSet scope,
            @NonNull ScopeGraph graph,
            @NonNull Set<String> callerPermissionCodes,
            int tokenBudget) {
        Set<String> qualified = qualifiedEntities(scope, graph);
        if (qualified.isEmpty()) {
            return Optional.empty();
        }
        List<String> lines = new ArrayList<>();
        lines.add(entitiesLine(scope, graph, qualified));
        relationsLine(scope, qualified).ifPresent(lines::add);
        lines.addAll(sectionLines("States: ", statesEntries(scope, graph, qualified)));
        lines.addAll(sectionLines("Actions: ", actionEntries(scope, graph, qualified, callerPermissionCodes)));
        lines.addAll(sectionLines("Screens: ", screenEntries(scope, graph)));
        return Optional.ofNullable(fit(lines, tokenBudget));
    }

    /** The module's token estimate, the one both session managers use. */
    public static int estimateTokens(@NonNull String text) {
        return SimpleChatRuleCatalog.tokenize(SimpleChatRuleCatalog.normalize(text))
                .size();
    }

    /**
     * §7: an entity qualifies when a Tool ({@code ACTS_ON}), RagDoc ({@code ABOUT}) or Screen
     * ({@code SHOWS}) in the scope set connects to it, and it is itself in scope.
     */
    private static Set<String> qualifiedEntities(ScopeSet scope, ScopeGraph graph) {
        Set<String> inScope = new LinkedHashSet<>(scope.entities());
        Set<String> connected = new TreeSet<>();
        for (ScopeTool tool : scope.tools()) {
            targets(graph, NodeId.of(NodeType.TOOL, tool.name()), EdgeType.ACTS_ON, connected);
        }
        for (String document : scope.documentIds()) {
            targets(graph, NodeId.of(NodeType.RAG_DOC, document), EdgeType.ABOUT, connected);
        }
        for (String screen : scope.screenKeys()) {
            targets(graph, NodeId.of(NodeType.SCREEN, screen), EdgeType.SHOWS, connected);
        }
        inScope.retainAll(connected);
        return inScope;
    }

    private static String entitiesLine(ScopeSet scope, ScopeGraph graph, Set<String> qualified) {
        List<String> entries = new ArrayList<>();
        for (String entity : qualified) {
            String domain = graph.outgoing(NodeId.of(NodeType.ENTITY, entity), EdgeType.OWNED_BY).stream()
                    .map(edge -> edge.to().key())
                    .findFirst()
                    .orElse(null);
            entries.add(domain == null ? entity : entity + " (domain " + domain + ")");
        }
        return "Entities: " + String.join(", ", entries);
    }

    private static Optional<String> relationsLine(ScopeSet scope, Set<String> qualified) {
        List<String> entries = new ArrayList<>();
        for (Relation relation : scope.relations()) {
            if (qualified.contains(relation.from()) && qualified.contains(relation.to())) {
                entries.add(relation.from() + " " + relation.label() + " " + relation.to());
            }
        }
        return entries.isEmpty() ? Optional.empty() : Optional.of("Relations: " + String.join(", ", entries));
    }

    /** {@code workorder: DRAFT, COMPLETED}, one entry per qualified entity with states in scope. */
    private static List<String> statesEntries(ScopeSet scope, ScopeGraph graph, Set<String> qualified) {
        Set<String> inScope = Set.copyOf(scope.lifecycleStates());
        List<String> entries = new ArrayList<>();
        for (String entity : qualified) {
            String prefix = entity + ".";
            List<String> states = graph.outgoing(NodeId.of(NodeType.ENTITY, entity), EdgeType.HAS_STATE).stream()
                    .map(edge -> edge.to().key())
                    .filter(inScope::contains)
                    .map(key -> key.startsWith(prefix) ? key.substring(prefix.length()) : key)
                    .toList();
            if (!states.isEmpty()) {
                entries.add(entity + ": " + String.join(", ", states));
            }
        }
        return entries;
    }

    /**
     * {@code WorkorderFacadeTool reads workorder [requires workorder:workorder:view]}, one entry per
     * scope tool that acts on a qualified entity, in slot order.
     */
    private static List<String> actionEntries(
            ScopeSet scope, ScopeGraph graph, Set<String> qualified, Set<String> callerPermissionCodes) {
        List<String> entries = new ArrayList<>();
        for (ScopeTool tool :
                scope.tools().stream().sorted(ScopeTool.SLOT_ORDER).toList()) {
            NodeId toolId = NodeId.of(NodeType.TOOL, tool.name());
            List<String> reads = new ArrayList<>();
            List<String> writes = new ArrayList<>();
            for (Edge edge : graph.outgoing(toolId, EdgeType.ACTS_ON)) {
                if (qualified.contains(edge.to().key())) {
                    (edge.access() == Access.READS ? reads : writes)
                            .add(edge.to().key());
                }
            }
            if (reads.isEmpty() && writes.isEmpty()) {
                continue;
            }
            StringBuilder entry = new StringBuilder(tool.name());
            if (!reads.isEmpty()) {
                entry.append(" reads ").append(String.join(", ", reads));
            }
            if (!writes.isEmpty()) {
                entry.append(reads.isEmpty() ? " writes " : "; writes ").append(String.join(", ", writes));
            }
            Set<String> requires = heldCodes(graph, toolId, callerPermissionCodes);
            if (!requires.isEmpty()) {
                entry.append(" [requires ").append(String.join(", ", requires)).append(']');
            }
            entries.add(entry.toString());
        }
        return entries;
    }

    /**
     * The codes of {@code tool} the caller holds: for a facade, the codes of every permission group
     * the caller satisfies in full; for a discovered operation, any code held. Mirrors {@link
     * ScopeCallerFilter}, so a code the caller lacks is never printed.
     */
    private static Set<String> heldCodes(ScopeGraph graph, NodeId toolId, Set<String> callerPermissionCodes) {
        NodeAttributes.Tool attributes =
                graph.attributes(toolId, NodeAttributes.Tool.class).orElse(null);
        Set<String> held = new TreeSet<>();
        if (attributes == null) {
            return held;
        }
        for (Set<String> group : attributes.permissionGroups().values()) {
            if (attributes.source() == ToolSource.FACADE) {
                if (!group.isEmpty() && callerPermissionCodes.containsAll(group)) {
                    held.addAll(group);
                }
            } else {
                group.stream().filter(callerPermissionCodes::contains).forEach(held::add);
            }
        }
        return held;
    }

    /** {@code Work Orders -> /workorders}, one entry per screen in scope. */
    private static List<String> screenEntries(ScopeSet scope, ScopeGraph graph) {
        List<String> entries = new ArrayList<>();
        for (String key : scope.screenKeys()) {
            graph.attributes(NodeId.of(NodeType.SCREEN, key), NodeAttributes.Screen.class)
                    .ifPresent(screen -> entries.add(screen.title() + " -> " + screen.urlTemplate()));
        }
        return entries;
    }

    /** The first entry carries the section label; the rest are indented continuation lines. */
    private static List<String> sectionLines(String label, List<String> entries) {
        List<String> lines = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            lines.add((index == 0 ? label : CONTINUATION) + entries.get(index));
        }
        return lines;
    }

    /**
     * Header plus as many lines, in order, as fit the budget; null when not even the entities line
     * fits, because a header alone orients nothing.
     */
    private static String fit(List<String> lines, int tokenBudget) {
        StringBuilder card = new StringBuilder(HEADER);
        int kept = 0;
        for (String line : lines) {
            String candidate = card + LINE_SEPARATOR + line;
            if (estimateTokens(candidate) > tokenBudget) {
                break;
            }
            card.append(LINE_SEPARATOR).append(line);
            kept++;
        }
        return kept == 0 ? null : card.toString();
    }

    private static void targets(ScopeGraph graph, NodeId from, EdgeType type, Set<String> into) {
        graph.outgoing(from, type).forEach(edge -> into.add(edge.to().key()));
    }
}

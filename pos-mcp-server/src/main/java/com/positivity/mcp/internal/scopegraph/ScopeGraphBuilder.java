package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.domain.RagScope;
import com.positivity.mcp.internal.scopegraph.EntityLexicon.EntityDefinition;
import com.positivity.mcp.internal.scopegraph.NodeAttributes.ToolSource;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ScreenRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolPermissionRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolPrerequisiteRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolWorkflowRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphFinding.Kind;
import com.positivity.mcp.internal.scopegraph.ScopeGraphGlossarySource.GlossaryTerm;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * ADR-0069 §3: builds the scope graph from its sources. The graph is generated, never hand-drawn
 * and never LLM-extracted.
 *
 * <p>The builder validates as it goes and <strong>never throws for a validation finding</strong>
 * (spec §2.5). A reference that does not resolve drops that one edge and is reported; a discovered
 * operation that matches no lexicon schema stays in the graph attached to its domain only. Whether
 * a finding fails anything is the caller's decision: the module test asserts there is no strict
 * finding, the runtime logs and carries on.
 *
 * <p>One §3 rule produces no finding kind: "every referenced permission resolves". A permission node
 * is <em>defined</em> by being referenced (§2: its sources are the tool, RAG and screen permission
 * metadata), so the reference cannot dangle inside this module; whether a code exists in the
 * platform permission model is checked against the security seed by {@code
 * RagRequiredPermissionSeedTest} and {@code FacadeToolPermissionSeedTest}.
 */
public final class ScopeGraphBuilder {

    /** {@code entities: [none]}: a platform-wide document, deliberately about no entity (§3). */
    public static final String NO_ENTITIES = "none";

    /** The language glossary phrases are written in. */
    private static final String GLOSSARY_LANGUAGE = "en";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Comparator<ScopeGraphFinding> FINDING_ORDER =
            Comparator.comparing(ScopeGraphFinding::kind).thenComparing(ScopeGraphFinding::subject);

    private final Clock clock;

    public ScopeGraphBuilder(@NonNull Clock clock) {
        this.clock = clock;
    }

    public @NonNull ScopeGraphBuildResult build(@NonNull ScopeGraphSources sources) {
        Build build = new Build(sources);
        build.tools();
        build.screens();
        build.ragDocNodes();
        build.domainScopes();
        build.entities();
        build.discoveredToolEdges();
        build.unscopedTools();
        build.screenFallback();
        build.prerequisites();
        build.ragDocEdges();
        build.glossary();
        build.findings.sort(FINDING_ORDER);
        return new ScopeGraphBuildResult(build.graph.build(clock.instant()), build.findings);
    }

    /** Lower-cased, trimmed, inner whitespace collapsed: the key form of a term phrase. */
    static @NonNull String normalizePhrase(@NonNull String phrase) {
        return WHITESPACE.matcher(phrase.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    /** The key of a {@code Term} node: {@code <lang>:<normalized phrase>}. */
    public static @NonNull String termKey(@NonNull String language, @NonNull String phrase) {
        return language + ":" + normalizePhrase(phrase);
    }

    /** The key of a {@code LifecycleState} node: {@code <entity>.<enum value>}. */
    public static @NonNull String lifecycleKey(@NonNull String entity, @NonNull String state) {
        return entity + "." + state;
    }

    /** The mutable state of one build; one instance per {@link #build} call. */
    private static final class Build {

        private final ScopeGraphSources sources;
        private final EntityLexicon lexicon;
        private final ScopeGraph.Builder graph = ScopeGraph.builder();
        private final List<ScopeGraphFinding> findings = new ArrayList<>();

        /** Enabled tools by name. */
        private final Map<String, ToolRow> tools = new TreeMap<>();

        private final Map<String, ScreenRow> screens = new TreeMap<>();
        private final Set<String> entityKeys = new TreeSet<>();
        private final Set<String> ragScopes = new TreeSet<>();
        /** Tool, screen and spec domains: the tool-catalog vocabulary. */
        private final Set<String> catalogDomains = new TreeSet<>();
        /** Qualified schema name → the entities it represents. */
        private final Map<String, Set<String>> entitiesBySchema = new TreeMap<>();

        private final Set<String> toolsWithEntity = new HashSet<>();
        private final Set<String> screensWithEntity = new HashSet<>();

        Build(ScopeGraphSources sources) {
            this.sources = sources;
            this.lexicon = sources.lexicon();
            lexicon.entities().forEach(entity -> entityKeys.add(entity.key()));
            catalogDomains.addAll(sources.schemaIndex().domains());
        }

        private void finding(Kind kind, String subject) {
            findings.add(new ScopeGraphFinding(kind, subject));
        }

        private NodeId domain(String domain) {
            return graph.node(NodeType.DOMAIN, domain);
        }

        /** Tool nodes with their {@code REQUIRES} and {@code VALID_IN} edges. Only enabled tools become nodes. */
        void tools() {
            Map<String, Map<String, Set<String>>> groupsByTool = new TreeMap<>();
            for (ToolPermissionRow row : sources.catalog().toolPermissions()) {
                groupsByTool
                        .computeIfAbsent(row.toolName(), ignored -> new TreeMap<>())
                        .computeIfAbsent(row.permissionGroup(), ignored -> new TreeSet<>())
                        .add(row.permissionCode());
            }
            for (ToolRow row : sources.catalog().tools()) {
                // A disabled tool's domain still names a real domain of the catalog.
                catalogDomains.add(row.domain());
                if (!row.enabled()) {
                    continue;
                }
                tools.put(row.name(), row);
                Map<String, Set<String>> groups = groupsByTool.getOrDefault(row.name(), Map.of());
                NodeId tool = graph.node(
                        NodeType.TOOL,
                        row.name(),
                        new NodeAttributes.Tool(
                                row.discovered() ? ToolSource.DISCOVERED : ToolSource.FACADE,
                                row.domain(),
                                row.httpMethod(),
                                groups));
                domain(row.domain());
                groups.values()
                        .forEach(codes -> codes.forEach(
                                code -> graph.edge(EdgeType.REQUIRES, tool, graph.node(NodeType.PERMISSION, code))));
            }
            for (ToolWorkflowRow row : sources.catalog().toolWorkflowStates()) {
                if (tools.containsKey(row.toolName())) {
                    graph.edge(
                            EdgeType.VALID_IN,
                            NodeId.of(NodeType.TOOL, row.toolName()),
                            graph.node(NodeType.WORKFLOW_STATE, row.workflowState()));
                }
            }
        }

        void screens() {
            for (ScreenRow row : sources.catalog().screens()) {
                screens.put(row.screenKey(), row);
                catalogDomains.add(row.domain());
                NodeId screen = graph.node(
                        NodeType.SCREEN,
                        row.screenKey(),
                        new NodeAttributes.Screen(row.title(), row.urlTemplate(), row.domain(), row.requiredPerm()));
                domain(row.domain());
                if (row.requiredPerm() != null && !row.requiredPerm().isBlank()) {
                    graph.edge(EdgeType.REQUIRES, screen, graph.node(NodeType.PERMISSION, row.requiredPerm()));
                }
            }
        }

        /** RagDoc nodes and their permissions; the {@code ABOUT} edges wait until the entities exist. */
        void ragDocNodes() {
            for (StaticDocEntry doc : sources.ragDocs()) {
                String scope = RagScope.normalize(doc.ragScope());
                ragScopes.add(scope);
                NodeId node = graph.node(
                        NodeType.RAG_DOC,
                        doc.id(),
                        new NodeAttributes.RagDoc(scope, doc.requiredPermissions(), platformWide(doc)));
                domain(scope);
                doc.requiredPermissions()
                        .forEach(code -> graph.edge(EdgeType.REQUIRES, node, graph.node(NodeType.PERMISSION, code)));
            }
        }

        private static boolean platformWide(StaticDocEntry doc) {
            return doc.entities().size() == 1
                    && NO_ENTITIES.equals(doc.entities().getFirst());
        }

        /** §2, Domain row: the explicit map between the tool-catalog and the RAG spelling of a domain. */
        void domainScopes() {
            lexicon.domainScopes().forEach((domain, scope) -> {
                if (!catalogDomains.contains(domain)) {
                    finding(Kind.UNKNOWN_DOMAIN, "domain_scopes -> " + domain);
                }
                if (!ragScopes.contains(scope)) {
                    finding(Kind.UNKNOWN_RAG_SCOPE, domain + " -> " + scope);
                    return;
                }
                graph.domainScope(domain, scope);
            });
        }

        void entities() {
            // Every entity node first, so a relation may point forward in the file.
            lexicon.entities().forEach(entity -> graph.node(NodeType.ENTITY, entity.key()));
            Set<String> allSchemas = sources.schemaIndex().schemaNames();
            for (EntityDefinition entity : lexicon.entities()) {
                NodeId node = NodeId.of(NodeType.ENTITY, entity.key());
                ownedBy(entity, node);
                terms(entity, node);
                entity.identifiers()
                        .forEach(identifier -> graph.edge(
                                EdgeType.IDENTIFIES,
                                graph.node(
                                        NodeType.IDENTIFIER_PATTERN,
                                        identifier.key(),
                                        new NodeAttributes.IdentifierPattern(identifier.pattern())),
                                node));
                entity.relatesTo().forEach(relation -> {
                    if (entityKeys.contains(relation.entity())) {
                        graph.edge(
                                EdgeType.RELATES_TO,
                                node,
                                NodeId.of(NodeType.ENTITY, relation.entity()),
                                relation.label());
                    } else {
                        finding(Kind.UNKNOWN_ENTITY, entity.key() + " -> " + relation.entity());
                    }
                });
                schemas(entity, node, allSchemas);
                facadeTools(entity, node);
                entity.screens().forEach(screenKey -> {
                    if (screens.containsKey(screenKey)) {
                        graph.edge(EdgeType.SHOWS, NodeId.of(NodeType.SCREEN, screenKey), node);
                        screensWithEntity.add(screenKey);
                    } else {
                        finding(Kind.UNKNOWN_SCREEN, entity.key() + " -> " + screenKey);
                    }
                });
            }
        }

        private void ownedBy(EntityDefinition entity, NodeId node) {
            if (catalogDomains.contains(entity.domain()) || ragScopes.contains(entity.domain())) {
                graph.edge(EdgeType.OWNED_BY, node, domain(entity.domain()));
            } else {
                finding(Kind.UNKNOWN_DOMAIN, entity.key() + " -> " + entity.domain());
            }
        }

        private void terms(EntityDefinition entity, NodeId node) {
            for (String language : EntityLexicon.REQUIRED_LANGUAGES) {
                if (entity.terms().getOrDefault(language, List.of()).isEmpty()) {
                    finding(Kind.MISSING_LANGUAGE_TERMS, entity.key() + ":" + language);
                }
            }
            entity.terms()
                    .forEach((language, phrases) -> phrases.forEach(phrase -> graph.edge(
                            EdgeType.DENOTES,
                            graph.node(
                                    NodeType.TERM,
                                    termKey(language, phrase),
                                    new NodeAttributes.Term(language, phrase, false)),
                            node)));
        }

        /**
         * Resolves the entity's schema references and, from the explicitly listed schemas only, its
         * lifecycle states. A pattern is for attaching tools; it also matches item and request DTOs
         * whose {@code status} is not the entity's own, so it contributes no {@code HAS_STATE}.
         */
        private void schemas(EntityDefinition entity, NodeId node, Set<String> allSchemas) {
            for (String schema : entity.schemas()) {
                if (!sources.schemaIndex().hasSchema(schema)) {
                    finding(Kind.UNKNOWN_SCHEMA, entity.key() + " -> " + schema);
                    continue;
                }
                entitiesBySchema
                        .computeIfAbsent(schema, ignored -> new TreeSet<>())
                        .add(entity.key());
                sources.schemaIndex()
                        .enums(schema)
                        .values()
                        .forEach(values -> values.forEach(value -> graph.edge(
                                EdgeType.HAS_STATE,
                                node,
                                graph.node(NodeType.LIFECYCLE_STATE, lifecycleKey(entity.key(), value)))));
            }
            for (String rawPattern : entity.schemaPatterns()) {
                // Compiles: the loader rejected a lexicon whose pattern does not.
                Pattern pattern = Pattern.compile(rawPattern);
                boolean matched = false;
                for (String schema : allSchemas) {
                    if (pattern.matcher(schema).matches()) {
                        matched = true;
                        entitiesBySchema
                                .computeIfAbsent(schema, ignored -> new TreeSet<>())
                                .add(entity.key());
                    }
                }
                if (!matched) {
                    finding(Kind.SCHEMA_PATTERN_WITHOUT_MATCH, entity.key() + " -> " + rawPattern);
                }
            }
        }

        private void facadeTools(EntityDefinition entity, NodeId node) {
            entity.facadeTools().forEach(reference -> {
                ToolRow tool = tools.get(reference.tool());
                if (tool == null || tool.discovered()) {
                    finding(Kind.UNKNOWN_FACADE_TOOL, entity.key() + " -> " + reference.tool());
                    return;
                }
                graph.edge(
                        EdgeType.ACTS_ON,
                        NodeId.of(NodeType.TOOL, tool.name()),
                        node,
                        reference.access().label());
                toolsWithEntity.add(tool.name());
            });
        }

        /**
         * §2, {@code ACTS_ON}: a discovered operation acts on every entity one of its request or
         * response schemas represents; the HTTP method gives reads or writes. One that matches
         * nothing keeps its domain only and is counted (§3).
         */
        void discoveredToolEdges() {
            for (ToolRow tool : tools.values()) {
                if (!tool.discovered()) {
                    continue;
                }
                Set<String> entities = new TreeSet<>();
                sources.schemaIndex()
                        .operationSchemas(tool.name())
                        .forEach(schema -> entities.addAll(entitiesBySchema.getOrDefault(schema, Set.of())));
                // A row without a method cannot be shown to read only, so it is treated as writing.
                Access access = tool.httpMethod() == null ? Access.WRITES : Access.ofHttpMethod(tool.httpMethod());
                NodeId node = NodeId.of(NodeType.TOOL, tool.name());
                entities.forEach(entity ->
                        graph.edge(EdgeType.ACTS_ON, node, NodeId.of(NodeType.ENTITY, entity), access.label()));
                if (!entities.isEmpty()) {
                    toolsWithEntity.add(tool.name());
                }
            }
        }

        /** §3: every enabled tool acts on an entity, or is listed in {@code unscoped_tools}. */
        void unscopedTools() {
            Set<String> unscoped = new HashSet<>(lexicon.unscopedTools());
            for (String name : lexicon.unscopedTools()) {
                if (!tools.containsKey(name)) {
                    finding(Kind.UNKNOWN_UNSCOPED_TOOL, name);
                }
            }
            for (ToolRow tool : tools.values()) {
                if (toolsWithEntity.contains(tool.name()) || unscoped.contains(tool.name())) {
                    continue;
                }
                finding(
                        tool.discovered() ? Kind.DISCOVERED_TOOL_UNMAPPED : Kind.FACADE_TOOL_WITHOUT_ENTITY,
                        tool.name());
            }
        }

        /** §2, {@code SHOWS}: the lexicon screen list, else the entities of the screen's domain. */
        void screenFallback() {
            for (ScreenRow screen : screens.values()) {
                if (screensWithEntity.contains(screen.screenKey())) {
                    continue;
                }
                lexicon.entities().stream()
                        .filter(entity -> entity.domain().equals(screen.domain()))
                        .forEach(entity -> graph.edge(
                                EdgeType.SHOWS,
                                NodeId.of(NodeType.SCREEN, screen.screenKey()),
                                NodeId.of(NodeType.ENTITY, entity.key())));
            }
        }

        void prerequisites() {
            for (ToolPrerequisiteRow row : sources.catalog().toolPrerequisites()) {
                if (tools.containsKey(row.toolName()) && tools.containsKey(row.producingTool())) {
                    graph.edge(
                            EdgeType.PRODUCES_INPUT_FOR,
                            NodeId.of(NodeType.TOOL, row.producingTool()),
                            NodeId.of(NodeType.TOOL, row.toolName()),
                            row.requiredParam());
                } else {
                    finding(Kind.PREREQUISITE_TOOL_MISSING, row.producingTool() + " -> " + row.toolName());
                }
            }
        }

        /** §3: every RAG document declares at least one entity, or {@code entities: [none]}. */
        void ragDocEdges() {
            for (StaticDocEntry doc : sources.ragDocs()) {
                if (doc.entities().isEmpty()) {
                    finding(Kind.RAG_DOC_WITHOUT_ENTITIES, doc.id());
                    continue;
                }
                if (platformWide(doc)) {
                    continue;
                }
                NodeId node = NodeId.of(NodeType.RAG_DOC, doc.id());
                for (String entity : doc.entities()) {
                    if (entityKeys.contains(entity)) {
                        graph.edge(EdgeType.ABOUT, node, NodeId.of(NodeType.ENTITY, entity));
                    } else {
                        // Also `none` beside real entities: it is not an entity, and the mix is a mistake.
                        finding(Kind.UNKNOWN_ENTITY, doc.id() + " -> " + entity);
                    }
                }
            }
        }

        /**
         * §2, Term row: glossary phrases become terms. The glossary links to the lexicon instead of
         * repeating it (§3.1), so a phrase denotes the entities whose own English term occurs in it
         * on word boundaries ("best customers" → customer); a phrase naming no entity ("who owes us
         * the most money") is a term that denotes nothing.
         */
        void glossary() {
            Map<String, List<Pattern>> entityTerms = new LinkedHashMap<>();
            for (EntityDefinition entity : lexicon.entities()) {
                List<Pattern> patterns = new ArrayList<>();
                for (String phrase : entity.terms().getOrDefault(GLOSSARY_LANGUAGE, List.of())) {
                    patterns.add(Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(normalizePhrase(phrase))
                            + "(?:s|es)?(?![\\p{L}\\p{N}])"));
                }
                entityTerms.put(entity.key(), patterns);
            }
            for (GlossaryTerm term : sources.glossaryTerms()) {
                List<String> phrases = new ArrayList<>();
                phrases.add(term.term());
                phrases.addAll(term.aliases());
                for (String phrase : phrases) {
                    String normalized = normalizePhrase(phrase);
                    if (normalized.isEmpty()) {
                        continue;
                    }
                    NodeId id = NodeId.of(NodeType.TERM, termKey(GLOSSARY_LANGUAGE, phrase));
                    // A phrase the lexicon already lists keeps its lexicon node and edges.
                    if (!graph.contains(id)) {
                        graph.node(NodeType.TERM, id.key(), new NodeAttributes.Term(GLOSSARY_LANGUAGE, phrase, true));
                    }
                    entityTerms.forEach((entity, patterns) -> {
                        if (patterns.stream()
                                .anyMatch(pattern -> pattern.matcher(normalized).find())) {
                            graph.edge(EdgeType.DENOTES, id, NodeId.of(NodeType.ENTITY, entity));
                        }
                    });
                }
            }
        }
    }
}

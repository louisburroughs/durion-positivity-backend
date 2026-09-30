package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.repository.ToolMetadataRepositoryImpl;
import com.positivity.mcp.internal.scopegraph.NodeAttributes.ToolSource;
import com.positivity.mcp.tenancy.PostgresTenancyTestBase;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spec §2.9: the in-memory tool predicates of {@link ScopeCallerFilter} agree with the SQL they
 * mirror, for a matrix of callers and workflow states.
 *
 * <p>Runs against the real migration chain on Postgres, so the catalog holds the seeded facades with
 * their real {@code permission_group}s, plus the rows added here for the shapes the seed does not
 * have: a tool with no permission rows, a disabled tool, a tool valid outside {@code IDLE} only, and
 * discovered operations (the seed has none; discovery writes them at runtime). The graph is built
 * from the same rows through the production reader and builder, and each predicate is compared with
 * the repository method whose SQL it mirrors.
 *
 * <p>Requires Docker.
 */
@DisplayName("Scope caller filter agrees with the tool-gating SQL (ADR-0069 §5.3)")
class ScopeCallerFilterSqlParityIT extends PostgresTenancyTestBase {

    private static final String PREFIX = "parity_";
    private static final int EMBEDDING_DIMENSIONS = 1024;
    private static final int NO_LIMIT = 100_000;
    private static final String A = "parity:thing:view";
    private static final String B = "parity:thing:edit";
    private static final String C = "parity:other:view";
    private static final String AUTHENTICATED = "AUTHENTICATED";

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private ToolMetadataRepositoryImpl repository;

    @BeforeEach
    void seedShapesTheMigrationsDoNotHave() {
        jdbc = new JdbcTemplate(dataSource);
        repository = new ToolMetadataRepositoryImpl(jdbc);
        deleteFixtureRows();

        // Facades.
        UUID andGroups = tool("and_groups", "facade", null, true, false);
        permission(andGroups, "both", A);
        permission(andGroups, "both", B);
        permission(andGroups, "single", C);
        validIn(andGroups, "IDLE");

        UUID noRows = tool("no_rows", "facade", null, true, false);
        validIn(noRows, "IDLE");

        UUID creatingPoOnly = tool("creating_po_only", "facade", null, true, false);
        permission(creatingPoOnly, "only", A);
        validIn(creatingPoOnly, "CREATING_PO");

        UUID authenticated = tool("authenticated", "facade", null, true, false);
        permission(authenticated, AUTHENTICATED, AUTHENTICATED);
        validIn(authenticated, "IDLE");
        validIn(authenticated, "CREATING_PO");

        UUID disabled = tool("disabled", "facade", null, false, false);
        permission(disabled, "only", A);
        validIn(disabled, "IDLE");

        UUID noWorkflow = tool("no_workflow", "facade", null, true, false);
        permission(noWorkflow, "only", A);

        // Discovered operations: every grant is its own group (permission_group = permission_code).
        UUID anyCode = tool("d_any_code", "openapi", "GET", true, true);
        permission(anyCode, A, A);
        permission(anyCode, B, B);
        validIn(anyCode, "IDLE");

        UUID discoveredNoRows = tool("d_no_rows", "openapi", "GET", true, true);
        validIn(discoveredNoRows, "IDLE");

        UUID discoveredPoOnly = tool("d_creating_po_only", "openapi", "POST", true, true);
        permission(discoveredPoOnly, A, A);
        validIn(discoveredPoOnly, "CREATING_PO");

        UUID discoveredAuthenticated = tool("d_authenticated", "openapi", "GET", true, true);
        permission(discoveredAuthenticated, AUTHENTICATED, AUTHENTICATED);
        validIn(discoveredAuthenticated, "IDLE");

        UUID discoveredDisabled = tool("d_disabled", "openapi", "GET", false, true);
        permission(discoveredDisabled, A, A);
        validIn(discoveredDisabled, "IDLE");
    }

    @AfterEach
    void deleteFixtureRows() {
        jdbc.update(
                "DELETE FROM mcp_tool_permission WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name LIKE ?)",
                PREFIX + "%");
        jdbc.update(
                "DELETE FROM mcp_tool_workflow WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name LIKE ?)",
                PREFIX + "%");
        jdbc.update("DELETE FROM mcp_tool WHERE name LIKE ?", PREFIX + "%");
    }

    @Test
    @DisplayName("facade predicate = findEnabledByPermissionsAndWorkflow, for every caller and workflow state")
    void facadePredicateAgreesWithTheSql() {
        ScopeGraph graph = graphOfTheCatalog();
        List<NodeId> facades = toolsOf(graph, ToolSource.FACADE);
        // The seeded facades are in the comparison, not only the rows added above.
        assertThat(facades).hasSizeGreaterThan(10);
        int compared = 0;
        int nonEmpty = 0;

        for (String state : workflowStates()) {
            for (Set<String> caller : callers()) {
                Set<String> sql = repository.findEnabledByPermissionsAndWorkflow(caller, state).stream()
                        .map(ToolMetadata::name)
                        .collect(Collectors.toCollection(TreeSet::new));
                Set<String> inMemory = facades.stream()
                        .filter(tool -> ScopeCallerFilter.toolPermitted(graph, tool, caller, state))
                        .map(NodeId::key)
                        .collect(Collectors.toCollection(TreeSet::new));

                assertThat(inMemory)
                        .as("facade tools for caller %s in %s", caller, state)
                        .isEqualTo(sql);
                compared++;
                nonEmpty += sql.isEmpty() ? 0 : 1;
            }
        }
        // The matrix is not vacuous: many cells have tools, and the ones that matter are told apart.
        assertThat(compared).isGreaterThan(100);
        assertThat(nonEmpty).isGreaterThan(20);
        assertThat(facadeNames(graph, Set.of(A, B), "IDLE")).contains(PREFIX + "and_groups");
        assertThat(facadeNames(graph, Set.of(A), "IDLE")).doesNotContain(PREFIX + "and_groups");
        assertThat(facadeNames(graph, Set.of(C), "IDLE")).contains(PREFIX + "and_groups");
        assertThat(facadeNames(graph, Set.of(A, B, C, AUTHENTICATED), "IDLE"))
                .contains(PREFIX + "authenticated")
                .doesNotContain(
                        PREFIX + "no_rows", PREFIX + "creating_po_only", PREFIX + "disabled", PREFIX + "no_workflow");
        assertThat(facadeNames(graph, Set.of(A), "CREATING_PO")).containsExactly(PREFIX + "creating_po_only");
    }

    @Test
    @DisplayName("discovered predicate = findDiscoveredCandidatesForPermissions in IDLE, whatever the turn's state")
    void discoveredPredicateAgreesWithTheSql() {
        ScopeGraph graph = graphOfTheCatalog();
        List<NodeId> discovered = toolsOf(graph, ToolSource.DISCOVERED);
        assertThat(discovered).isNotEmpty();
        float[] embedding = new float[EMBEDDING_DIMENSIONS];
        Arrays.fill(embedding, 0.1f);

        for (Set<String> caller : callers()) {
            Set<String> sql = repository
                    .findDiscoveredCandidatesForPermissions(
                            embedding, NO_LIMIT, caller, ScopeCallerFilter.DISCOVERED_WORKFLOW_STATE)
                    .stream()
                    .map(DiscoveredOperation::name)
                    .collect(Collectors.toCollection(TreeSet::new));
            for (String turnState : workflowStates()) {
                Set<String> inMemory = discovered.stream()
                        .filter(tool -> ScopeCallerFilter.toolPermitted(graph, tool, caller, turnState))
                        .map(NodeId::key)
                        .collect(Collectors.toCollection(TreeSet::new));

                assertThat(inMemory)
                        .as("discovered tools for caller %s in a %s turn", caller, turnState)
                        .isEqualTo(sql);
            }
        }
        Set<String> eitherCode = discovered.stream()
                .filter(tool -> ScopeCallerFilter.toolPermitted(graph, tool, Set.of(B), "IDLE"))
                .map(NodeId::key)
                .collect(Collectors.toSet());
        assertThat(eitherCode).containsExactly(PREFIX + "d_any_code");
    }

    /** The whole catalog, read and built the way the runtime does it; the lexicon plays no part in gating. */
    private ScopeGraph graphOfTheCatalog() {
        ScopeGraphCatalog catalog = new JdbcScopeGraphCatalogReader(jdbc).read();
        return new ScopeGraphBuilder(Clock.systemUTC())
                .build(new ScopeGraphSources(
                        new EntityLexicon(Map.of(), List.of(), List.of()),
                        catalog,
                        List.of(),
                        OpenApiSchemaIndex.empty(),
                        List.of()))
                .graph();
    }

    private static List<NodeId> toolsOf(ScopeGraph graph, ToolSource source) {
        return graph.nodesOfType(NodeType.TOOL).stream()
                .filter(node -> node.attributes() instanceof NodeAttributes.Tool tool && tool.source() == source)
                .map(ScopeNode::id)
                .toList();
    }

    private static Set<String> facadeNames(ScopeGraph graph, Set<String> caller, String state) {
        return toolsOf(graph, ToolSource.FACADE).stream()
                .filter(tool -> ScopeCallerFilter.toolPermitted(graph, tool, caller, state))
                .map(NodeId::key)
                .filter(name -> name.startsWith(PREFIX))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Every seeded state, plus one no tool is linked to. */
    private List<String> workflowStates() {
        List<String> states = new ArrayList<>(jdbc.queryForList("SELECT name FROM mcp_workflow_state", String.class));
        states.add("PROCESSING_RETURN");
        return states;
    }

    /**
     * The caller matrix: nobody, the sentinel alone, each fixture combination, every code in the
     * catalog on its own and with the sentinel (which walks each seeded AND-group's partial and
     * whole cases), every pair of codes that share a seeded tool, and a caller holding everything.
     */
    private List<Set<String>> callers() {
        Set<Set<String>> callers = new LinkedHashSet<>();
        callers.add(Set.of());
        callers.add(Set.of(AUTHENTICATED));
        callers.add(Set.of("no:such:code"));
        for (Set<String> fixture : List.of(
                Set.of(A),
                Set.of(B),
                Set.of(C),
                Set.of(A, B),
                Set.of(A, C),
                Set.of(A, AUTHENTICATED),
                Set.of(A, B, C))) {
            callers.add(fixture);
        }
        List<String> codes = jdbc.queryForList(
                "SELECT DISTINCT permission_code FROM mcp_tool_permission ORDER BY permission_code", String.class);
        for (String code : codes) {
            callers.add(Set.of(code));
            callers.add(code.equals(AUTHENTICATED) ? Set.of(code) : Set.of(code, AUTHENTICATED));
        }
        // Whole groups, as seeded: the callers a multi-code AND-group is satisfied by.
        jdbc.query("""
                        SELECT array_agg(permission_code ORDER BY permission_code) AS codes
                        FROM mcp_tool_permission
                        GROUP BY tool_id, permission_group
                        """, (rs, rowNum) -> Set.of((String[]) rs.getArray("codes").getArray()))
                .forEach(callers::add);
        callers.add(Set.copyOf(codes));
        return List.copyOf(callers);
    }

    private UUID tool(String name, String source, String httpMethod, boolean enabled, boolean embedded) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mcp_tool (id, name, display_name, description, domain, source, http_method, enabled)
                VALUES (?, ?, ?, 'scope caller filter parity fixture', 'parity', ?, ?, ?)
                """, id, PREFIX + name, PREFIX + name, source, httpMethod, enabled);
        if (embedded) {
            jdbc.update(
                    "UPDATE mcp_tool SET embedding = array_fill(0.1::real, ARRAY[?])::vector WHERE id = ?",
                    EMBEDDING_DIMENSIONS,
                    id);
        }
        return id;
    }

    private void permission(UUID toolId, String group, String code) {
        jdbc.update(
                "INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code) VALUES (?, ?, ?)",
                toolId,
                group,
                code);
    }

    private void validIn(UUID toolId, String workflowState) {
        int linked = jdbc.update("""
                INSERT INTO mcp_tool_workflow (tool_id, workflow_state_id)
                SELECT ?, id FROM mcp_workflow_state WHERE name = ?
                """, toolId, workflowState);
        assertThat(linked).as("workflow state %s is seeded", workflowState).isEqualTo(1);
    }
}

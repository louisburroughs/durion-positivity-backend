package com.positivity.mcp.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.domain.DiscoveredOperation;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * ADR-0069 §6: functional guard on {@link ToolMetadataRepositoryImpl#findDiscoveredByNamesForPermissions},
 * the SQL gate behind the discovered slots. Like {@link ToolPermissionGroupGatingTest} it runs against
 * a real H2 (PostgreSQL mode) database, so the gate's <em>semantics</em> are checked: the same
 * enabled / {@code source = 'openapi'} / workflow / any-one-permission-code predicates as the ANN
 * query {@code findDiscoveredCandidatesForPermissions}, applied to a fixed set of names.
 */
class ScopeDiscoveredToolGatingTest {

    private static final String IDLE = "IDLE";
    private static final String VIEW = "workorder:workorder:view";
    private static final String CREATE = "workorder:workorder:create";

    private JdbcTemplate jdbcTemplate;
    private ToolMetadataRepositoryImpl repository;
    private UUID idleStateId;
    private UUID creatingPoStateId;

    @BeforeEach
    void setUp() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(
                new org.h2.Driver(),
                "jdbc:h2:mem:scopeslots-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new ToolMetadataRepositoryImpl(jdbcTemplate);

        jdbcTemplate.execute("""
                CREATE TABLE mcp_tool (
                  id UUID PRIMARY KEY,
                  name VARCHAR(100) NOT NULL UNIQUE,
                  display_name VARCHAR(150) NOT NULL,
                  description TEXT NOT NULL,
                  domain VARCHAR(80) NOT NULL,
                  priority DOUBLE PRECISION NOT NULL DEFAULT 1.0,
                  cost_level VARCHAR(20) NOT NULL DEFAULT 'low',
                  avg_latency_ms INTEGER NOT NULL DEFAULT 200,
                  enabled BOOLEAN NOT NULL DEFAULT TRUE,
                  handler_bean VARCHAR(150) NOT NULL,
                  source VARCHAR(20) NOT NULL DEFAULT 'facade',
                  http_method VARCHAR(10),
                  http_path VARCHAR(300),
                  service_id VARCHAR(100),
                  input_schema TEXT
                )
                """);
        jdbcTemplate.execute("CREATE TABLE mcp_workflow_state (id UUID PRIMARY KEY, name VARCHAR(80) NOT NULL UNIQUE)");
        jdbcTemplate.execute("""
                CREATE TABLE mcp_tool_workflow (
                  tool_id UUID NOT NULL,
                  workflow_state_id UUID NOT NULL,
                  PRIMARY KEY (tool_id, workflow_state_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE mcp_tool_permission (
                  tool_id UUID NOT NULL,
                  permission_group TEXT NOT NULL,
                  permission_code VARCHAR(150) NOT NULL,
                  PRIMARY KEY (tool_id, permission_group, permission_code)
                )
                """);
        idleStateId = UUID.randomUUID();
        creatingPoStateId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO mcp_workflow_state (id, name) VALUES (?, ?)", idleStateId, IDLE);
        jdbcTemplate.update(
                "INSERT INTO mcp_workflow_state (id, name) VALUES (?, ?)", creatingPoStateId, "CREATING_PO");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
    }

    @Test
    @DisplayName("returns only the named operations the caller holds a code for, with their execution coordinates")
    void returnsNamedOperationsTheCallerMayUse() {
        grant(insertOp("workorder_getworkorder", "GET", true, idleStateId), VIEW);
        grant(insertOp("workorder_createworkorder", "POST", true, idleStateId), CREATE);
        grant(insertOp("workorder_listworkorders", "GET", true, idleStateId), VIEW);

        List<DiscoveredOperation> found = repository.findDiscoveredByNamesForPermissions(
                List.of("workorder_createworkorder", "workorder_getworkorder"), Set.of(VIEW), IDLE);

        assertThat(found).extracting(DiscoveredOperation::name).containsExactly("workorder_getworkorder");
        assertThat(found.getFirst().httpMethod()).isEqualTo("GET");
        assertThat(found.getFirst().httpPath()).isEqualTo("/v1/workorder_getworkorder");
        assertThat(found.getFirst().serviceId()).isEqualTo("pos-workorder");
        assertThat(found.getFirst().isExecutable()).isTrue();
    }

    @Test
    @DisplayName("permission codes are OR: any one of an operation's codes qualifies")
    void anyOneCodeQualifies() {
        UUID list = insertOp("workorder_listworkorders", "GET", true, idleStateId);
        grant(list, "workorder:workorder:list");
        grant(list, VIEW);

        assertThat(names(Set.of(VIEW), "workorder_listworkorders")).containsExactly("workorder_listworkorders");
        assertThat(names(Set.of("workorder:workorder:list"), "workorder_listworkorders"))
                .containsExactly("workorder_listworkorders");
        assertThat(names(Set.of("something:else"), "workorder_listworkorders")).isEmpty();
    }

    @Test
    @DisplayName("the workflow state scopes the gate")
    void workflowStateScopesTheGate() {
        grant(insertOp("workorder_notidle", "GET", true, creatingPoStateId), VIEW);

        assertThat(names(Set.of(VIEW), "workorder_notidle")).isEmpty();
        assertThat(repository.findDiscoveredByNamesForPermissions(
                        List.of("workorder_notidle"), Set.of(VIEW), "CREATING_PO"))
                .extracting(DiscoveredOperation::name)
                .containsExactly("workorder_notidle");
    }

    @Test
    @DisplayName("disabled operations, facade tools and zero-permission operations are never returned")
    void failClosedInvariants() {
        grant(insertOp("workorder_disabled", "GET", false, idleStateId), VIEW);
        grant(insertTool("WorkorderFacadeTool", "facade", "GET", true, idleStateId), VIEW);
        insertOp("workorder_ungated", "GET", true, idleStateId);

        assertThat(names(Set.of(VIEW), "workorder_disabled", "WorkorderFacadeTool", "workorder_ungated"))
                .isEmpty();
    }

    @Test
    @DisplayName("empty names or empty codes short-circuit without a query")
    void emptyInputsShortCircuit() {
        grant(insertOp("workorder_getworkorder", "GET", true, idleStateId), VIEW);

        assertThat(repository.findDiscoveredByNamesForPermissions(List.of(), Set.of(VIEW), IDLE))
                .isEmpty();
        assertThat(repository.findDiscoveredByNamesForPermissions(List.of("workorder_getworkorder"), Set.of(), IDLE))
                .isEmpty();
    }

    @Test
    @DisplayName("an operation without an embedding is still admitted: the graph chose it, not similarity")
    void noEmbeddingRequired() {
        // The table here has no embedding column at all, which is the point: the query must not read one.
        grant(insertOp("workorder_getworkorder", "GET", true, idleStateId), VIEW);

        assertThat(names(Set.of(VIEW), "workorder_getworkorder")).containsExactly("workorder_getworkorder");
    }

    private List<String> names(Set<String> codes, String... asked) {
        return repository.findDiscoveredByNamesForPermissions(List.of(asked), codes, IDLE).stream()
                .map(DiscoveredOperation::name)
                .toList();
    }

    private UUID insertOp(String name, String method, boolean enabled, UUID state) {
        return insertTool(name, "openapi", method, enabled, state);
    }

    private UUID insertTool(String name, String source, String method, boolean enabled, UUID state) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mcp_tool (id, name, display_name, description, domain, priority, cost_level,
                                      avg_latency_ms, enabled, handler_bean, source, http_method, http_path,
                                      service_id, input_schema)
                VALUES (?, ?, ?, ?, 'workorder', 1.0, 'low', 200, ?, ?, ?, ?, ?, 'pos-workorder', NULL)
                """, id, name, name, name + " description", enabled, name, source, method, "/v1/" + name);
        jdbcTemplate.update("INSERT INTO mcp_tool_workflow (tool_id, workflow_state_id) VALUES (?, ?)", id, state);
        return id;
    }

    private void grant(UUID toolId, String code) {
        jdbcTemplate.update(
                "INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code) VALUES (?, ?, ?)",
                toolId,
                code,
                code);
    }
}

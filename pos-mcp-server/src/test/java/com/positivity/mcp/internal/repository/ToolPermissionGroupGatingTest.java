package com.positivity.mcp.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.domain.ToolMetadata;
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
 * V40 / #1606 finding 1: functional guard on the AND-group permission gate.
 *
 * <p>{@link ToolMetadataRepositoryImplTest} mocks {@code JdbcTemplate}, so it can only assert the
 * statement's shape. This test runs {@link ToolMetadataRepositoryImpl#findEnabledByPermissionsAndWorkflow}
 * against a real (H2, PostgreSQL-compatibility) database holding the V40 table shape, so the gate's
 * <em>semantics</em> are checked rather than its text: a tool is offered iff the caller holds ALL
 * codes of AT LEAST ONE {@code permission_group}.
 *
 * <p>The ANN sibling {@code findTopKByEmbeddingForPermissions} carries the identical predicate but
 * cannot run here (it needs pgvector's {@code <=>}); {@code ToolMetadataRepositoryImplTest} locks
 * the two predicates to the same shape instead.
 */
class ToolPermissionGroupGatingTest {

    private static final String IDLE = "IDLE";

    private JdbcTemplate jdbcTemplate;
    private ToolMetadataRepositoryImpl repository;
    private UUID idleStateId;

    @BeforeEach
    void setUp() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(
                new org.h2.Driver(),
                "jdbc:h2:mem:permgroups-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
                  source VARCHAR(20) NOT NULL DEFAULT 'facade'
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
        // V17 shape + the V40 permission_group column and widened primary key.
        jdbcTemplate.execute("""
                CREATE TABLE mcp_tool_permission (
                  tool_id UUID NOT NULL,
                  permission_group TEXT NOT NULL,
                  permission_code VARCHAR(150) NOT NULL,
                  PRIMARY KEY (tool_id, permission_group, permission_code)
                )
                """);

        UUID idleId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO mcp_workflow_state (id, name) VALUES (?, ?)", idleId, IDLE);
        jdbcTemplate.update("INSERT INTO mcp_workflow_state (id, name) VALUES (?, ?)", UUID.randomUUID(), "PROCESSING");
        this.idleStateId = idleId;
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
    }

    // ── the #1606 finding-1 regression ────────────────────────────────────────

    @Test
    @DisplayName("a caller holding only workorder:workorder:view is not offered CustomerFacadeTool")
    void workorderOnlyCallerIsNotOfferedCustomerFacade() {
        seedCustomerFacadeTool();

        assertThat(namesFor(Set.of("workorder:workorder:view"))).isEmpty();
    }

    @Test
    @DisplayName("a caller holding crm:party:view is offered CustomerFacadeTool")
    void crmPartyViewCallerIsOfferedCustomerFacade() {
        seedCustomerFacadeTool();

        assertThat(namesFor(Set.of("crm:party:view"))).containsExactly("CustomerFacadeTool");
    }

    // ── AND-within-group / OR-across-groups ───────────────────────────────────

    @Test
    @DisplayName("holding all codes of one group offers the tool")
    void allCodesOfOneGroupQualifies() {
        seedTaxFacadeTool();

        assertThat(namesFor(Set.of("location:read", "tax:calculate"))).containsExactly("TaxFacadeTool");
        assertThat(namesFor(Set.of("location:read", "tax:rates:view"))).containsExactly("TaxFacadeTool");
        assertThat(namesFor(Set.of("reporting:view:financial-statements"))).containsExactly("TaxFacadeTool");
    }

    @Test
    @DisplayName("holding a strict subset of every group does not offer the tool")
    void strictSubsetOfEveryGroupDoesNotQualify() {
        seedTaxFacadeTool();

        // location:read alone completes neither calculateTax nor getTaxRate.
        assertThat(namesFor(Set.of("location:read"))).isEmpty();
        assertThat(namesFor(Set.of("tax:calculate"))).isEmpty();
        assertThat(namesFor(Set.of("tax:rates:view"))).isEmpty();
    }

    @Test
    @DisplayName("codes spread across two groups that complete neither do not offer the tool")
    void codesSpreadAcrossGroupsCompletingNeitherDoesNotQualify() {
        seedTaxFacadeTool();

        // One code from calculateTax and one from getTaxRate — neither group is satisfied,
        // and the flat pre-V40 union would have admitted the tool on either code alone.
        assertThat(namesFor(Set.of("tax:calculate", "tax:rates:view"))).isEmpty();
    }

    @Test
    @DisplayName("singleton groups behave exactly like the pre-V40 OR gate")
    void singletonGroupsBehaveAsOr() {
        // The shape every discovered (source='openapi') row carries after the V40 backfill and
        // through addToolPermission: permission_group = permission_code. Each code alone qualifies.
        UUID toolId = insertTool("SingletonGroupTool", "facade");
        grant(toolId, "alpha:read", "alpha:read");
        grant(toolId, "beta:read", "beta:read");

        assertThat(namesFor(Set.of("alpha:read"))).containsExactly("SingletonGroupTool");
        assertThat(namesFor(Set.of("beta:read"))).containsExactly("SingletonGroupTool");
        assertThat(namesFor(Set.of("gamma:read"))).isEmpty();
    }

    @Test
    @DisplayName("addToolPermission writes the grant as its own singleton group (OR-preserving)")
    void addToolPermissionWritesSingletonGroup() {
        UUID toolId = insertTool("DiscoveredOp", "openapi");

        assertThat(repository.addToolPermission(toolId, "crm:party:view")).isTrue();
        assertThat(repository.addToolPermission(toolId, "crm:party:view")).isFalse();

        assertThat(jdbcTemplate.queryForList(
                        "SELECT permission_group FROM mcp_tool_permission WHERE tool_id = ?", String.class, toolId))
                .containsExactly("crm:party:view");
    }

    // ── fail-closed invariants ────────────────────────────────────────────────

    @Test
    @DisplayName("a tool with zero permission rows is never returned")
    void zeroPermissionToolIsNeverReturned() {
        insertTool("UngatedTool", "facade");

        assertThat(namesFor(Set.of("crm:party:view"))).isEmpty();
        assertThat(namesFor(Set.of("anything:at:all"))).isEmpty();
    }

    @Test
    @DisplayName("discovered (openapi) tools are excluded from the facade gate regardless of grants")
    void discoveredToolsAreNotFacadeCandidates() {
        UUID toolId = insertTool("crm_getParty", "openapi");
        grant(toolId, "crm:party:view", "crm:party:view");

        assertThat(namesFor(Set.of("crm:party:view"))).isEmpty();
    }

    @Test
    @DisplayName("workflow state still scopes the gate")
    void workflowStateStillScopesTheGate() {
        seedCustomerFacadeTool();

        List<ToolMetadata> processing =
                repository.findEnabledByPermissionsAndWorkflow(Set.of("crm:party:view"), "PROCESSING");

        assertThat(processing).isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** The V40 CustomerFacadeTool seed: getCustomerHistory contributes no group (R3). */
    private void seedCustomerFacadeTool() {
        UUID toolId = insertTool("CustomerFacadeTool", "facade");
        grant(toolId, "getCustomer", "crm:party:view");
        grant(toolId, "searchCustomers", "crm:party:view");
    }

    /** The V40 TaxFacadeTool seed: two genuine multi-code groups plus a single-code one. */
    private void seedTaxFacadeTool() {
        UUID toolId = insertTool("TaxFacadeTool", "facade");
        grant(toolId, "calculateTax", "location:read");
        grant(toolId, "calculateTax", "tax:calculate");
        grant(toolId, "getTaxRate", "location:read");
        grant(toolId, "getTaxRate", "tax:rates:view");
        grant(toolId, "getTaxSummary", "reporting:view:financial-statements");
    }

    private UUID insertTool(String name, String source) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mcp_tool (id, name, display_name, description, domain, priority,
                                      cost_level, avg_latency_ms, enabled, handler_bean, source)
                VALUES (?, ?, ?, ?, 'test', 1.0, 'low', 200, true, ?, ?)
                """, id, name, name, name + " description", name, source);
        jdbcTemplate.update(
                "INSERT INTO mcp_tool_workflow (tool_id, workflow_state_id) VALUES (?, ?)", id, idleStateId);
        return id;
    }

    private void grant(UUID toolId, String group, String code) {
        jdbcTemplate.update(
                "INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code) VALUES (?, ?, ?)",
                toolId,
                group,
                code);
    }

    private List<String> namesFor(Set<String> permissionCodes) {
        return repository.findEnabledByPermissionsAndWorkflow(permissionCodes, IDLE).stream()
                .map(ToolMetadata::name)
                .toList();
    }
}

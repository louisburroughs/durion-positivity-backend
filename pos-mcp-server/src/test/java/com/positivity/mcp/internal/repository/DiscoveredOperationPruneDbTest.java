package com.positivity.mcp.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
 * #1632 (per-prefix prune): semantics of
 * {@link ToolMetadataRepositoryImpl#pruneDiscoveredOperationsExcept(java.util.Collection, Set)}
 * against a real (H2, PostgreSQL-compatibility) database, in the style of
 * {@link ToolPermissionGroupGatingTest}. {@code ToolMetadataRepositoryImplTest} mocks {@code
 * JdbcTemplate} and can only assert the statement's shape; this test runs the real {@code
 * name <> ALL(?) AND (domain IS NULL OR domain <> ALL(?))} SQL so the exclusion's semantics —
 * which rows actually survive a partial-discovery cycle — are pinned, not just its text.
 */
class DiscoveredOperationPruneDbTest {

    private JdbcTemplate jdbcTemplate;
    private ToolMetadataRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(
                new org.h2.Driver(),
                "jdbc:h2:mem:prune-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new ToolMetadataRepositoryImpl(jdbcTemplate);

        // Only the columns the prune statements touch. domain is nullable on purpose: discovered rows
        // written before the domain column existed (or whose path yielded no prefix) carry NULL.
        jdbcTemplate.execute("""
                CREATE TABLE mcp_tool (
                  id UUID PRIMARY KEY,
                  name VARCHAR(100) NOT NULL UNIQUE,
                  domain VARCHAR(80),
                  source VARCHAR(20) NOT NULL DEFAULT 'facade'
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
        jdbcTemplate.execute("""
                CREATE TABLE mcp_tool_workflow (
                  tool_id UUID NOT NULL,
                  workflow_state_id UUID NOT NULL,
                  PRIMARY KEY (tool_id, workflow_state_id)
                )
                """);
        jdbcTemplate.execute("CREATE TABLE mcp_tool_invocation_log (id UUID PRIMARY KEY, tool_id UUID)");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
    }

    // ── the #1632 exclusion semantics ─────────────────────────────────────────

    @Test
    @DisplayName("rows in an excluded (fetch-failed) domain survive the prune even when absent from keptNames")
    void excludedDomainRowsSurvive_staleRowsInHealthyDomainsAreDeleted() {
        // workorder's spec fetch failed this cycle → its ops are absent from keptNames because they
        // could not be SEEN, not because the service removed them.
        UUID workorderA = insertOpenApiTool("workorder_getworkorder", "workorder");
        UUID workorderB = insertOpenApiTool("workorder_listworkorders", "workorder");
        // accounting fetched fine: one op still in the spec, one gone from it.
        UUID accountingKept = insertOpenApiTool("accounting_listinvoices", "accounting");
        UUID accountingStale = insertOpenApiTool("accounting_removedop", "accounting");

        int deleted =
                repository.pruneDiscoveredOperationsExcept(List.of("accounting_listinvoices"), Set.of("workorder"));

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingNames())
                .containsExactlyInAnyOrder(
                        "workorder_getworkorder", "workorder_listworkorders", "accounting_listinvoices");
        assertThat(remainingIds())
                .contains(workorderA, workorderB, accountingKept)
                .doesNotContain(accountingStale);
    }

    @Test
    @DisplayName("a NULL-domain orphan is deleted even when exclusions are present")
    void nullDomainOrphanIsDeleted_evenWithExclusionsPresent() {
        // Deliberate semantics, pinned here: a NULL domain is definitionally NOT a member of
        // excludedDomains, so a NULL-domain orphan stays prunable. The naive predicate
        // `domain <> ALL(?)` alone would evaluate to NULL (not TRUE) for such a row and silently
        // PROTECT it forever; the production SQL adds `domain IS NULL OR ...` precisely so the
        // orphan is deleted. If this test starts failing, the exclusion predicate's NULL handling
        // changed.
        insertOpenApiTool("workorder_getworkorder", "workorder");
        insertOpenApiTool("accounting_listinvoices", "accounting");
        UUID nullDomainOrphan = insertOpenApiTool("legacy_orphanop", null);

        int deleted =
                repository.pruneDiscoveredOperationsExcept(List.of("accounting_listinvoices"), Set.of("workorder"));

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingIds()).doesNotContain(nullDomainOrphan);
        assertThat(remainingNames()).containsExactlyInAnyOrder("workorder_getworkorder", "accounting_listinvoices");
    }

    @Test
    @DisplayName("pruning a row clears its permission/workflow children and nulls its invocation-log FK")
    void pruneClearsChildRows_andPreservesInvocationLogHistory() {
        UUID stale = insertOpenApiTool("accounting_removedop", "accounting");
        UUID kept = insertOpenApiTool("accounting_listinvoices", "accounting");
        jdbcTemplate.update(
                "INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code) VALUES (?, ?, ?)",
                stale,
                "accounting:invoice:view",
                "accounting:invoice:view");
        jdbcTemplate.update(
                "INSERT INTO mcp_tool_workflow (tool_id, workflow_state_id) VALUES (?, ?)", stale, UUID.randomUUID());
        UUID logId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO mcp_tool_invocation_log (id, tool_id) VALUES (?, ?)", logId, stale);

        int deleted = repository.pruneDiscoveredOperationsExcept(List.of("accounting_listinvoices"), Set.of());

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingIds()).containsExactly(kept);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM mcp_tool_permission WHERE tool_id = ?", Integer.class, stale))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM mcp_tool_workflow WHERE tool_id = ?", Integer.class, stale))
                .isZero();
        // Audit history survives the tool's removal; only the FK is nulled.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT tool_id FROM mcp_tool_invocation_log WHERE id = ?", UUID.class, logId))
                .isNull();
    }

    // ── empty exclusions ≡ the one-arg default ────────────────────────────────

    @Test
    @DisplayName("empty exclusions behave exactly like the one-arg default: every stale row goes, any domain")
    void emptyExclusionsBehaveLikeOneArgDefault() {
        insertOpenApiTool("workorder_getworkorder", "workorder");
        insertOpenApiTool("accounting_listinvoices", "accounting");
        insertOpenApiTool("accounting_removedop", "accounting");
        insertOpenApiTool("legacy_orphanop", null);

        int deletedViaTwoArg = repository.pruneDiscoveredOperationsExcept(List.of("accounting_listinvoices"), Set.of());

        assertThat(deletedViaTwoArg).isEqualTo(3);
        assertThat(remainingNames()).containsExactly("accounting_listinvoices");

        // Re-seed the identical catalog and run the one-arg default: identical outcome.
        insertOpenApiTool("workorder_getworkorder", "workorder");
        insertOpenApiTool("accounting_removedop", "accounting");
        insertOpenApiTool("legacy_orphanop", null);

        int deletedViaDefault = repository.pruneDiscoveredOperationsExcept(List.of("accounting_listinvoices"));

        assertThat(deletedViaDefault).isEqualTo(deletedViaTwoArg);
        assertThat(remainingNames()).containsExactly("accounting_listinvoices");
    }

    // ── guards / non-candidates ───────────────────────────────────────────────

    @Test
    @DisplayName("facade-source rows are never prune candidates")
    void facadeRowsAreNeverPruned() {
        UUID facade = insertTool("CustomerFacadeTool", "customer", "facade");
        insertOpenApiTool("accounting_removedop", "accounting");

        int deleted = repository.pruneDiscoveredOperationsExcept(List.of("accounting_listinvoices"), Set.of());

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingIds()).containsExactly(facade);
    }

    @Test
    @DisplayName("an empty keep-set never prunes (whole-catalog wipe guard), regardless of exclusions")
    void emptyKeepSetNeverPrunes() {
        insertOpenApiTool("accounting_listinvoices", "accounting");
        insertOpenApiTool("legacy_orphanop", null);

        assertThat(repository.pruneDiscoveredOperationsExcept(List.of(), Set.of()))
                .isZero();
        assertThat(repository.pruneDiscoveredOperationsExcept(List.of(), Set.of("workorder")))
                .isZero();
        assertThat(remainingNames()).hasSize(2);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private UUID insertOpenApiTool(String name, String domain) {
        return insertTool(name, domain, "openapi");
    }

    private UUID insertTool(String name, String domain, String source) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO mcp_tool (id, name, domain, source) VALUES (?, ?, ?, ?)", id, name, domain, source);
        return id;
    }

    private List<String> remainingNames() {
        return jdbcTemplate.queryForList("SELECT name FROM mcp_tool", String.class);
    }

    private List<UUID> remainingIds() {
        return jdbcTemplate.queryForList("SELECT id FROM mcp_tool", UUID.class);
    }
}

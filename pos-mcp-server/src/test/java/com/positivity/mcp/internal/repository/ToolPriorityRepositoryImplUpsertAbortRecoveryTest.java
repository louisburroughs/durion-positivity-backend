package com.positivity.mcp.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression coverage for the bug Copilot flagged on PR #1956 (ToolPriorityRepositoryImpl around
 * lines 53-74): {@link ToolPriorityTuningService} now runs a whole tenant's sweep in one
 * transaction, so a duplicate-key retry that does not isolate the failed insert can hit "current
 * transaction is aborted" on PostgreSQL and skip the global rollup for the whole run.
 *
 * <p>Drives {@link ToolPriorityRepositoryImpl#upsertOverlay} through a real {@link JdbcTemplate} and
 * a real Spring-managed transaction ({@link TransactionTemplate} over {@link
 * DataSourceTransactionManager}), backed by a real H2 connection wrapped in {@link
 * PostgresAbortSimulatingConnection} so the one Postgres-specific behavior H2 does not reproduce —
 * transaction poisoning after a failed statement — is present for this test. See that class's
 * Javadoc for why a plain H2 test cannot exercise this path, and {@code
 * ToolPriorityRepositoryImplTest} for the mock-level assertions on the same method.
 */
@DisplayName("ToolPriorityRepositoryImpl.upsertOverlay under Postgres transaction-abort semantics")
class ToolPriorityRepositoryImplUpsertAbortRecoveryTest {

    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    private Connection h2;
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() throws SQLException, ClassNotFoundException {
        Class.forName("org.h2.Driver");
        h2 = DriverManager.getConnection(
                "jdbc:h2:mem:upsert-abort-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setAutoCommit(false);
        try (Statement ddl = h2.createStatement()) {
            // Mirrors db/h2-migration/V30__tool_priority_overlay.sql.
            ddl.execute("CREATE TABLE mcp_tool_priority ("
                    + " tenant_id UUID DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL,"
                    + " tool_id UUID NOT NULL,"
                    + " priority DOUBLE PRECISION NOT NULL,"
                    + " avg_latency_ms INT NOT NULL,"
                    + " updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,"
                    + " PRIMARY KEY (tenant_id, tool_id))");
        }
        h2.commit();

        // Simulates a concurrent instance's insert landing between this run's update-sees-nothing
        // read and its own insert attempt: it fires right after that first UPDATE returns (0 rows),
        // a point common to both the old and the new upsertOverlay before either has taken a
        // savepoint, so the savepoint the new code takes afterward — and any rollback to it — starts
        // after this row exists and never touches it, exactly as a different session's already-
        // committed row would be unaffected by this session's rollback to savepoint.
        Runnable insertConcurrentRow = () -> {
            try (Statement insert = h2.createStatement()) {
                insert.execute("INSERT INTO mcp_tool_priority (tool_id, priority, avg_latency_ms) VALUES ('" + TOOL_ID
                        + "', 0.11, 999)");
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to seed the concurrent row", e);
            }
        };
        Connection abortSimulating =
                PostgresAbortSimulatingConnection.wrap(h2, "UPDATE mcp_tool_priority", insertConcurrentRow);

        dataSource = new SingleConnectionDataSource(abortSimulating, true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    @DisplayName("a duplicate-key retry recovers by rolling back to its savepoint, not the whole transaction, even"
            + " when the database refuses every later statement until a rollback happens")
    void survivesADuplicateKeyUnderPostgresAbortSemantics() throws SQLException {
        ToolPriorityRepositoryImpl repository = new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK);

        assertThatCode(() ->
                        transactionTemplate.executeWithoutResult(status -> repository.upsertOverlay(TOOL_ID, 0.99, 42)))
                .doesNotThrowAnyException();

        try (PreparedStatement select =
                h2.prepareStatement("SELECT priority, avg_latency_ms FROM mcp_tool_priority WHERE tool_id = ?")) {
            select.setObject(1, TOOL_ID);
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next())
                        .as("the overlay row exists after the upsert")
                        .isTrue();
                assertThat(rows.getDouble("priority")).isEqualTo(0.99);
                assertThat(rows.getInt("avg_latency_ms")).isEqualTo(42);
                assertThat(rows.next())
                        .as("no duplicate row from the racing insert")
                        .isFalse();
            }
        }
    }
}

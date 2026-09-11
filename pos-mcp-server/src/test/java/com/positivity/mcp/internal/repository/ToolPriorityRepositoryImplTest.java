package com.positivity.mcp.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.tenancy.TenantAudited;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * Query-shape tests: the statements name no tenant (row-level security supplies it through the
 * bound connection, ADR-0062 §5), the stats query keeps the Gate 7 filters, and the overlay upsert
 * inserts without a tenant so the column default stamps the bound one.
 *
 * <p>The insert path is driven through {@code jdbcTemplate.execute(ConnectionCallback)} here, the
 * same entry point production code uses (see {@link ToolPriorityRepositoryImpl#insertOverlayRow}):
 * stubbing {@link JdbcTemplate#execute(ConnectionCallback)} to invoke the callback against a single
 * mock {@link Connection} is what lets these tests tell a savepoint taken on the connection the
 * INSERT actually runs on apart from one taken anywhere else — the defect Copilot flagged on PR
 * #1956 (this file's savepoint could land on a different pooled connection than the INSERT outside
 * an active Spring transaction, and a savepoint attempted in autocommit mode is refused by
 * PostgreSQL). {@code upsertOutsideATransaction*} below covers that non-transactional path; {@code
 * ToolPriorityRepositoryImplUpsertAbortRecoveryTest} covers the transactional one against a real
 * (H2-backed) connection and transaction manager.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolPriorityRepositoryImpl query shape")
class ToolPriorityRepositoryImplTest {

    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private Connection connection;

    @Mock
    private Savepoint savepoint;

    @Mock
    private PreparedStatement insertStatement;

    @Mock
    private SQLExceptionTranslator exceptionTranslator;

    /**
     * Every path through {@link ToolPriorityRepositoryImpl#upsertOverlay} that reaches the insert
     * attempt runs it through {@code jdbcTemplate.execute(ConnectionCallback)} against this single
     * connection, so the savepoint (taken only when {@code autoCommit} is false) and the INSERT
     * itself are provably on the same connection. Tests that exercise the insert branch call this
     * first and then set {@code connection}'s {@code autoCommit} state themselves.
     */
    @SuppressWarnings("unchecked")
    private void stubConnectionCallback() throws SQLException {
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });
        when(connection.prepareStatement(anyString())).thenReturn(insertStatement);
    }

    private void stubDuplicateKeyTranslation(SQLException raced) {
        when(jdbcTemplate.getExceptionTranslator()).thenReturn(exceptionTranslator);
        when(exceptionTranslator.translate(anyString(), anyString(), eq(raced)))
                .thenReturn(new DuplicateKeyException("mcp_tool_priority_pkey", raced));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("the stats query filters unattributed and selection-only rows and names no tenant")
    void statsQueryShape() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK).invocationStatsSince(Instant.parse("2026-09-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(LocalDateTime.class));
        assertThat(sql.getValue())
                .contains("FROM mcp_tool_invocation_log")
                .contains("tool_id IS NOT NULL")
                .contains("execution_time_ms >= 0")
                .doesNotContain("tenant_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("the overlay read is the bound tenant's rows alone: no tenant in the statement")
    void overlayReadNamesNoTenant() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        assertThat(new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK).findOverlayForCurrentTenant())
                .isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("FROM mcp_tool_priority").doesNotContain("tenant_id");
    }

    @Test
    @DisplayName("inside a transaction, upsert updates the tenant's row in place and inserts through a savepoint"
            + " when absent")
    void upsertIsUpdateThenInsert() throws SQLException {
        stubConnectionCallback();
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.setSavepoint(anyString())).thenReturn(savepoint);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), eq(TOOL_ID))).thenReturn(0);

        new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK).upsertOverlay(TOOL_ID, 0.42, 150);

        ArgumentCaptor<String> insertSql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(insertSql.capture());
        assertThat(insertSql.getValue())
                .startsWith("INSERT INTO mcp_tool_priority (tool_id, priority, avg_latency_ms)")
                .doesNotContain("tenant_id");
        verify(insertStatement).setObject(1, TOOL_ID);
        verify(insertStatement).setDouble(2, 0.42);
        verify(insertStatement).setInt(3, 150);
        verify(insertStatement).executeUpdate();
        verify(connection).setSavepoint(anyString());
        verify(connection, never()).rollback(any(Savepoint.class));
    }

    @Test
    @DisplayName("a concurrent insert of the same overlay row inside a transaction rolls back to the pre-insert"
            + " savepoint, not the whole transaction, and the loser re-applies its values by update")
    void upsertAbsorbsAConcurrentInsert() throws SQLException {
        stubConnectionCallback();
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.setSavepoint(anyString())).thenReturn(savepoint);
        SQLException raced = new SQLException("duplicate key value violates unique constraint", "23505");
        when(insertStatement.executeUpdate()).thenThrow(raced);
        stubDuplicateKeyTranslation(raced);
        // First update: no row yet. Retry update after the rollback: applied.
        when(jdbcTemplate.update(anyString(), any(), any(), any(), eq(TOOL_ID))).thenReturn(0, 1);

        assertThatCode(() -> new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK).upsertOverlay(TOOL_ID, 0.42, 150))
                .doesNotThrowAnyException();

        verify(jdbcTemplate, times(2)).update(anyString(), any(), any(), any(), eq(TOOL_ID));
        // The retry update must be preceded by a rollback to the savepoint taken before the insert:
        // on Postgres a bare retry after the duplicate-key violation would hit "current transaction
        // is aborted" instead, since the whole per-tenant sweep now runs in one transaction.
        verify(connection).setSavepoint(anyString());
        verify(connection).rollback(savepoint);
    }

    @Test
    @DisplayName("a duplicate insert whose row then cannot be updated is reported, not swallowed")
    void upsertReportsARowThatVanishedAfterTheDuplicate() throws SQLException {
        stubConnectionCallback();
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.setSavepoint(anyString())).thenReturn(savepoint);
        SQLException raced = new SQLException("duplicate key value violates unique constraint", "23505");
        when(insertStatement.executeUpdate()).thenThrow(raced);
        stubDuplicateKeyTranslation(raced);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), eq(TOOL_ID))).thenReturn(0, 0);

        assertThatThrownBy(() -> new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK).upsertOverlay(TOOL_ID, 0.42, 150))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(DuplicateKeyException.class);

        verify(connection).rollback(savepoint);
    }

    @Test
    @DisplayName("upsert of an existing row does not insert")
    void upsertExistingRowUpdatesOnly() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), eq(TOOL_ID))).thenReturn(1);

        new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK).upsertOverlay(TOOL_ID, 0.42, 150);

        verify(jdbcTemplate, never()).execute(any(ConnectionCallback.class));
    }

    /**
     * Regression coverage for the connection-identity defect Copilot flagged on PR #1956: a caller
     * outside a Spring-managed transaction (autocommit true) must not attempt a savepoint at all —
     * PostgreSQL refuses to create one in autocommit mode, and {@code DataSourceUtils.getConnection}
     * is not guaranteed to hand a manually acquired connection and {@code jdbcTemplate}'s own the
     * same physical connection once no transaction synchronization is bound. Every caller in this
     * module runs inside {@link com.positivity.mcp.internal.service.ToolPriorityTuningService}'s
     * {@code TransactionTemplate} today, but this method must behave correctly for one that does not
     * (a future caller, or a direct test such as {@code TenantIsolationIT}).
     */
    @Test
    @DisplayName("outside a transaction, upsert of a new row inserts cleanly without attempting a savepoint")
    void upsertOutsideATransactionDoesNotAttemptASavepoint() throws SQLException {
        stubConnectionCallback();
        when(connection.getAutoCommit()).thenReturn(true);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), eq(TOOL_ID))).thenReturn(0);

        new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK).upsertOverlay(TOOL_ID, 0.42, 150);

        verify(connection, never()).setSavepoint(anyString());
        verify(insertStatement).executeUpdate();
    }

    @Test
    @DisplayName("outside a transaction, a concurrent insert of the same row is absorbed without a savepoint")
    void upsertOutsideATransactionAbsorbsAConcurrentInsertWithoutASavepoint() throws SQLException {
        stubConnectionCallback();
        when(connection.getAutoCommit()).thenReturn(true);
        SQLException raced = new SQLException("duplicate key value violates unique constraint", "23505");
        when(insertStatement.executeUpdate()).thenThrow(raced);
        stubDuplicateKeyTranslation(raced);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), eq(TOOL_ID))).thenReturn(0, 1);

        assertThatCode(() -> new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK).upsertOverlay(TOOL_ID, 0.42, 150))
                .doesNotThrowAnyException();

        verify(connection, never()).setSavepoint(anyString());
        verify(connection, never()).rollback(any(Savepoint.class));
        verify(jdbcTemplate, times(2)).update(anyString(), any(), any(), any(), eq(TOOL_ID));
    }

    @Test
    @DisplayName("the JDBC access to the scoped tables carries the @TenantAudited review marker")
    void isTenantAudited() {
        assertThat(ToolPriorityRepositoryImpl.class.isAnnotationPresent(TenantAudited.class))
                .isTrue();
    }
}

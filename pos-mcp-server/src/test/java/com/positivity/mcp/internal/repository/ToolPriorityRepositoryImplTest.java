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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Query-shape tests: the statements name no tenant (row-level security supplies it through the
 * bound connection, ADR-0062 §5), the stats query keeps the Gate 7 filters, and the overlay upsert
 * inserts without a tenant so the column default stamps the bound one.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolPriorityRepositoryImpl query shape")
class ToolPriorityRepositoryImplTest {

    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private JdbcTemplate jdbcTemplate;

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
    @DisplayName("upsert updates the tenant's row in place and inserts without naming a tenant when absent")
    void upsertIsUpdateThenInsert() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), eq(TOOL_ID))).thenReturn(0);

        new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK).upsertOverlay(TOOL_ID, 0.42, 150);

        ArgumentCaptor<String> insert = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(insert.capture(), eq(TOOL_ID), eq(0.42), eq(150));
        assertThat(insert.getValue())
                .startsWith("INSERT INTO mcp_tool_priority (tool_id, priority, avg_latency_ms)")
                .doesNotContain("tenant_id");
    }

    @Test
    @DisplayName("a concurrent insert of the same overlay row is absorbed: the loser re-applies its values by update")
    void upsertAbsorbsAConcurrentInsert() {
        // First update: no row yet. Insert: another instance got there first. Second update: applied.
        when(jdbcTemplate.update(anyString(), any(), any(), any(), eq(TOOL_ID))).thenReturn(0, 1);
        when(jdbcTemplate.update(anyString(), eq(TOOL_ID), eq(0.42), eq(150)))
                .thenThrow(new DuplicateKeyException("mcp_tool_priority_pkey"));

        assertThatCode(() -> new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK).upsertOverlay(TOOL_ID, 0.42, 150))
                .doesNotThrowAnyException();

        verify(jdbcTemplate, times(2)).update(anyString(), any(), any(), any(), eq(TOOL_ID));
    }

    @Test
    @DisplayName("a duplicate insert whose row then cannot be updated is reported, not swallowed")
    void upsertReportsARowThatVanishedAfterTheDuplicate() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), eq(TOOL_ID))).thenReturn(0, 0);
        when(jdbcTemplate.update(anyString(), eq(TOOL_ID), eq(0.42), eq(150)))
                .thenThrow(new DuplicateKeyException("mcp_tool_priority_pkey"));

        assertThatThrownBy(() -> new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK).upsertOverlay(TOOL_ID, 0.42, 150))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("upsert of an existing row does not insert")
    void upsertExistingRowUpdatesOnly() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), eq(TOOL_ID))).thenReturn(1);

        new ToolPriorityRepositoryImpl(jdbcTemplate, CLOCK).upsertOverlay(TOOL_ID, 0.42, 150);

        verify(jdbcTemplate, never()).update(anyString(), eq(TOOL_ID), any(), any());
    }

    @Test
    @DisplayName("the JDBC access to the scoped tables carries the @TenantAudited review marker")
    void isTenantAudited() {
        assertThat(ToolPriorityRepositoryImpl.class.isAnnotationPresent(TenantAudited.class))
                .isTrue();
    }
}

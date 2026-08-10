package com.positivity.mcp.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ToolMetadata;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link ToolMetadataRepositoryImpl}: verifies JdbcTemplate
 * query delegation.
 */
@ExtendWith(MockitoExtension.class)
class ToolMetadataRepositoryImplTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ToolMetadataRepositoryImpl repository;

    private static final ToolMetadata SAMPLE_TOOL = new ToolMetadata(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "customerFacadeTool",
            "Customer Lookup",
            "Look up customer records",
            "customer",
            0.8,
            "low",
            50,
            true,
            "customerFacadeTool");

    @BeforeEach
    void setUp() {
        repository = new ToolMetadataRepositoryImpl(jdbcTemplate);
    }

    @Test
    @DisplayName("findEnabledByPermissionsAndWorkflow returns tools from JdbcTemplate query")
    @SuppressWarnings("unchecked")
    void findEnabledByPermissionsAndWorkflow_returnsMappedTools() {
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(SAMPLE_TOOL));

        List<ToolMetadata> result =
                repository.findEnabledByPermissionsAndWorkflow(Set.of("customer:account:view"), "IDLE");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("customerFacadeTool");
    }

    @Test
    @DisplayName("findEnabledByPermissionsAndWorkflow returns empty list when JdbcTemplate returns no rows")
    @SuppressWarnings("unchecked")
    void findEnabledByPermissionsAndWorkflow_returnsEmptyWhenNoRows() {
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());

        List<ToolMetadata> result =
                repository.findEnabledByPermissionsAndWorkflow(Set.of("customer:account:view"), "IDLE");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findEnabledByPermissionsAndWorkflow short-circuits to empty list without querying when "
            + "permissionCodes is empty")
    void findEnabledByPermissionsAndWorkflow_withEmptyPermissionCodes_shortCircuits() {
        List<ToolMetadata> result = repository.findEnabledByPermissionsAndWorkflow(Set.of(), "IDLE");

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("findEnabledByWorkflow returns workflow-scoped tools from JdbcTemplate query")
    @SuppressWarnings("unchecked")
    void findEnabledByWorkflow_returnsMappedTools() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("IDLE"))).thenReturn(List.of(SAMPLE_TOOL));

        List<ToolMetadata> result = repository.findEnabledByWorkflow("IDLE");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().domain()).isEqualTo("customer");
    }

    @Test
    @DisplayName("findTopKByEmbedding returns bounded list from JdbcTemplate query")
    @SuppressWarnings("unchecked")
    void findTopKByEmbedding_returnsBoundedList() {
        float[] embedding = new float[768];
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(PGobject.class), eq(5)))
                .thenReturn(List.of(SAMPLE_TOOL));

        List<ToolMetadata> result = repository.findTopKByEmbedding(embedding, 5);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().handlerBean()).isEqualTo("customerFacadeTool");
    }

    @Test
    @DisplayName("findTopKByEmbeddingForPermissions delegates gated ANN query to JdbcTemplate")
    @SuppressWarnings("unchecked")
    void findTopKByEmbeddingForPermissions_returnsBoundedGatedList() {
        float[] embedding = new float[768];
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(SAMPLE_TOOL));

        List<ToolMetadata> result =
                repository.findTopKByEmbeddingForPermissions(embedding, 5, Set.of("customer:account:view"), "IDLE");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("customerFacadeTool");
    }

    @Test
    @DisplayName("findTopKByEmbeddingForPermissions short-circuits to empty list without querying when "
            + "permissionCodes is empty")
    void findTopKByEmbeddingForPermissions_withEmptyPermissionCodes_shortCircuits() {
        float[] embedding = new float[768];

        List<ToolMetadata> result = repository.findTopKByEmbeddingForPermissions(embedding, 5, Set.of(), "IDLE");

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("pruneDiscoveredOperationsExcept short-circuits to 0 without querying when keptNames is empty")
    void pruneDiscoveredOperationsExcept_withEmptyKept_shortCircuits() {
        int pruned = repository.pruneDiscoveredOperationsExcept(Set.of());

        assertThat(pruned).isZero();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("pruneDiscoveredOperationsExcept returns 0 and issues no deletes when no orphans exist")
    @SuppressWarnings("unchecked")
    void pruneDiscoveredOperationsExcept_noOrphans_returnsZero() {
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());

        int pruned = repository.pruneDiscoveredOperationsExcept(Set.of("customer_getall"));

        assertThat(pruned).isZero();
        verify(jdbcTemplate, never()).update(anyString(), any(PreparedStatementSetter.class));
    }

    @Test
    @DisplayName("pruneDiscoveredOperationsExcept clears FK children before deleting parents, scoped to openapi")
    @SuppressWarnings("unchecked")
    void pruneDiscoveredOperationsExcept_deletesOrphansAndClearsChildrenInOrder() {
        UUID orphanA = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID orphanB = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(orphanA, orphanB));
        // parent DELETE (the value prune returns); child clears also return via this stub — value unused.
        when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class)))
                .thenReturn(2);

        int pruned = repository.pruneDiscoveredOperationsExcept(Set.of("customer_getall", "order_list"));

        assertThat(pruned).isEqualTo(2);
        // The orphan lookup is scoped to source='openapi' and excludes the kept names.
        verify(jdbcTemplate)
                .query(
                        contains("source = 'openapi' AND name <> ALL"),
                        any(PreparedStatementSetter.class),
                        any(RowMapper.class));
        // Children must be cleared before the parent rows are deleted (FK-safe order).
        InOrder inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate)
                .update(contains("DELETE FROM mcp_tool_permission"), any(PreparedStatementSetter.class));
        inOrder.verify(jdbcTemplate)
                .update(contains("DELETE FROM mcp_tool_workflow"), any(PreparedStatementSetter.class));
        inOrder.verify(jdbcTemplate)
                .update(contains("UPDATE mcp_tool_invocation_log"), any(PreparedStatementSetter.class));
        inOrder.verify(jdbcTemplate)
                .update(contains("DELETE FROM mcp_tool WHERE id = ANY"), any(PreparedStatementSetter.class));
    }

    @Test
    @DisplayName("findDiscoveredOperationByName only matches enabled openapi tools (execution fails closed)")
    @SuppressWarnings("unchecked")
    void findDiscoveredOperationByName_filtersOnEnabled() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("customer_getall")))
                .thenReturn(List.of());

        assertThat(repository.findDiscoveredOperationByName("customer_getall")).isEmpty();

        // A tool disabled after plan creation (e.g. emergency kill switch) must not resolve for execution.
        verify(jdbcTemplate)
                .query(contains("source = 'openapi' AND enabled = true"), any(RowMapper.class), eq("customer_getall"));
    }
}

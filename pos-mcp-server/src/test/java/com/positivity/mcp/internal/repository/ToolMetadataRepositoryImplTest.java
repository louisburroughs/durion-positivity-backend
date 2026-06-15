package com.positivity.mcp.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
}

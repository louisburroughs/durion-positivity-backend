package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ToolMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
  @DisplayName("findEnabledByRoleAndWorkflow returns tools from JdbcTemplate query")
  @SuppressWarnings("unchecked")
  void findEnabledByRoleAndWorkflow_returnsMappedTools() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("ROLE_CASHIER"), eq("IDLE")))
        .thenReturn(List.of(SAMPLE_TOOL));

    List<ToolMetadata> result = repository.findEnabledByRoleAndWorkflow("ROLE_CASHIER", "IDLE");

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().name()).isEqualTo("customerFacadeTool");
  }

  @Test
  @DisplayName("findEnabledByRoleAndWorkflow returns empty list when JdbcTemplate returns no rows")
  @SuppressWarnings("unchecked")
  void findEnabledByRoleAndWorkflow_returnsEmptyWhenNoRows() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("ROLE_CASHIER"), eq("IDLE")))
        .thenReturn(List.of());

    List<ToolMetadata> result = repository.findEnabledByRoleAndWorkflow("ROLE_CASHIER", "IDLE");

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("findTopKByEmbedding returns bounded list from JdbcTemplate query")
  @SuppressWarnings("unchecked")
  void findTopKByEmbedding_returnsBoundedList() {
    float[] embedding = new float[768];
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(embedding), eq(5)))
        .thenReturn(List.of(SAMPLE_TOOL));

    List<ToolMetadata> result = repository.findTopKByEmbedding(embedding, 5);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().handlerBean()).isEqualTo("customerFacadeTool");
  }
}

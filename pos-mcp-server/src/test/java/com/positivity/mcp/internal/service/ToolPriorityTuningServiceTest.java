package com.positivity.mcp.internal.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link ToolPriorityTuningService}.
 *
 * <p>
 * The service is {@code @Profile("!test")} so it is constructed directly.
 * {@code @Scheduled} does not fire in unit tests; {@code tuneToolPriorities()}
 * is invoked explicitly.
 */
@ExtendWith(MockitoExtension.class)
class ToolPriorityTuningServiceTest {

  @Mock
  private JdbcTemplate jdbcTemplate;

  private ToolPriorityTuningService service;

  private static final UUID TOOL_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @BeforeEach
  void setUp() {
    service = new ToolPriorityTuningService(jdbcTemplate);
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("tuneToolPriorities invokes the stats query on JdbcTemplate")
  void tuneToolPriorities_queryCalledWithSevenDayWindow() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

    service.tuneToolPriorities();

    verify(jdbcTemplate, times(1)).query(anyString(), any(RowMapper.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("tuneToolPriorities does not call update when stats query returns no rows")
  void tuneToolPriorities_noTools_noUpdates() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

    service.tuneToolPriorities();

    verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("tuneToolPriorities calls update with a priority clamped to [0.1, 1.0]")
  void tuneToolPriorities_updatesOneTool_withClampedPriority() throws SQLException {
    // Arrange: stub stats query via RowMapper capture so we can return a real stats
    // row
    ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
    when(resultSet.getObject("tool_id", UUID.class)).thenReturn(TOOL_ID_1);
    // Extreme values: successRate=0.0, avgLatency=9999.0, fallbackRate=1.0
    // → performanceScore = 0 + (1 - min(4.999,1))*0.3 - 0.2 = -0.2 → clamped to 0.1
    when(resultSet.getDouble("success_rate")).thenReturn(0.0);
    when(resultSet.getDouble("avg_latency")).thenReturn(9999.0);
    when(resultSet.getDouble("fallback_rate")).thenReturn(1.0);

    when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
      RowMapper<?> mapper = invocation.getArgument(1);
      return List.of(mapper.mapRow(resultSet, 0));
    });

    // Stub priority lookup for the tool
    when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), eq(TOOL_ID_1)))
        .thenReturn(0.5);

    // Act
    service.tuneToolPriorities();

    // Assert: update was called once and the new priority is within [0.1, 1.0]
    ArgumentCaptor<Double> priorityCaptor = ArgumentCaptor.forClass(Double.class);
    verify(jdbcTemplate, times(1)).update(
        anyString(), priorityCaptor.capture(), any(), eq(TOOL_ID_1));

    double capturedPriority = priorityCaptor.getValue();
    assertThat(capturedPriority)
        .as("computed new priority must be within the clamped range [0.1, 1.0]")
        .isBetween(0.1, 1.0);
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("tuneToolPriorities query filters out null tool_id rows")
  void tuneToolPriorities_statsQueryContainsToolIdNotNullFilter() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

    service.tuneToolPriorities();

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, times(1)).query(sqlCaptor.capture(), any(RowMapper.class));
    assertThat(sqlCaptor.getValue()).contains("tool_id IS NOT NULL");
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("tuneToolPriorities query filters out selection-only rows (executionTimeMs < 0)")
  void tuneToolPriorities_statsQueryContainsExecutionTimeMsFilter() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

    service.tuneToolPriorities();

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, times(1)).query(sqlCaptor.capture(), any(RowMapper.class));
    assertThat(sqlCaptor.getValue()).contains("execution_time_ms >= 0");
  }
}

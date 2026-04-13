package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ToolInvocationLog;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.repository.ToolAuditRepository;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ToolAuditService}.
 *
 * <p>
 * The service is {@code @Profile("!test")} and is therefore instantiated
 * directly via {@code new}, bypassing the Spring profile gate.
 */
@ExtendWith(MockitoExtension.class)
class ToolAuditServiceTest {

  @Mock
  private ToolAuditRepository auditRepository;

  private ToolAuditService service;

  private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static final ToolMetadata SAMPLE_TOOL = new ToolMetadata(
      TOOL_ID,
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
    service = new ToolAuditService(auditRepository);
  }

  @Test
  @DisplayName("logToolSelection calls repository with correct fields")
  void logToolSelection_callsRepository_withCorrectFields() {
    service.logToolSelection(SAMPLE_TOOL, "user-1", "session-abc", "find customer", 2, 0.75, true);

    verify(auditRepository, times(1)).logInvocation(argThat(log -> TOOL_ID.equals(log.toolId())
        && "user-1".equals(log.userId())
        && "session-abc".equals(log.sessionId())
        && log.semanticRank() == 2
        && Double.compare(log.finalScore(), 0.75) == 0
        && log.selected()));
  }

  @Test
  @DisplayName("logToolSelection swallows exception from repository")
  void logToolSelection_swallowsException() {
    doThrow(new RuntimeException("db error")).when(auditRepository).logInvocation(
        org.mockito.ArgumentMatchers.any(ToolInvocationLog.class));

    assertThatNoException().isThrownBy(
        () -> service.logToolSelection(SAMPLE_TOOL, "user-1", "session-abc", "find customer", 1, 0.9, true));
  }

  @Test
  @DisplayName("logToolExecution calls repository with correct fields")
  void logToolExecution_callsRepository_withCorrectFields() {
    service.logToolExecution(TOOL_ID, "user-1", true, false, 150, null);

    verify(auditRepository, times(1)).logInvocation(argThat(log -> TOOL_ID.equals(log.toolId())
        && "user-1".equals(log.userId())
        && log.success()
        && !log.fallbackInvoked()
        && log.executionTimeMs() == 150
        && log.errorType() == null));
  }

  @Test
  @DisplayName("logToolExecution swallows exception from repository")
  void logToolExecution_swallowsException() {
    doThrow(new RuntimeException("db error")).when(auditRepository).logInvocation(
        org.mockito.ArgumentMatchers.any(ToolInvocationLog.class));

    assertThatNoException().isThrownBy(() -> service.logToolExecution(TOOL_ID, "user-1", false, true, 200, "TIMEOUT"));
  }
}

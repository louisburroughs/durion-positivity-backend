package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ToolInvocationLog;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.repository.ToolAuditRepository;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ToolAuditService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ToolAuditService.class);

  private final ToolAuditRepository auditRepository;

  public ToolAuditService(@NonNull ToolAuditRepository auditRepository) {
    this.auditRepository = auditRepository;
  }

  public void logToolSelection(
      @NonNull ToolMetadata tool,
      @NonNull String userId,
      @NonNull String sessionId,
      @NonNull String userInput,
      int semanticRank,
      double finalScore,
      boolean selected) {
    try {
      ToolInvocationLog log = new ToolInvocationLog(
          tool.id(),
          userId,
          sessionId,
          userInput,
          "UNKNOWN",
          semanticRank,
          finalScore,
          selected,
          false,
          false,
          -1,
          null);
      auditRepository.logInvocation(log);
    } catch (Exception exception) {
      LOGGER.warn("Failed to write tool selection audit log for tool {}", tool.id(), exception);
    }
  }

  public void logToolExecution(
      @Nullable UUID toolId,
      @NonNull String userId,
      boolean success,
      boolean fallbackInvoked,
      int executionTimeMs,
      @Nullable String errorType) {
    try {
      ToolInvocationLog log = new ToolInvocationLog(
          toolId,
          userId,
          null,
          null,
          null,
          -1,
          0.0,
          true,
          success,
          fallbackInvoked,
          executionTimeMs,
          errorType);
      auditRepository.logInvocation(log);
    } catch (Exception exception) {
      LOGGER.warn("Failed to write tool execution audit log for tool {}", toolId, exception);
    }
  }
}

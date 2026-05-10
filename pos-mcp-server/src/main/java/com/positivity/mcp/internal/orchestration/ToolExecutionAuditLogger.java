package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.service.ToolAuditService;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
final class ToolExecutionAuditLogger {

    private final ToolAuditService toolAuditService;

    ToolExecutionAuditLogger(@NonNull ToolAuditService toolAuditService) {
        this.toolAuditService = toolAuditService;
    }

    void logToolExecution(
            @Nullable UUID toolId,
            @NonNull String userId,
            boolean success,
            boolean fallbackInvoked,
            int executionTimeMs,
            @Nullable String errorType) {
        toolAuditService.logToolExecution(toolId, userId, success, fallbackInvoked, executionTimeMs, errorType);
    }
}

package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ToolInvocationLog;
import org.jspecify.annotations.NonNull;

public interface ToolAuditRepository {

    void logInvocation(@NonNull ToolInvocationLog log);
}

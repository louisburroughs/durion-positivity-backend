package com.positivity.mcp.internal.dto;

import java.util.Map;
import java.util.UUID;

public record NltiResponseV1(
        UUID requestId,
        UUID correlationId,
        UUID sessionId,
        String status,
        Object result,
        Map<String, Object> meta) {
}

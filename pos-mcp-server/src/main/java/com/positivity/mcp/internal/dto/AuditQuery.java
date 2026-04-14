package com.positivity.mcp.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditQuery(UUID correlationId, Instant from, Instant to, String eventType) {}

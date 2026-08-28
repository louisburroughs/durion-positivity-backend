package com.positivity.mcp.internal.dto;

import com.positivity.mcp.internal.enums.NltiAuditEventType;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Values needed to append one entry to the NLTI audit ledger, kept separate from the persisted
 * {@code NltiAuditEvent} entity so callers never construct or hold a managed entity just to shuttle
 * these fields across the service seam. A null {@code timestamp} is stamped with the current time by
 * the ledger on append.
 */
public record AuditEventAppend(
        @Nullable UUID id,
        @NonNull UUID correlationId,
        @Nullable UUID sessionId,
        @Nullable UUID requestId,
        @NonNull NltiAuditEventType eventType,
        @Nullable OffsetDateTime timestamp,
        @Nullable String payloadRef) {}

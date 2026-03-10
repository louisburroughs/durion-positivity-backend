package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.AuditLogEventDto;
import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Public API for immutable audit event operations.
 *
 * Issue: #41
 */
public interface AuditEventService {

    AuditLogEventDto createEvent(@NonNull AuditLogEventRequest request);

    AuditLogEventDto getEvent(@NonNull UUID eventId);

    List<AuditLogEventDto> searchEvents(String entityId, String entityType, Instant from, Instant to);

    List<AuditLogEventDto> searchByEventType(@NonNull String eventType);
}

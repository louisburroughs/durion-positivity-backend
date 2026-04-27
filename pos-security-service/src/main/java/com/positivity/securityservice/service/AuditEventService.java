package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.AuditLogEventDto;
import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.internal.dto.AuditEventSearchFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /**
     * Searches audit events using a rich filter set with pagination.
     *
     * @param filter   search criteria (all fields optional)
     * @param pageable pagination specification
     * @return page of matching audit events
     */
    @NonNull
    Page<AuditLogEventDto> searchEventsFiltered(@NonNull AuditEventSearchFilter filter, @NonNull Pageable pageable);
}

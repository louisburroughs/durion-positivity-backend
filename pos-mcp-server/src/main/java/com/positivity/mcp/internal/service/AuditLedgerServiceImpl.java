package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.AuditEventResponse;
import com.positivity.mcp.internal.dto.AuditQuery;
import com.positivity.mcp.internal.entity.NltiAuditEvent;
import com.positivity.mcp.internal.enums.NltiAuditEventType;
import com.positivity.mcp.internal.repository.NltiAuditEventRepository;
import com.positivity.mcp.service.AuditLedgerService;
import com.positivity.security.common.SecurityContextHelper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** Append-only audit ledger for NLTI request lifecycle events. Issue: NLTI-007 */
@Service
public class AuditLedgerServiceImpl implements AuditLedgerService {

    private static final String AUDIT_WRITE_FAILURE_COUNTER = "nlt.audit.write_failures";

    private final NltiAuditEventRepository auditEventRepository;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Set<UUID> recentlyAppended = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public AuditLedgerServiceImpl(
            NltiAuditEventRepository auditEventRepository,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.auditEventRepository = auditEventRepository;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void append(@NonNull NltiAuditEvent event) {
        if (event.getTimestamp() == null) {
            event.setTimestamp(OffsetDateTime.now(clock));
        }

        event.setActorSubjectId(SecurityContextHelper.getCurrentUsernameOrDefault("system"));

        try {
            UUID eventId = event.getId();
            if (eventId != null && (recentlyAppended.contains(eventId) || auditEventRepository.existsById(eventId))) {
                return;
            }
            if (eventId != null) {
                recentlyAppended.add(eventId);
            }
            auditEventRepository.save(event);
        } catch (RuntimeException ex) {
            try {
                meterRegistry.counter(AUDIT_WRITE_FAILURE_COUNTER).increment();
            } catch (RuntimeException ignored) {
                // Metric emission failure must not block any path.
            }
            // For destructive execution events, propagate failure to block execution.
            if (event.getEventType() != null && isDestructiveEventType(event.getEventType())) {
                throw ex;
            }
        }
    }

    @Override
    @NonNull
    public Page<AuditEventResponse> query(@NonNull AuditQuery query, @NonNull Pageable pageable) {
        if (query.correlationId() != null) {
            return auditEventRepository
                    .findByCorrelationIdOrderByTimestampAsc(query.correlationId(), pageable)
                    .map(this::toResponse);
        }

        if (query.from() != null && query.to() != null) {
            OffsetDateTime from = OffsetDateTime.ofInstant(query.from(), ZoneOffset.UTC);
            OffsetDateTime to = OffsetDateTime.ofInstant(query.to(), ZoneOffset.UTC);
            return auditEventRepository
                    .findByTimestampBetweenOrderByTimestampAsc(from, to, pageable)
                    .map(this::toResponse);
        } else if (query.eventType() != null) {
            try {
                NltiAuditEventType eventType = NltiAuditEventType.valueOf(query.eventType());
                return auditEventRepository.findByEventTypeOrderByTimestampAsc(eventType, pageable).map(this::toResponse);
            } catch (IllegalArgumentException ex) {
                return Page.empty(pageable);
            }
        }

        return auditEventRepository.findAll(pageable).map(this::toResponse);
    }

    private static boolean isDestructiveEventType(NltiAuditEventType eventType) {
        return eventType == NltiAuditEventType.EXECUTION_STEP
                || eventType == NltiAuditEventType.EXECUTION_COMPLETE
                || eventType == NltiAuditEventType.EXECUTION_FAILED;
    }

    private AuditEventResponse toResponse(NltiAuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getCorrelationId(),
                event.getEventType().name(),
                event.getTimestamp().toInstant(),
                event.getActorSubjectId(),
                event.getPayloadRef());
    }
}

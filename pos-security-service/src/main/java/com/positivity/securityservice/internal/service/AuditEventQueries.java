package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.AuditEventSearchFilter;
import com.positivity.securityservice.internal.dto.AuditLogEventDto;
import com.positivity.securityservice.internal.entity.AuditLogEvent;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.domain.Specification;

/**
 * The one audit-event filter path, shared by {@code searchAuditEvents} and the asynchronous export
 * (#2408), so an export always covers exactly the events the same filters would page through.
 */
final class AuditEventQueries {

    private AuditEventQueries() {}

    /** Rejects a filter whose date window is empty or inverted. */
    static void validate(@NonNull AuditEventSearchFilter filter) {
        if (filter.getFromDate() != null
                && filter.getToDate() != null
                && !filter.getFromDate().isBefore(filter.getToDate())) {
            throw new SecurityValidationException("fromDate must be before toDate");
        }
    }

    @NonNull
    static Specification<AuditLogEvent> specification(@NonNull AuditEventSearchFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.getFromDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), filter.getFromDate()));
            }
            if (filter.getToDate() != null) {
                predicates.add(cb.lessThan(root.get("timestamp"), filter.getToDate()));
            }
            if (!isBlank(filter.getEventType())) {
                predicates.add(cb.equal(root.get("eventType"), filter.getEventType()));
            }
            if (!isBlank(filter.getActorId())) {
                predicates.add(cb.equal(root.get("actorId"), filter.getActorId()));
            }
            if (!isBlank(filter.getAggregateId())) {
                predicates.add(cb.equal(root.get("entityId"), filter.getAggregateId()));
            }

            // TODO(B-3): workorderId filter - column 'workorder_id' not yet indexed on
            // audit_log_event; planned for follow-on story
            // TODO(B-3): movementId filter - column not yet present on audit_log_event
            // TODO(B-3): productId filter - column not yet present
            // TODO(B-3): sku filter - column not yet present
            // TODO(B-3): correlationId filter - column not yet present
            // TODO(B-3): reasonCode filter - column not yet present
            // TODO(B-3): locationIds filter - column not yet present
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    @NonNull
    static AuditLogEventDto toDto(@NonNull AuditLogEvent event) {
        return AuditLogEventDto.builder()
                .eventId(event.getEventId())
                .timestamp(event.getTimestamp())
                .eventType(event.getEventType())
                .actorId(event.getActorId())
                .entityId(event.getEntityId())
                .entityType(event.getEntityType())
                .oldValue(event.getOldValue())
                .newValue(event.getNewValue())
                .context(event.getContext())
                .build();
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

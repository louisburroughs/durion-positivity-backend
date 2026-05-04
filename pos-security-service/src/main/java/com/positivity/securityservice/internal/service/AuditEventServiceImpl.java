package com.positivity.securityservice.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.securityservice.internal.dto.AuditEventSearchFilter;
import com.positivity.securityservice.internal.dto.AuditLogEventDto;
import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.internal.entity.AuditLogEvent;
import com.positivity.securityservice.internal.repository.AuditLogEventRepository;
import com.positivity.securityservice.service.AuditEventService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for immutable audit event creation and retrieval.
 *
 * Issue: #41
 */
@Service
@RequiredArgsConstructor
public class AuditEventServiceImpl implements AuditEventService {

    private final AuditLogEventRepository auditLogEventRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public AuditLogEventDto createEvent(@NonNull AuditLogEventRequest request) {
        validateCreateRequest(request);

        AuditLogEvent event = new AuditLogEvent();
        event.setEventType(request.getEventType());
        event.setActorId(resolveActorId());
        event.setEntityId(request.getEntityId());
        event.setEntityType(request.getEntityType());
        event.setOldValue(writeJson(request.getOldValue()));
        event.setNewValue(writeJson(request.getNewValue()));
        event.setContext(writeJson(request.getContext()));

        AuditLogEvent saved = auditLogEventRepository.save(event);
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditLogEventDto getEvent(@NonNull UUID eventId) {
        AuditLogEvent event = auditLogEventRepository
                .findById(eventId)
                .orElseThrow(
                        () -> new jakarta.persistence.EntityNotFoundException("Audit event not found: " + eventId));
        return toDto(event);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditLogEventDto> searchEvents(String entityId, String entityType, Instant from, Instant to) {
        if (isBlank(entityId) || isBlank(entityType)) {
            throw new IllegalArgumentException("entityId and entityType are required");
        }

        List<AuditLogEvent> events;
        if (from != null && to != null) {
            events = auditLogEventRepository.findByEntityIdAndEntityTypeAndTimestampBetweenOrderByTimestampDesc(
                    entityId, entityType, from, to);
        } else {
            events = auditLogEventRepository.findByEntityIdAndEntityTypeOrderByTimestampDesc(entityId, entityType);
        }

        return events.stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditLogEventDto> searchByEventType(@NonNull String eventType) {
        if (isBlank(eventType)) {
            throw new IllegalArgumentException("eventType is required");
        }

        return auditLogEventRepository.findByEventTypeOrderByTimestampDesc(eventType).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public Page<AuditLogEventDto> searchEventsFiltered(
            @NonNull AuditEventSearchFilter filter, @NonNull Pageable pageable) {
        if (filter.getFromDate() != null && filter.getToDate() != null
                && !filter.getFromDate().isBefore(filter.getToDate())) {
            throw new IllegalArgumentException("fromDate must be before toDate");
        }

        Specification<AuditLogEvent> specification = (root, query, cb) -> {
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
        return auditLogEventRepository.findAll(specification, pageable).map(this::toDto);
    }

    private void validateCreateRequest(@NonNull AuditLogEventRequest request) {
        if (isBlank(request.getEventType())) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (isBlank(request.getEntityId())) {
            throw new IllegalArgumentException("entityId is required");
        }
        if (isBlank(request.getEntityType())) {
            throw new IllegalArgumentException("entityType is required");
        }
        if (request.getOldValue() == null) {
            throw new IllegalArgumentException("oldValue is required");
        }
        if (request.getNewValue() == null) {
            throw new IllegalArgumentException("newValue is required");
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize JSON field", exception);
        }
    }

    private AuditLogEventDto toDto(AuditLogEvent event) {
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String resolveActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !isBlank(authentication.getName())) {
            return authentication.getName();
        }
        return "system";
    }
}

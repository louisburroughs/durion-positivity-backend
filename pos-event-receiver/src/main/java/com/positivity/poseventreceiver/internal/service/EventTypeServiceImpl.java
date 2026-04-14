package com.positivity.poseventreceiver.internal.service;

import com.positivity.poseventreceiver.dao.EventDao;
import com.positivity.poseventreceiver.internal.dto.EventTypeMapper;
import com.positivity.poseventreceiver.internal.dto.EventTypeRequest;
import com.positivity.poseventreceiver.internal.dto.EventTypeResponse;
import com.positivity.poseventreceiver.service.EventTypeService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Service layer for EventType lifecycle and validation.
 */
@Service
@RequiredArgsConstructor
public class EventTypeServiceImpl implements EventTypeService {
    private static final Pattern TYPE_CODE_PATTERN = Pattern.compile("^[A-Z0-9_]+$");
    private static final Pattern API_VERSION_PATTERN = Pattern.compile("^[0-9]+$");

    private final EventDao eventDao;

    @Override
    @NonNull
    public List<EventTypeResponse> getAllEventTypes() {
        return eventDao.getAllEventTypes().stream()
                .map(EventTypeMapper::toResponse)
                .toList();
    }

    @Override
    @NonNull
    public List<EventTypeResponse> getActiveEventTypes() {
        return eventDao.getActiveEventTypes().stream()
                .map(EventTypeMapper::toResponse)
                .toList();
    }

    @Override
    public Optional<EventTypeResponse> getEventTypeById(@NonNull UUID id) {
        validateId(id);
        return eventDao.getEventType(id).map(EventTypeMapper::toResponse);
    }

    @Override
    public Optional<EventTypeResponse> getEventTypeByCode(@NonNull String typeCode) {
        String normalizedTypeCode = normalizeTypeCode(typeCode, "typeCode");
        return eventDao.getEventTypeByCode(normalizedTypeCode).map(EventTypeMapper::toResponse);
    }

    @Override
    public Optional<EventTypeResponse> createEventType(@NonNull EventTypeRequest request) {
        validateRequest(request, true);
        String normalizedTypeCode = normalizeTypeCode(request.getTypeCode(), "typeCode");

        if (eventDao.getEventTypeByCode(normalizedTypeCode).isPresent()) {
            return Optional.empty();
        }

        var eventType = EventTypeMapper.toNewEntity(normalizedTypeCode, request);
        var created = eventDao.saveEventType(eventType);
        return Optional.of(EventTypeMapper.toResponse(created));
    }

    @Override
    @NonNull
    public EventTypeResponse upsertEventType(@NonNull String typeCode, @NonNull EventTypeRequest request) {
        String normalizedTypeCode = normalizeTypeCode(typeCode, "typeCode");
        validateRequest(request, false);

        String requestTypeCode = request.getTypeCode();
        if (requestTypeCode != null && !requestTypeCode.isBlank()) {
            String normalizedRequestTypeCode = normalizeTypeCode(requestTypeCode, "typeCode");
            if (!normalizedTypeCode.equals(normalizedRequestTypeCode)) {
                throw new IllegalArgumentException("Path typeCode must match request typeCode when provided");
            }
        }

        return eventDao.getEventTypeByCode(normalizedTypeCode)
                .map(existingType -> {
                    EventTypeMapper.applyRequest(existingType, request);
                    return EventTypeMapper.toResponse(eventDao.saveEventType(existingType));
                })
                .orElseGet(() -> {
                    var eventType = EventTypeMapper.toNewEntity(normalizedTypeCode, request);
                    return EventTypeMapper.toResponse(eventDao.saveEventType(eventType));
                });
    }

    @Override
    public Optional<EventTypeResponse> updateEventType(@NonNull UUID id, @NonNull EventTypeRequest request) {
        validateId(id);
        validateRequest(request, false);

        return eventDao.getEventType(id).map(eventType -> {
            EventTypeMapper.applyRequest(eventType, request);
            return EventTypeMapper.toResponse(eventDao.saveEventType(eventType));
        });
    }

    @Override
    public boolean deleteEventType(@NonNull UUID id) {
        validateId(id);
        if (!eventDao.eventTypeExists(id)) {
            return false;
        }
        eventDao.deleteEventType(id);
        return true;
    }

    private void validateId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
    }

    private void validateRequest(EventTypeRequest request, boolean requireTypeCode) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (requireTypeCode) {
            normalizeTypeCode(request.getTypeCode(), "typeCode");
        }
        validateDescription(request.getDescription());
        validateApiVersion(request.getApiVersion());
        validateThresholds(request.getP50Micros(), request.getP95Micros(), request.getP99Micros());
    }

    private String normalizeTypeCode(String typeCode, String fieldName) {
        if (typeCode == null || typeCode.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String normalized = typeCode.trim().toUpperCase(Locale.ROOT);
        if (!TYPE_CODE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must contain only uppercase letters, digits, and underscores");
        }
        return normalized;
    }

    private void validateDescription(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
    }

    private void validateApiVersion(String apiVersion) {
        if (apiVersion == null) {
            return;
        }
        String normalized = apiVersion.trim();
        if (normalized.isEmpty() || !API_VERSION_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("apiVersion must be numeric");
        }
    }

    private void validateThresholds(Long p50Micros, Long p95Micros, Long p99Micros) {
        if (p50Micros != null && p50Micros <= 0) {
            throw new IllegalArgumentException("p50Micros must be positive");
        }
        if (p95Micros != null && p95Micros <= 0) {
            throw new IllegalArgumentException("p95Micros must be positive");
        }
        if (p99Micros != null && p99Micros <= 0) {
            throw new IllegalArgumentException("p99Micros must be positive");
        }
        if (p50Micros != null && p95Micros != null && p50Micros > p95Micros) {
            throw new IllegalArgumentException("p50Micros must be less than or equal to p95Micros");
        }
        if (p95Micros != null && p99Micros != null && p95Micros > p99Micros) {
            throw new IllegalArgumentException("p95Micros must be less than or equal to p99Micros");
        }
        if (p50Micros != null && p99Micros != null && p50Micros > p99Micros) {
            throw new IllegalArgumentException("p50Micros must be less than or equal to p99Micros");
        }
    }
}

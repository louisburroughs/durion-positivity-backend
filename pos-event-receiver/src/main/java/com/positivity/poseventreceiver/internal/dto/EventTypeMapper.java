package com.positivity.poseventreceiver.internal.dto;

import com.positivity.poseventreceiver.internal.entity.EventType;
import org.jspecify.annotations.NonNull;

/**
 * Maps event type entities to/from API DTOs.
 */
public final class EventTypeMapper {

    private EventTypeMapper() {
    }

    public static @NonNull EventTypeResponse toResponse(@NonNull EventType eventType) {
        return new EventTypeResponse(
                eventType.getId(),
                eventType.getTypeCode(),
                eventType.getDescription(),
                eventType.isActive(),
                eventType.getApiVersion(),
                eventType.getP50Micros(),
                eventType.getP95Micros(),
                eventType.getP99Micros());
    }

    public static @NonNull EventType toNewEntity(@NonNull String typeCode, @NonNull EventTypeRequest request) {
        EventType eventType = new EventType(typeCode, request.getDescription());
        applyRequest(eventType, request);
        return eventType;
    }

    public static void applyRequest(@NonNull EventType eventType, @NonNull EventTypeRequest request) {
        eventType.setDescription(request.getDescription());
        eventType.setActive(request.isActive());
        if (request.getApiVersion() != null) {
            eventType.setApiVersion(request.getApiVersion());
        }
        if (request.getP50Micros() != null) {
            eventType.setP50Micros(request.getP50Micros());
        }
        if (request.getP95Micros() != null) {
            eventType.setP95Micros(request.getP95Micros());
        }
        if (request.getP99Micros() != null) {
            eventType.setP99Micros(request.getP99Micros());
        }
    }
}

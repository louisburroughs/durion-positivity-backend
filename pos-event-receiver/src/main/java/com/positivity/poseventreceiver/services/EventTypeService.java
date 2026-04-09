package com.positivity.poseventreceiver.services;

import com.positivity.poseventreceiver.internal.dto.EventTypeRequest;
import com.positivity.poseventreceiver.internal.dto.EventTypeResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service API for EventType management.
 */
public interface EventTypeService {

    @NonNull
    List<EventTypeResponse> getAllEventTypes();

    @NonNull
    List<EventTypeResponse> getActiveEventTypes();

    Optional<EventTypeResponse> getEventTypeById(@NonNull UUID id);

    Optional<EventTypeResponse> getEventTypeByCode(@NonNull String typeCode);

    Optional<EventTypeResponse> createEventType(@NonNull EventTypeRequest request);

    @NonNull
    EventTypeResponse upsertEventType(@NonNull String typeCode, @NonNull EventTypeRequest request);

    Optional<EventTypeResponse> updateEventType(@NonNull UUID id, @NonNull EventTypeRequest request);

    boolean deleteEventType(@NonNull UUID id);
}

package com.positivity.poseventreceiver.dao;

import com.positivity.poseventreceiver.internal.dto.EmitEventRequest;
import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import com.positivity.poseventreceiver.internal.entity.EventType;
import com.positivity.poseventreceiver.internal.entity.PreregisteredEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface EventDao {
    boolean isPreregistered(@NonNull String id);

    EmittedEvent saveEmittedEvent(@NonNull EmittedEvent event);

    EmittedEvent saveEmittedEvent(@NonNull EmitEventRequest request);

    Optional<PreregisteredEvent> getPreregisteredEvent(@NonNull String id);

    // EventType operations
    EventType saveEventType(@NonNull EventType eventType);

    Optional<EventType> getEventType(@NonNull UUID id);

    Optional<EventType> getEventTypeByCode(@NonNull String typeCode);

    List<EventType> getAllEventTypes();

    List<EventType> getActiveEventTypes();

    void deleteEventType(@NonNull UUID id);

    boolean eventTypeExists(@NonNull UUID id);
}

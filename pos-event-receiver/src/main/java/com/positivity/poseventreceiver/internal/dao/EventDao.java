package com.positivity.poseventreceiver.internal.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;

import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import com.positivity.poseventreceiver.internal.entity.EventType;
import com.positivity.poseventreceiver.internal.entity.PreregisteredEvent;

public interface EventDao {
    boolean isPreregistered(@NonNull String id);

    EmittedEvent saveEmittedEvent(@NonNull EmittedEvent event);

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

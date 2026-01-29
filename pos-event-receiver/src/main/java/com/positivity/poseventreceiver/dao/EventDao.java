package com.positivity.poseventreceiver.dao;

import java.util.List;
import java.util.Optional;

import com.positivity.poseventreceiver.entity.EmittedEvent;
import com.positivity.poseventreceiver.entity.EventType;
import com.positivity.poseventreceiver.entity.PreregisteredEvent;

public interface EventDao {
    boolean isPreregistered(String id);

    EmittedEvent saveEmittedEvent(EmittedEvent event);

    Optional<PreregisteredEvent> getPreregisteredEvent(String id);

    // EventType operations
    EventType saveEventType(EventType eventType);

    Optional<EventType> getEventType(Long id);

    Optional<EventType> getEventTypeByCode(String typeCode);

    List<EventType> getAllEventTypes();

    List<EventType> getActiveEventTypes();

    void deleteEventType(Long id);

    boolean eventTypeExists(Long id);
}

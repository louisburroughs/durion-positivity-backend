package com.positivity.poseventreceiver.dao;

import com.positivity.poseventreceiver.entity.EmittedEvent;
import com.positivity.poseventreceiver.entity.EventType;
import com.positivity.poseventreceiver.entity.PreregisteredEvent;
import com.positivity.poseventreceiver.repository.EmittedEventRepository;
import com.positivity.poseventreceiver.repository.EventTypeRepository;
import com.positivity.poseventreceiver.repository.PreregisteredEventRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class EventDaoImpl implements EventDao {
    private final PreregisteredEventRepository preregRepo;
    private final EmittedEventRepository emittedRepo;
    private final EventTypeRepository eventTypeRepo;

    public EventDaoImpl(PreregisteredEventRepository preregRepo, EmittedEventRepository emittedRepo,
            EventTypeRepository eventTypeRepo) {
        this.preregRepo = preregRepo;
        this.emittedRepo = emittedRepo;
        this.eventTypeRepo = eventTypeRepo;
    }

    @Override
    public boolean isPreregistered(String id) {
        return preregRepo.existsById(id);
    }

    @Override
    public EmittedEvent saveEmittedEvent(EmittedEvent event) {
        return emittedRepo.save(event);
    }

    @Override
    public Optional<PreregisteredEvent> getPreregisteredEvent(String id) {
        return preregRepo.findById(id);
    }

    @Override
    public EventType saveEventType(EventType eventType) {
        return eventTypeRepo.save(eventType);
    }

    @Override
    public Optional<EventType> getEventType(Long id) {
        return eventTypeRepo.findById(id);
    }

    @Override
    public Optional<EventType> getEventTypeByCode(String typeCode) {
        return eventTypeRepo.findByTypeCode(typeCode);
    }

    @Override
    public List<EventType> getAllEventTypes() {
        return eventTypeRepo.findAll();
    }

    @Override
    public List<EventType> getActiveEventTypes() {
        return eventTypeRepo.findByActive(true);
    }

    @Override
    public void deleteEventType(Long id) {
        eventTypeRepo.deleteById(id);
    }

    @Override
    public boolean eventTypeExists(Long id) {
        return eventTypeRepo.existsById(id);
    }
}

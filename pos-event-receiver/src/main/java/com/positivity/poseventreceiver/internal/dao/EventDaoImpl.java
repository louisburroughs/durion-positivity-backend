package com.positivity.poseventreceiver.internal.dao;

import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import com.positivity.poseventreceiver.internal.entity.EventType;
import com.positivity.poseventreceiver.internal.entity.PreregisteredEvent;
import com.positivity.poseventreceiver.internal.repository.EmittedEventRepository;
import com.positivity.poseventreceiver.internal.repository.EventTypeRepository;
import com.positivity.poseventreceiver.internal.repository.PreregisteredEventRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Service
public class EventDaoImpl implements EventDao {
    private final PreregisteredEventRepository preregRepo;
    private final EmittedEventRepository emittedRepo;
    private final EventTypeRepository eventTypeRepo;

    /** Thread-safe queue for batching emitted events */
    private final ConcurrentLinkedQueue<EmittedEvent> eventBatch = new ConcurrentLinkedQueue<>();

    public EventDaoImpl(PreregisteredEventRepository preregRepo, EmittedEventRepository emittedRepo,
            EventTypeRepository eventTypeRepo) {
        this.preregRepo = preregRepo;
        this.emittedRepo = emittedRepo;
        this.eventTypeRepo = eventTypeRepo;
    }

    @Override
    public boolean isPreregistered(@NonNull String id) {
        return preregRepo.existsById(id);
    }

    @Override
    public EmittedEvent saveEmittedEvent(@NonNull EmittedEvent event) {
        eventBatch.offer(event);
        log.debug("Event queued for batch save: {} (queue size: {})", event.getId(), eventBatch.size());
        return event;
    }

    /**
     * Flushes batched events to the database every 5 seconds.
     * Uses bulk insert for improved performance.
     */
    @Scheduled(fixedRate = 5000)
    public void flushEventBatch() {
        if (eventBatch.isEmpty()) {
            return;
        }

        List<EmittedEvent> eventsToSave = new ArrayList<>();
        EmittedEvent event;
        while ((event = eventBatch.poll()) != null) {
            eventsToSave.add(event);
        }

        if (!eventsToSave.isEmpty()) {
            try {
                List<EmittedEvent> savedEvents = emittedRepo.saveAll(eventsToSave);
                log.info("Flushed batch of {} events to database", savedEvents.size());
            } catch (Exception e) {
                log.error("Failed to flush event batch of size {}: {}", eventsToSave.size(), e.getMessage(), e);
                // Re-queue failed events for retry
                eventBatch.addAll(eventsToSave);
            }
        }
    }

    /**
     * Ensures any remaining events are flushed on shutdown.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down EventDaoImpl, flushing remaining events...");
        flushEventBatch();
    }

    @Override
    public Optional<PreregisteredEvent> getPreregisteredEvent(@NonNull String id) {
        return preregRepo.findById(id);
    }

    @Override
    public EventType saveEventType(@NonNull EventType eventType) {
        return eventTypeRepo.save(eventType);
    }

    @Override
    public Optional<EventType> getEventType(@NonNull UUID id) {
        return eventTypeRepo.findById(id);
    }

    @Override
    public Optional<EventType> getEventTypeByCode(@NonNull String typeCode) {
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
    public void deleteEventType(@NonNull UUID id) {
        eventTypeRepo.deleteById(id);
    }

    @Override
    public boolean eventTypeExists(@NonNull UUID id) {
        return eventTypeRepo.existsById(id);
    }
}

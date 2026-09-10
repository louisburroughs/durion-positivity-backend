package com.positivity.poseventreceiver.internal.dao;

import com.positivity.poseventreceiver.internal.dto.EmitEventRequest;
import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import com.positivity.poseventreceiver.internal.entity.EventType;
import com.positivity.poseventreceiver.internal.entity.PreregisteredEvent;
import com.positivity.poseventreceiver.internal.repository.EmittedEventRepository;
import com.positivity.poseventreceiver.internal.repository.EventTypeRepository;
import com.positivity.poseventreceiver.internal.repository.PreregisteredEventRepository;
import com.positivity.tenancy.PlatformScoped;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantResolver;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EventDaoImpl implements EventDao {
    private final PreregisteredEventRepository preregRepo;
    private final EmittedEventRepository emittedRepo;
    private final EventTypeRepository eventTypeRepo;

    /** Resolves the tenant of the request an event arrives under (bound by the tenancy filter). */
    private final TenantResolver tenantResolver;

    /**
     * Each queued event carries the tenant it arrived under (ADR-0062 §3): the flush runs on the
     * scheduler thread, unbound, and saves every event under the tenant captured here.
     */
    record QueuedEvent(UUID tenantId, EmittedEvent event) {}

    /** Thread-safe queue for batching emitted events. */
    private final ConcurrentLinkedQueue<QueuedEvent> eventBatch = new ConcurrentLinkedQueue<>();

    public EventDaoImpl(
            @NonNull PreregisteredEventRepository preregRepo,
            @NonNull EmittedEventRepository emittedRepo,
            @NonNull EventTypeRepository eventTypeRepo,
            @NonNull TenantResolver tenantResolver) {
        this.tenantResolver = tenantResolver;
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
        eventBatch.offer(new QueuedEvent(tenantResolver.require(), event));
        log.debug("Event queued for batch save: {} (queue size: {})", event.getId(), eventBatch.size());
        return event;
    }

    @Override
    public EmittedEvent saveEmittedEvent(@NonNull EmitEventRequest request) {
        EmittedEvent event = new EmittedEvent(
                request.id(),
                request.apiVersion(),
                request.timestamp(),
                request.elapsedMs(),
                request.publishedAt(),
                request.entityId());
        return saveEmittedEvent(event);
    }

    /**
     * Flushes batched events to the database every 5 seconds.
     * Uses bulk insert for improved performance.
     */
    @PlatformScoped(
            reason = "drains the in-memory batch for every tenant; each event is saved under the tenant captured"
                    + " when it was queued, so no row is written unbound")
    @Scheduled(fixedRate = 5000)
    public void flushEventBatch() {
        if (eventBatch.isEmpty()) {
            return;
        }

        Map<UUID, List<EmittedEvent>> byTenant = new LinkedHashMap<>();
        QueuedEvent queued;
        while ((queued = eventBatch.poll()) != null) {
            byTenant.computeIfAbsent(queued.tenantId(), id -> new ArrayList<>()).add(queued.event());
        }

        byTenant.forEach((tenantId, eventsToSave) -> {
            try {
                List<EmittedEvent> savedEvents =
                        TenantContext.callAs(tenantId, () -> emittedRepo.saveAll(eventsToSave));
                log.info("Flushed batch of {} events to database for tenant {}", savedEvents.size(), tenantId);
            } catch (Exception e) {
                log.error(
                        "Failed to flush event batch of size {} for tenant {}: {}",
                        eventsToSave.size(),
                        tenantId,
                        e.getMessage(),
                        e);
                // Re-queue failed events for retry, under the same tenant
                eventsToSave.forEach(event -> eventBatch.add(new QueuedEvent(tenantId, event)));
            }
        });
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

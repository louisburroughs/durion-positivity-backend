package com.positivity.poseventreceiver.internal.config;

import com.positivity.events.EventTypeInitializerSupport;
import com.positivity.events.EventTypeRegistration;
import com.positivity.poseventreceiver.dao.EventDao;
import com.positivity.poseventreceiver.internal.entity.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Initializes all event types for the pos-event-receiver module on application
 * startup.
 *
 * <p>
 * ⚠️ CRITICAL: This initializer uses DIRECT DAO access, NOT the REST API! ⚠️
 * </p>
 *
 * <p>
 * Unlike other modules that call the pos-event-receiver REST API to register
 * their event types, this module registers directly via the DAO to avoid
 * circular dependency issues:
 * </p>
 * <ul>
 * <li>Other modules → REST API → pos-event-receiver (correct)</li>
 * <li>pos-event-receiver → REST API → pos-event-receiver (CIRCULAR -
 * FORBIDDEN)</li>
 * <li>pos-event-receiver → EventDao directly (correct - what we do here)</li>
 * </ul>
 */
@Component
public class EventTypeInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EventTypeInitializer.class);

    private final EventDao eventDao;
    private final EventTypeInitializerSupport initializerSupport;

    public EventTypeInitializer(EventDao eventDao) {
        this.eventDao = eventDao;
        this.initializerSupport = new EventTypeInitializerSupport("pos-event-receiver");
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info(
                "Registering {} event-receiver event types (direct DAO access)",
                EventTypes.all().size());

        initializerSupport.registerEventTypes(EventTypes.all(), this::registerEventTypeDirectly);

        log.info("Event-receiver event type registration complete");
    }

    /**
     * Registers an event type directly via the DAO, bypassing the REST API.
     *
     * <p>
     * This is intentionally different from other modules which use REST API calls.
     * The pos-event-receiver MUST NOT call its own REST API to avoid circular
     * dependencies.
     * </p>
     */
    private void registerEventTypeDirectly(EventTypeRegistration registration) {
        try {
            // Check if event type already exists
            var existing = eventDao.getEventTypeByCode(registration.getTypeCode());

            EventType eventType;
            if (existing.isPresent()) {
                // Update existing event type
                eventType = existing.get();
                eventType.setDescription(registration.getDescription());
                eventType.setApiVersion(registration.getApiVersion());
                eventType.setActive(registration.isActive());
                if (registration.getP50Micros() != null) {
                    eventType.setP50Micros(registration.getP50Micros());
                }
                if (registration.getP95Micros() != null) {
                    eventType.setP95Micros(registration.getP95Micros());
                }
                if (registration.getP99Micros() != null) {
                    eventType.setP99Micros(registration.getP99Micros());
                }
            } else {
                // Create new event type
                eventType = new EventType(
                        registration.getTypeCode(),
                        registration.getDescription(),
                        registration.getApiVersion(),
                        registration.getP50Micros() != null
                                ? registration.getP50Micros()
                                : EventType.DEFAULT_THRESHOLD_MICROS,
                        registration.getP95Micros() != null
                                ? registration.getP95Micros()
                                : EventType.DEFAULT_THRESHOLD_MICROS,
                        registration.getP99Micros() != null
                                ? registration.getP99Micros()
                                : EventType.DEFAULT_THRESHOLD_MICROS);
            }

            eventDao.saveEventType(eventType);
            log.debug("Registered event type (direct): {}", registration.getTypeCode());
        } catch (Exception e) {
            log.warn("Failed to register event type {}: {}", registration.getTypeCode(), e.getMessage());
        }
    }
}

package com.positivity.poseventreceiver.internal.config;

import com.positivity.events.EventTypeInitializerSupport;
import com.positivity.events.EventTypeRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Initializes all event types for the pos-event-receiver module on application
 * startup.
 * Uses the pos-events upsert endpoint for idempotent registration.
 *
 * <p>
 * This initializer registers its own event types with itself (the event
 * receiver service),
 * which is intentionally designed to support self-registration for bootstrap
 * purposes.
 * </p>
 */
@Component
public class EventReceiverEventTypeInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EventReceiverEventTypeInitializer.class);

    private final RestClient restClient;
    private final EventTypeInitializerSupport initializerSupport;

    public EventReceiverEventTypeInitializer(
            RestClient.Builder restClientBuilder,
            @Value("${pos.events.base-url:http://localhost:8085}") String eventServiceBaseUrl) {
        this.restClient = restClientBuilder
                .baseUrl(eventServiceBaseUrl + "/v1/eventTypes/code")
                .build();
        this.initializerSupport = new EventTypeInitializerSupport("pos-event-receiver");
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Registering {} event-receiver event types", EventReceiverEventTypes.all().size());

        initializerSupport.registerEventTypes(
                EventReceiverEventTypes.all(),
                this::registerEventType);

        log.info("Event-receiver event type registration complete");
    }

    private void registerEventType(EventTypeRegistration registration) {
        try {
            restClient.put()
                    .uri("/{typeCode}", registration.getTypeCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(registration)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Registered event type: {}", registration.getTypeCode());
        } catch (Exception e) {
            log.warn("Failed to register event type {}: {}", registration.getTypeCode(), e.getMessage());
        }
    }
}

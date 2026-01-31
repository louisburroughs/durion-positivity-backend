package com.positivity.catalog.internal.config;

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
 * Initializes all event types for the pos-catalog module on application
 * startup.
 */
@Component
public class CatalogEventTypeInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogEventTypeInitializer.class);

    private final RestClient restClient;
    private final EventTypeInitializerSupport initializerSupport;

    public CatalogEventTypeInitializer(
            RestClient.Builder restClientBuilder,
            @Value("${pos.events.base-url:http://localhost:8085}") String eventServiceBaseUrl) {
        this.restClient = restClientBuilder
                .baseUrl(eventServiceBaseUrl + "/v1/eventTypes/code")
                .build();
        this.initializerSupport = new EventTypeInitializerSupport("pos-catalog");
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Registering {} catalog event types", CatalogEventTypes.all().size());

        initializerSupport.registerEventTypes(
                CatalogEventTypes.all(),
                this::registerEventType);

        log.info("Catalog event type registration complete");
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

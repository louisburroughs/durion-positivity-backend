package com.positivity.accounting.internal.config;

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
 * Initializes all event types for the pos-accounting module on application
 * startup.
 * Uses the pos-events upsert endpoint for idempotent registration.
 */
@Component
public class AccountingEventTypeInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountingEventTypeInitializer.class);

    private final RestClient restClient;
    private final EventTypeInitializerSupport initializerSupport;

    public AccountingEventTypeInitializer(
            RestClient.Builder restClientBuilder,
            @Value("${pos.events.base-url:http://localhost:8085}") String eventServiceBaseUrl) {
        this.restClient = restClientBuilder
                .baseUrl(eventServiceBaseUrl + "/v1/eventTypes/code")
                .build();
        this.initializerSupport = new EventTypeInitializerSupport("pos-accounting");
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Registering {} accounting event types", AccountingEventTypes.all().size());

        initializerSupport.registerEventTypes(
                AccountingEventTypes.all(),
                this::registerEventType);

        log.info("Accounting event type registration complete");
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

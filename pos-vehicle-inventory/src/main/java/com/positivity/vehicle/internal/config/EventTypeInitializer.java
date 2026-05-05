package com.positivity.vehicle.internal.config;

import com.positivity.events.EventTypeInitializerSupport;
import com.positivity.events.EventTypeRegistration;
import com.positivity.events.EventsApiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Registers vehicle event types with the event-receiver service on startup.
 */
@Component
public class EventTypeInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EventTypeInitializer.class);
    private static final String SERVICE_NAME = "pos-vehicle-inventory";

    private final RestClient restClient;
    private final EventTypeInitializerSupport initializerSupport;
    private final String apiSecret;

    public EventTypeInitializer(
            RestClient.Builder restClientBuilder,
            @Value("${pos.events.base-url:http://pos-event-receiver:8080}") String eventServiceBaseUrl,
            @Value("${pos.events.api-secret:}") String apiSecret) {
        this.restClient = restClientBuilder
                .baseUrl(eventServiceBaseUrl + "/v1/eventTypes/code")
                .build();
        this.initializerSupport = new EventTypeInitializerSupport(SERVICE_NAME);
        this.apiSecret = apiSecret;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual()
                .name("vehicle-event-type-init")
                .start(() -> initializerSupport.registerEventTypes(EventTypes.all(), this::registerViaHttp));
    }

    private void registerViaHttp(EventTypeRegistration registration) {
        try {
            var request = restClient
                    .put()
                    .uri("/{typeCode}", registration.getTypeCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(registration);

            if (EventsApiConstants.hasSecret(apiSecret)) {
                request.header(EventsApiConstants.SECRET_HEADER, apiSecret);
            }

            request.retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to register event type '{}': {}", registration.getTypeCode(), e.getMessage());
        }
    }
}

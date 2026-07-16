package com.positivity.warranty.internal.config;

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

@Component
public class WarrantyEventTypeInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WarrantyEventTypeInitializer.class);

    private final RestClient restClient;
    private final EventTypeInitializerSupport initializerSupport;
    private final String apiSecret;

    public WarrantyEventTypeInitializer(
            RestClient.Builder restClientBuilder,
            @Value("${pos.events.base-url:http://pos-event-receiver:8080}") String eventServiceBaseUrl,
            @Value("${pos.events.api-secret:}") String apiSecret) {
        this.restClient = restClientBuilder
                .baseUrl(eventServiceBaseUrl + "/v1/eventTypes/code")
                .build();
        this.initializerSupport = new EventTypeInitializerSupport("pos-warranty");
        this.apiSecret = apiSecret;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Registering {} warranty event types", WarrantyEventTypes.all().size());
        initializerSupport.registerEventTypes(WarrantyEventTypes.all(), this::registerEventType);
    }

    private void registerEventType(EventTypeRegistration registration) {
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
        } catch (Exception ex) {
            log.warn("Failed to register warranty event type {}: {}", registration.getTypeCode(), ex.getMessage());
        }
    }
}

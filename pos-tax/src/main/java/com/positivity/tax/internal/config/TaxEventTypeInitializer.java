package com.positivity.tax.internal.config;

import com.positivity.events.EventsApiConstants;
import com.positivity.events.EventTypeInitializerSupport;
import com.positivity.events.EventTypeRegistration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Initializes event types for pos-tax module at application startup.
 * <p>
 * Registers all tax service events with the pos-events service.
 */
@Component
public class TaxEventTypeInitializer implements ApplicationRunner {
    
    private final RestClient restClient;
    private final EventTypeInitializerSupport initializerSupport;
    private final String apiSecret;

    public TaxEventTypeInitializer(
        RestClient.Builder restClientBuilder,
        @Value("${pos.events.base-url:http://localhost:8085}") String eventServiceBaseUrl,
        @Value("${pos.events.api-secret:}") String apiSecret
    ) {
        this.restClient = restClientBuilder
            .baseUrl(eventServiceBaseUrl + "/v1/eventTypes/code")
            .build();
        this.initializerSupport = new EventTypeInitializerSupport("pos-tax");
        this.apiSecret = apiSecret;
    }

    @Override
    public void run(ApplicationArguments args) {
        initializerSupport.registerEventTypes(TaxEventTypes.all(), this::registerEventType);
    }

    private void registerEventType(EventTypeRegistration registration) {
        try {
            var request = restClient.put()
                .uri("/{typeCode}", registration.getTypeCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(registration);

            // Add shared secret header for authentication (avoids JWT circular dependency)
            if (EventsApiConstants.hasSecret(apiSecret)) {
                request.header(EventsApiConstants.SECRET_HEADER, apiSecret);
            }

            request.retrieve().toBodilessEntity();
        } catch (Exception e) {
            // Log warning but don't fail startup
            // EventTypeInitializerSupport already logs warnings
        }
    }
}

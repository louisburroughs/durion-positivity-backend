package com.positivity.tenant.internal.config;

import com.positivity.events.EventTypeInitializerSupport;
import com.positivity.events.EventTypeRegistration;
import com.positivity.events.EventsApiConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Registers {@link TenantEventTypes} with pos-event-receiver at startup; failures never block boot. */
@Component
public class TenantEventTypeInitializer implements ApplicationRunner {

    private final RestClient restClient;
    private final EventTypeInitializerSupport initializerSupport;
    private final String apiSecret;

    public TenantEventTypeInitializer(
            RestClient.Builder restClientBuilder,
            @Value("${pos.events.base-url:http://pos-event-receiver:8080}") String eventServiceBaseUrl,
            @Value("${pos.events.api-secret:}") String apiSecret) {
        this.restClient = restClientBuilder
                .baseUrl(eventServiceBaseUrl + "/v1/eventTypes/code")
                .build();
        this.initializerSupport = new EventTypeInitializerSupport("pos-tenant");
        this.apiSecret = apiSecret;
    }

    @Override
    public void run(ApplicationArguments args) {
        initializerSupport.registerEventTypes(TenantEventTypes.all(), this::registerEventType);
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
        } catch (Exception e) {
            // EventTypeInitializerSupport already logs the warning; startup must not block.
        }
    }
}

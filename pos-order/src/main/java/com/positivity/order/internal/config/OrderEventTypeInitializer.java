package com.positivity.order.internal.config;

import com.positivity.events.EventTypeInitializerSupport;
import com.positivity.events.EventTypeRegistration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Initializer that registers Order module event types with the Event Receiver
 * service at startup.
 * 
 * Uses upsert semantics for idempotent registration - safe to restart the
 * service without
 * duplicating event types.
 */
@Component
@Slf4j
public class OrderEventTypeInitializer implements ApplicationRunner {

    private static final String SERVICE_NAME = "pos-order";

    private final RestClient restClient;
    private final EventTypeInitializerSupport initializerSupport;
    private final boolean enabled;

    public OrderEventTypeInitializer(
            RestClient.Builder restClientBuilder,
            @Value("${gateway.base-url:http://localhost:8080}") String gatewayBaseUrl,
            @Value("${pos-events.registration.enabled:true}") boolean enabled) {
        this.restClient = restClientBuilder
                .baseUrl(gatewayBaseUrl + "/api/event-receiver/v1/eventTypes/code")
                .build();
        this.initializerSupport = new EventTypeInitializerSupport(SERVICE_NAME);
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[{}] Event type registration is disabled", SERVICE_NAME);
            return;
        }

        try {
            initializerSupport.registerEventTypes(
                    OrderEventTypes.ALL_EVENT_TYPES,
                    this::registerViaHttp);
        } catch (Exception e) {
            log.warn("[{}] Failed to register event types at startup: {}. " +
                    "Events will still be emitted, but event types may need manual registration.",
                    SERVICE_NAME, e.getMessage());
        }
    }

    private void registerViaHttp(EventTypeRegistration registration) {
        restClient.put()
                .uri("/{typeCode}", registration.getTypeCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(registration)
                .retrieve()
                .toBodilessEntity();
    }
}

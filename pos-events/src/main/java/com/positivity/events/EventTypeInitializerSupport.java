package com.positivity.events;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Support class for registering event types with the Event Receiver service at
 * startup.
 *
 * <p>
 * This class is dependency-agnostic and uses a functional approach. The actual
 * HTTP
 * client implementation is provided by the consuming module via a
 * {@code Consumer}.
 *
 * <p>
 * Example usage with RestClient:
 *
 * <pre>
 * {
 *     &#64;code
 *     &#64;Component
 *     public class OrderEventTypeInitializer implements ApplicationRunner {
 *
 *         private final RestClient restClient;
 *         private final EventTypeInitializerSupport initializerSupport;
 *
 *         public OrderEventTypeInitializer(RestClient.Builder restClientBuilder,
 *                 &#64;Value("${gateway.base-url}") String gatewayBaseUrl) {
 *             this.restClient = restClientBuilder
 *                     .baseUrl(gatewayBaseUrl + "/v1/event-receiver/v1/eventTypes/code")
 *                     .build();
 *             this.initializerSupport = new EventTypeInitializerSupport("pos-order");
 *         }
 *
 *         @Override
 *         public void run(ApplicationArguments args) {
 *             initializerSupport.registerEventTypes(OrderEventTypes.ALL_EVENT_TYPES, this::registerViaHttp);
 *         }
 *
 *         private void registerViaHttp(EventTypeRegistration registration) {
 *             restClient.put()
 *                     .uri("/{typeCode}", registration.getTypeCode())
 *                     .contentType(MediaType.APPLICATION_JSON)
 *                     .body(registration)
 *                     .retrieve()
 *                     .toBodilessEntity();
 *         }
 *     }
 * }
 * </pre>
 */
public class EventTypeInitializerSupport {

    private static final Logger log = LoggerFactory.getLogger(EventTypeInitializerSupport.class);

    private final String serviceName;

    /**
     * Create a new initializer support instance.
     *
     * @param serviceName the name of the calling service (for logging)
     */
    public EventTypeInitializerSupport(@NonNull String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * Register a collection of event types using the provided registration
     * function.
     * Uses upsert semantics - creates if not exists, updates if exists.
     *
     * @param eventTypes           the event types to register
     * @param registrationFunction the function to call for each registration
     *                             (typically an HTTP PUT)
     * @return the number of successfully registered event types
     */
    public int registerEventTypes(
            @NonNull Collection<EventTypeRegistration> eventTypes,
            @NonNull Consumer<EventTypeRegistration> registrationFunction) {

        Objects.requireNonNull(eventTypes, "eventTypes cannot be null");
        Objects.requireNonNull(registrationFunction, "registrationFunction cannot be null");

        log.info("[{}] Registering {} event types with Event Receiver...", serviceName, eventTypes.size());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (EventTypeRegistration registration : eventTypes) {
            try {
                registrationFunction.accept(registration);
                successCount.incrementAndGet();
                log.debug("[{}] Successfully registered event type: {}", serviceName, registration.getTypeCode());
            } catch (Exception e) {
                failCount.incrementAndGet();
                log.warn("[{}] Failed to register event type '{}': {}",
                        serviceName, registration.getTypeCode(), e.getMessage());
            }
        }

        if (failCount.get() > 0) {
            log.warn("[{}] Event type registration completed: {} succeeded, {} failed",
                    serviceName, successCount.get(), failCount.get());
        } else {
            log.info("[{}] Successfully registered all {} event types", serviceName, successCount.get());
        }

        return successCount.get();
    }
}

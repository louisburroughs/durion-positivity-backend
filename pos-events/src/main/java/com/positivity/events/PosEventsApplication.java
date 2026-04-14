package com.positivity.events;

import com.positivity.events.service.EventEmissionService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the pos-events shared library.
 *
 * Enables automatic registration of event emission components when pos-events
 * is added as a dependency to consumer microservices.
 *
 * No manual configuration needed in consumer applications—just add pos-events
 * to the classpath and this auto-configuration will activate automatically.
 */
@AutoConfiguration
@RequiredArgsConstructor
public class PosEventsApplication {

    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    /**
     * Registers the core event emission service.
     *
     * @return configured service for event emission logic
     */
    @Bean
    public EventEmissionService eventEmissionService() {
        return new EventEmissionService(clock, publisher);
    }

    /**
     * Registers the event emission aspect for annotation-driven event tracking.
     *
     * @param eventEmissionService the event emission service
     * @return configured aspect for intercepting @EmitEvent methods
     */
    @Bean
    public EmitEventAspect emitEventAspect(EventEmissionService eventEmissionService) {
        return new EmitEventAspect(eventEmissionService);
    }

    /**
     * Registers the factory for creating proxy-based event emitters.
     *
     * @param eventEmissionService the event emission service
     * @return proxy factory for manual event emission in non-method-level contexts
     */
    @Bean
    public EmitEventProxyFactory emitEventProxyFactory(EventEmissionService eventEmissionService) {
        return new EmitEventProxyFactory(eventEmissionService);
    }
}

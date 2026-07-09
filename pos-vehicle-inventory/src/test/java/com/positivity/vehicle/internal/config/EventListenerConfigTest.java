package com.positivity.vehicle.internal.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

class EventListenerConfigTest {

    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    private final ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);

    private final EventListenerConfig config = new EventListenerConfig();

    @Test
    @DisplayName("Starts the Kafka listener registry once the application is ready")
    void startsListeners() {
        when(event.getApplicationContext()).thenReturn(context);
        when(context.getBean(KafkaListenerEndpointRegistry.class)).thenReturn(registry);

        config.startKafkaListeners(event);

        verify(registry).start();
    }

    @Test
    @DisplayName("Broker failure while starting listeners must not kill the service")
    void brokerFailureDoesNotPropagate() {
        when(event.getApplicationContext()).thenReturn(context);
        when(context.getBean(KafkaListenerEndpointRegistry.class)).thenReturn(registry);
        // Same failure mode as an unresolvable bootstrap server (deploy without a broker).
        doThrow(new KafkaException("Failed to construct kafka consumer"))
                .when(registry)
                .start();

        assertThatCode(() -> config.startKafkaListeners(event)).doesNotThrowAnyException();
    }
}

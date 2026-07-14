package com.positivity.vehicle.internal.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.mock.env.MockEnvironment;

class EventListenerConfigTest {

    @Test
    void skipsStartingListenersWhenAutoStartupIsDisabled() {
        EventListenerConfig config = new EventListenerConfig();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.kafka.listener.auto-startup", "false");

        ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);
        ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);

        when(event.getApplicationContext()).thenReturn(applicationContext);
        when(applicationContext.getBean(KafkaListenerEndpointRegistry.class)).thenReturn(registry);

        config.startKafkaListeners(event, environment);

        verify(registry, never()).start();
    }
}

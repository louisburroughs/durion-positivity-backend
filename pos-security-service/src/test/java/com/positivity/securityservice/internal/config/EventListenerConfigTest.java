package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

@DisplayName("EventListenerConfig starts the Kafka listeners only when a registry exists")
class EventListenerConfigTest {

    private final EventListenerConfig config = new EventListenerConfig();
    private final GenericApplicationContext context = new GenericApplicationContext();

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    @DisplayName("no registry (Kafka auto-configuration excluded): nothing to start, no failure")
    void noRegistryIsANoOp() {
        context.refresh();
        assertThatCode(() -> config.startKafkaListeners(readyEvent())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a registry in the context is started once the application is ready")
    void registryIsStarted() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        context.registerBean(KafkaListenerEndpointRegistry.class, () -> registry);
        context.refresh();

        config.startKafkaListeners(readyEvent());

        verify(registry).start();
    }

    @Test
    @DisplayName("an unreachable broker is logged, not fatal")
    void kafkaFailureIsSwallowed() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        doThrow(new KafkaException("broker unreachable")).when(registry).start();
        context.registerBean(KafkaListenerEndpointRegistry.class, () -> registry);
        context.refresh();

        assertThatCode(() -> config.startKafkaListeners(readyEvent())).doesNotThrowAnyException();
        verify(registry).start();
    }

    private ApplicationReadyEvent readyEvent() {
        return new ApplicationReadyEvent(mock(SpringApplication.class), new String[0], context, Duration.ZERO);
    }
}

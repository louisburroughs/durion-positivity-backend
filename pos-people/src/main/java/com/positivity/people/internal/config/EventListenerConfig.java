package com.positivity.people.internal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * Kafka listener lifecycle for this module.
 *
 * <p>Listeners are declared with {@code autoStartup = "false"} so consumer-group initialization
 * does not block the main startup sequence; containers are started once the application context
 * is fully ready.
 *
 * <p>This module's inbound surface is {@code people.commands.v1}
 * ({@link PeopleCommandListener}).
 */
@Slf4j
@Configuration
public class EventListenerConfig {

    @EventListener(ApplicationReadyEvent.class)
    public void startKafkaListeners(ApplicationReadyEvent event) {
        KafkaListenerEndpointRegistry registry =
                event.getApplicationContext().getBean(KafkaListenerEndpointRegistry.class);
        try {
            registry.start();
            log.info("Kafka listeners started after application ready");
        } catch (org.apache.kafka.common.KafkaException | org.springframework.kafka.KafkaException e) {
            // An unresolvable/unavailable broker (e.g. bootstrap servers pointing at a Kafka
            // container that is not deployed yet) must not kill the service — the REST API works
            // without event ingestion. Consumer construction fails fast on unresolvable DNS, so
            // this is reachable, not just theoretical. Narrowed to Kafka exceptions so genuine
            // listener misconfiguration still fails startup loudly.
            log.error(
                    "Kafka listeners could not be started; continuing without event ingestion. "
                            + "Check spring.kafka.bootstrap-servers / broker availability.",
                    e);
        }
    }
}

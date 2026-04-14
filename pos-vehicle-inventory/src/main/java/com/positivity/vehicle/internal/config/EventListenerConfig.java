package com.positivity.vehicle.internal.config;

import com.positivity.vehicle.internal.dto.VehicleUpdatedEvent;
import com.positivity.vehicle.service.VehicleEventIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Message listener configuration for vehicle events (CAP:091 Story #101).
 * Configures Kafka listeners for VehicleUpdated events.
 *
 * <p>
 * Kafka listeners use {@code autoStartup = "false"} so that consumer group
 * initialization does not block the main startup sequence. Containers are
 * started once the application context is fully ready.
 * </p>
 */
@Slf4j
@Configuration
public class EventListenerConfig {

    @Bean
    public VehicleEventListener vehicleEventListener(
            VehicleEventIngestionService ingestionService, ObjectMapper objectMapper) {
        return new VehicleEventListener(ingestionService, objectMapper);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startKafkaListeners(ApplicationReadyEvent event) {
        KafkaListenerEndpointRegistry registry =
                event.getApplicationContext().getBean(KafkaListenerEndpointRegistry.class);
        registry.start();
        log.info("Kafka listeners started after application ready");
    }

    /**
     * Kafka event listener for VehicleUpdated events.
     */
    @Slf4j
    @Component
    @RequiredArgsConstructor
    public static class VehicleEventListener {

        private final VehicleEventIngestionService ingestionService;
        private final ObjectMapper objectMapper;

        /**
         * Listen for VehicleUpdated events from vehicle.updates topic.
         * Deserializes JSON payload and routes to ingestion service.
         *
         * @param payload JSON event payload from message broker
         */
        @KafkaListener(
                topics = "vehicle.updates",
                groupId = "pos-vehicle-inventory-group",
                containerFactory = "kafkaListenerContainerFactory",
                autoStartup = "false")
        public void onVehicleUpdated(String payload) {
            try {
                log.debug("Received vehicle update event: {}", payload);
                VehicleUpdatedEvent event = objectMapper.readValue(payload, VehicleUpdatedEvent.class);
                ingestionService.processVehicleUpdateEvent(event);
            } catch (Exception e) {
                log.error("Error processing vehicle update event: {}", payload, e);
                // Event remains in dead-letter queue for manual review
            }
        }

        /**
         * Listen for WorkorderCompleted events and extract vehicle updates.
         * Triggered when workorder completion includes vehicle mileage/metadata.
         *
         * @param payload JSON event payload
         */
        @KafkaListener(
                topics = "workorder.completed",
                groupId = "pos-vehicle-inventory-group",
                containerFactory = "kafkaListenerContainerFactory",
                autoStartup = "false")
        public void onWorkorderCompleted(String payload) {
            try {
                log.debug("Received workorder completion event: {}", payload);
                // Extract vehicle update from workorder completion event
                // (Implementation depends on workorder event schema)
                // For now, this is a placeholder for future integration
                log.info("Workorder completion event received (vehicle extraction pending)");
            } catch (Exception e) {
                log.error("Error processing workorder completion event: {}", payload, e);
            }
        }
    }
}

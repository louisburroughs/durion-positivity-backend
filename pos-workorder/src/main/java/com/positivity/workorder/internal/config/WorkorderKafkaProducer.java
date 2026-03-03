package com.positivity.workorder.internal.config;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka producer for workorder domain events.
 *
 * <p>
 * Messages are emitted as JSON envelopes to support uniform consumer parsing
 * across domains.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class WorkorderKafkaProducer {

    private static final String SOURCE_SERVICE = "pos-workorder";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${workorder.kafka.events-topic:workorder.events.v1}")
    private String eventsTopic;

    public void publish(@NonNull String eventType, @NonNull String key, @NonNull Object payload) {
        String message = serializeEnvelope(eventType, payload);
        kafkaTemplate.send(eventsTopic, key, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish Kafka event type={} topic={} key={}", eventType, eventsTopic, key,
                                ex);
                        return;
                    }
                    log.debug("Published Kafka event type={} topic={} key={} partition={} offset={}",
                            eventType,
                            eventsTopic,
                            key,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                });
    }

    private String serializeEnvelope(String eventType, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("occurredAtUtc", Instant.now());
        envelope.put("sourceService", SOURCE_SERVICE);
        envelope.put("payload", payload);

        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize Kafka event envelope for type: " + eventType, e);
        }
    }
}

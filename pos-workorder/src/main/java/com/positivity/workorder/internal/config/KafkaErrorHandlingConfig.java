package com.positivity.workorder.internal.config;

import com.positivity.kafka.common.KafkaRails;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Retry and dead-letter handling for the module's Kafka listeners (ADR-0044 §4, #2178).
 *
 * <p>Failed records are retried with exponential backoff; when retries are exhausted the record is
 * published to {@code {topic}.dlq} so poison messages surface for alerting instead of silently
 * blocking or dropping. Without this bean Spring Kafka's default handler retries a propagated
 * failure a few times back to back and then logs and skips the record, so a
 * failure that a listener rethrows on purpose (the {@code RetryableConsumerFailures} set, #2355)
 * would still be lost.
 *
 * <p>Redelivery is safe for every listener here: the fact listeners guard on
 * {@code processed_events}, invoice generation is idempotent per workorder, replay and backfill
 * commands re-queue by window, an assignment update is a last-write-wins projection, and a
 * reconciliation manifest re-runs a read-only comparison whose replay request is keyed by window
 * (a failed replay request propagates for exactly this redelivery, #2419).
 *
 * <p>Spring Boot wires a single {@code CommonErrorHandler} bean into the auto-configured listener
 * container factory, so declaring the bean is sufficient.
 */
@Slf4j
@Configuration
@KafkaRails
public class KafkaErrorHandlingConfig {

    /** Route to {@code {topic}.dlq}; partition -1 lets the producer choose. */
    static final BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> DLQ_DESTINATION =
            (record, ex) -> new TopicPartition(record.topic() + ".dlq", -1);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(@SuppressWarnings("rawtypes") KafkaTemplate kafkaTemplate) {
        @SuppressWarnings("unchecked")
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate, DLQ_DESTINATION);

        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxAttempts(5);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setRetryListeners((record, ex, attempt) -> log.warn(
                "Kafka record processing failed topic={} partition={} offset={} attempt={}",
                record.topic(),
                record.partition(),
                record.offset(),
                attempt,
                ex));
        return handler;
    }
}

package com.positivity.invoice.internal.config;

import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Retry and dead-letter handling for the module's Kafka listeners (ADR-0044 §4).
 *
 * <p>Failed records are retried with exponential backoff; when retries are exhausted the record is
 * published to {@code {topic}.dlq} so poison messages surface for alerting instead of silently
 * blocking or dropping. The listeners rethrow the retryable failure set
 * ({@code RetryableConsumerFailures}) so the container retries here. Redelivery is safe on two
 * paths: the fact listeners guard with a processed-event marker, while the four manifest listeners
 * record no marker; their retry is safe because the manifest comparison is a read and the replay
 * command is keyed by window start, so a repeat asks the owner for the same idempotent replay.
 *
 * <p>Spring Boot wires a single {@code CommonErrorHandler} bean into the auto-configured listener
 * container factory, so declaring the bean is sufficient.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "pos.invoice.kafka", name = "enabled", havingValue = "true")
public class KafkaErrorHandlingConfig {

    /** Route to {@code {topic}.dlq}; partition -1 lets the producer choose. */
    static final BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> DLQ_DESTINATION =
            (record, ex) -> new TopicPartition(record.topic() + ".dlq", -1);

    /** Retry attempts before a record is dead-lettered. */
    static final int MAX_ATTEMPTS = 5;

    /** Production backoff: 1s, doubling, capped at 30s, {@link #MAX_ATTEMPTS} retries. */
    static ExponentialBackOff backOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxAttempts(MAX_ATTEMPTS);
        return backOff;
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(@SuppressWarnings("rawtypes") KafkaTemplate kafkaTemplate) {
        @SuppressWarnings("unchecked")
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate, DLQ_DESTINATION);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff());
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

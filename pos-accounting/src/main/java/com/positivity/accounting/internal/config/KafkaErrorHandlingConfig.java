package com.positivity.accounting.internal.config;

import com.positivity.kafka.common.KafkaRails;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Retry and dead-letter handling for the module's Kafka listeners (ADR-0044 §4).
 *
 * <p>Failed records are retried with exponential backoff; when retries are exhausted the record is
 * published to {@code {topic}.dlq} so poison messages surface for alerting instead of silently
 * blocking or dropping. Idempotency (the {@code processed_events} unique {@code event_id} guard in
 * {@code CustomerEventsListener} and {@code InvoiceEventsListener}) makes the retries harmless.
 *
 * <p>Spring Boot wires a single {@code CommonErrorHandler} bean into the auto-configured listener
 * container factory, so declaring the bean is sufficient.
 */
@Configuration
@KafkaRails
public class KafkaErrorHandlingConfig {

    /** Route to {@code {topic}.dlq}; partition -1 lets the producer choose. */
    static final BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> DLQ_DESTINATION =
            (record, ex) -> new TopicPartition(record.topic() + ".dlq", -1);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            @SuppressWarnings("rawtypes") KafkaTemplate kafkaTemplate, ObjectProvider<MeterRegistry> meterRegistry) {
        @SuppressWarnings("unchecked")
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafkaTemplate, DLQ_DESTINATION);
        KafkaFailureRecorder failures = new KafkaFailureRecorder(meterRegistry.getIfAvailable());
        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            failures.deadLettered(record, ex);
            publisher.accept(record, ex);
        };

        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxAttempts(5);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Logged and counted by reason (#2558): an unset accounting time zone is ACCOUNTING_TIME_ZONE_UNSET.
        handler.setRetryListeners(failures::failed);
        return handler;
    }
}

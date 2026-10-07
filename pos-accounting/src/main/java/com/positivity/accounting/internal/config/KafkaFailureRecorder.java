package com.positivity.accounting.internal.config;

import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Logs and counts the module's failed Kafka records by reason (#2558, Accounting Domain ruling on the unset zone):
 * every failed attempt increments {@value #FAILED_COUNTER} and every record sent to its {@code .dlq} increments
 * {@value #DEAD_LETTERED_COUNTER}, both tagged {@code reason} and {@code topic}. A posting that failed because the
 * tenant has no accounting time zone carries the reason {@value #TIME_ZONE_UNSET}; everything else is {@value
 * #OTHER}, so the tag stays low-cardinality. The alert on these counters is operations configuration.
 */
@Slf4j
public class KafkaFailureRecorder {

    static final String FAILED_COUNTER = "accounting.kafka.record.failed";
    static final String DEAD_LETTERED_COUNTER = "accounting.kafka.record.dead_lettered";
    static final String TIME_ZONE_UNSET = "ACCOUNTING_TIME_ZONE_UNSET";
    static final String OTHER = "OTHER";

    private final @Nullable MeterRegistry meterRegistry;

    public KafkaFailureRecorder(@Nullable MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** The reason code of a listener failure: {@value #TIME_ZONE_UNSET} when any cause is the unset zone. */
    static @NonNull String reasonOf(@Nullable Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AccountingTimeZoneUnsetException) {
                return TIME_ZONE_UNSET;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return OTHER;
    }

    /** One failed attempt at a record; the container retries it. */
    public void failed(@NonNull ConsumerRecord<?, ?> record, @Nullable Exception failure, int attempt) {
        String reason = reasonOf(failure);
        log.warn(
                "Kafka record processing failed reason={} topic={} partition={} offset={} attempt={}",
                reason,
                record.topic(),
                record.partition(),
                record.offset(),
                attempt,
                failure);
        count(FAILED_COUNTER, reason, record.topic());
    }

    /** A record whose retries are exhausted, now published to its {@code .dlq}. */
    public void deadLettered(@NonNull ConsumerRecord<?, ?> record, @Nullable Exception failure) {
        String reason = reasonOf(failure);
        log.error(
                "Kafka record sent to the dead-letter topic reason={} topic={} partition={} offset={}",
                reason,
                record.topic(),
                record.partition(),
                record.offset(),
                failure);
        count(DEAD_LETTERED_COUNTER, reason, record.topic());
    }

    private void count(String name, String reason, String topic) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(name)
                .description("Failed Kafka records of pos-accounting, by reason (#2558)")
                .tag("reason", reason)
                .tag("topic", topic)
                .register(meterRegistry)
                .increment();
    }
}

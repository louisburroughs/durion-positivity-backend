package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #2558 ruling condition 1: an unset-zone listener failure is logged and counted with its own reason. */
@DisplayName("KafkaFailureRecorder: failures counted by reason (#2558)")
class KafkaFailureRecorderTest {

    private static final ConsumerRecord<String, String> RECORD =
            new ConsumerRecord<>("inventory.events.v1", 0, 42L, "key", "{}");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final KafkaFailureRecorder recorder = new KafkaFailureRecorder(meters);

    private double count(String name, String reason) {
        var counter = meters.find(name)
                .tag("reason", reason)
                .tag("topic", "inventory.events.v1")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("an unset zone, wrapped as the listener rethrows it, is ACCOUNTING_TIME_ZONE_UNSET on each attempt and"
            + " at the DLQ")
    void unsetZoneIsCountedByItsReason() {
        Exception wrapped = new IllegalStateException("posting failed", new AccountingTimeZoneUnsetException());

        recorder.failed(RECORD, wrapped, 1);
        recorder.failed(RECORD, wrapped, 2);
        recorder.deadLettered(RECORD, wrapped);

        assertThat(count(KafkaFailureRecorder.FAILED_COUNTER, "ACCOUNTING_TIME_ZONE_UNSET"))
                .isEqualTo(2);
        assertThat(count(KafkaFailureRecorder.DEAD_LETTERED_COUNTER, "ACCOUNTING_TIME_ZONE_UNSET"))
                .isEqualTo(1);
        assertThat(count(KafkaFailureRecorder.FAILED_COUNTER, "OTHER")).isZero();
    }

    @Test
    @DisplayName("any other failure is OTHER")
    void otherFailuresAreOther() {
        recorder.failed(RECORD, new IllegalStateException("boom"), 1);

        assertThat(count(KafkaFailureRecorder.FAILED_COUNTER, "OTHER")).isEqualTo(1);
        assertThat(KafkaFailureRecorder.reasonOf(null)).isEqualTo("OTHER");
    }

    @Test
    @DisplayName("without a meter registry it only logs")
    void noRegistry() {
        new KafkaFailureRecorder(null).failed(RECORD, new AccountingTimeZoneUnsetException(), 1);
    }

    @Test
    @DisplayName("a cause cycle (A -> B -> A) ends at the depth cap instead of looping")
    void causeCycleTerminates() {
        IllegalStateException a = new IllegalStateException("a");
        IllegalStateException b = new IllegalStateException("b", a);
        a.initCause(b);

        assertThat(KafkaFailureRecorder.reasonOf(a)).isEqualTo("OTHER");
    }
}

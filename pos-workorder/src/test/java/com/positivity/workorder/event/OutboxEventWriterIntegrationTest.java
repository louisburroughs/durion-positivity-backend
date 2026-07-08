package com.positivity.workorder.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.positivity.workorder.internal.config.OutboxEventWriter;
import com.positivity.workorder.internal.entity.OutboxEvent;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the ADR-0044 outbox guarantee: an event row exists if and only if the business
 * transaction commits, and writing outside a transaction fails fast (MANDATORY propagation).
 */
@DataJpaTest(properties = {"workorder.kafka.enabled=true", "spring.flyway.enabled=false"})
@Import(OutboxEventWriter.class)
class OutboxEventWriterIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-07-08T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired
    private OutboxEventWriter writer;

    @Autowired
    private OutboxEventRepository repository;

    @Test
    @DisplayName("Outbox row is persisted when the business transaction commits")
    void persistsRowOnCommit() {
        writer.publish("workorder.work_session.started.v1", "wo-key-1", Map.of("workorderId", "wo-1"));
        TestTransaction.flagForCommit();
        TestTransaction.end();

        List<OutboxEvent> rows = repository.findTop100ByPublishedAtIsNullOrderByIdAsc();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.getFirst();
        assertThat(row.getTopic()).isEqualTo("workorder.events.v1");
        assertThat(row.getRecordKey()).isEqualTo("wo-key-1");
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getCreatedAt()).isEqualTo(Instant.parse("2026-07-08T12:00:00Z"));
        // Wire format unchanged from the previous direct producer (pos-customer consumer contract).
        assertThat(row.getPayload())
                .contains("\"eventId\"")
                .contains("\"eventType\":\"workorder.work_session.started.v1\"")
                .contains("\"occurredAtUtc\"")
                .contains("\"sourceService\":\"pos-workorder\"")
                .contains("\"payload\"");

        repository.deleteAll();
    }

    @Test
    @DisplayName("No outbox row survives when the business transaction rolls back")
    void discardsRowOnRollback() {
        writer.publish("workorder.work_session.stopped.v1", "wo-key-2", Map.of("workorderId", "wo-2"));
        TestTransaction.flagForRollback();
        TestTransaction.end();

        assertThat(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).isEmpty();
    }

    @Test
    @DisplayName("Writing outside a transaction fails fast (MANDATORY propagation)")
    void rejectsPublishOutsideTransaction() {
        TestTransaction.end();

        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> writer.publish("workorder.time_entry.approved.v1", "wo-key-3", Map.of()));
    }
}

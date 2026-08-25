package com.positivity.workorder.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.positivity.workorder.internal.config.OutboxEventWriter;
import com.positivity.workorder.internal.domain.TimeEntryApprovedEvent;
import com.positivity.workorder.internal.domain.WorkSessionStartedEvent;
import com.positivity.workorder.internal.domain.WorkSessionStoppedEvent;
import com.positivity.workorder.internal.entity.OutboxEvent;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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

    private static final Instant EVENT_TIME = Instant.parse("2026-07-08T12:00:00Z");
    private static final UUID SAMPLE_ID = UUID.fromString("01980001-0000-7000-8000-000000000001");
    private static final UUID MECHANIC_ID = UUID.fromString("01980001-0000-7000-8000-000000000002");
    private static final UUID WORKORDER_ID = UUID.fromString("01980001-0000-7000-8000-000000000003");

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
        writer.publish(
                "workorder.work-session.started.v1",
                WorkSessionStartedEvent.SCHEMA_VERSION,
                SAMPLE_ID,
                workSessionStarted());
        TestTransaction.flagForCommit();
        TestTransaction.end();

        List<OutboxEvent> rows = repository.findTop100ByPublishedAtIsNullOrderByIdAsc();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.getFirst();
        assertThat(row.getTopic()).isEqualTo("workorder.events.v1");
        assertThat(row.getRecordKey()).isEqualTo(SAMPLE_ID.toString());
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getCreatedAt()).isEqualTo(Instant.parse("2026-07-08T12:00:00Z"));
        // Now a serialized DomainEventEnvelope rather than the hand-built map (#1286). Every field
        // the map emitted is still emitted under the same name — that is the pos-customer consumer
        // contract, and those listeners read the envelope as a JsonNode tree by field name — and
        // the envelope adds schemaVersion, aggregateId, correlationId and actor on top.
        assertThat(row.getPayload())
                .contains("\"eventId\"")
                .contains("\"eventType\":\"workorder.work-session.started.v1\"")
                .contains("\"schemaVersion\":1")
                .contains("\"aggregateId\":\"" + SAMPLE_ID + "\"")
                .contains("\"aggregateVersion\"")
                .contains("\"occurredAtUtc\"")
                .contains("\"sourceService\":\"pos-workorder\"")
                .contains("\"payload\"");

        repository.deleteAll();
    }

    @Test
    @DisplayName("The explicit-version overload stamps the given aggregateVersion, not emission millis")
    void explicitVersionOverloadStampsGivenVersion() {
        // #1486: WorkorderUpdatedV1/EstimateUpdatedV1 are version-guarded and must carry the
        // entity's flushed @Version rather than the emission-timestamp millis the four-argument
        // overload stamps.
        writer.publish(
                "workorder.work-session.started.v1",
                WorkSessionStartedEvent.SCHEMA_VERSION,
                SAMPLE_ID,
                7L,
                workSessionStarted());
        TestTransaction.flagForCommit();
        TestTransaction.end();

        List<OutboxEvent> rows = repository.findTop100ByPublishedAtIsNullOrderByIdAsc();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getPayload()).contains("\"aggregateVersion\":7");

        repository.deleteAll();
    }

    @Test
    @DisplayName("No outbox row survives when the business transaction rolls back")
    void discardsRowOnRollback() {
        writer.publish(
                "workorder.work-session.stopped.v1",
                WorkSessionStoppedEvent.SCHEMA_VERSION,
                SAMPLE_ID,
                new WorkSessionStoppedEvent(SAMPLE_ID, MECHANIC_ID, EVENT_TIME, 3600));
        TestTransaction.flagForRollback();
        TestTransaction.end();

        assertThat(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).isEmpty();
    }

    @Test
    @DisplayName("A schemaVersion below 1 is rejected rather than published")
    void rejectsNonPositiveSchemaVersion() {
        // The guard is DomainEventEnvelope's own, reached through the writer. Pinning it here is
        // what proves the writer actually routes through the shared envelope: while this module
        // hand-built its map (#1286) nothing validated the version at all, and a 0 would have
        // reached consumers looking legitimate.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () -> writer.publish("workorder.work-session.started.v1", 0, SAMPLE_ID, workSessionStarted()))
                .withMessageContaining("schemaVersion must be >= 1");
    }

    @Test
    @DisplayName("Writing outside a transaction fails fast (MANDATORY propagation)")
    void rejectsPublishOutsideTransaction() {
        TestTransaction.end();

        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> writer.publish(
                        "workorder.time-entry.approved.v1",
                        TimeEntryApprovedEvent.SCHEMA_VERSION,
                        SAMPLE_ID,
                        new TimeEntryApprovedEvent(SAMPLE_ID, WORKORDER_ID, "user-1", EVENT_TIME)));
    }

    /** The payload type whose SCHEMA_VERSION the publish calls above pass — kept in step. */
    private static WorkSessionStartedEvent workSessionStarted() {
        return new WorkSessionStartedEvent(SAMPLE_ID, MECHANIC_ID, EVENT_TIME);
    }
}

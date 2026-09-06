package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.KafkaOutboxEvent;
import com.positivity.accounting.internal.repository.KafkaOutboxEventRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.accounting.InvoiceGlPostedV1;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the pos-accounting {@link OutboxEventWriter} (ADR-0044 §4, issue #1843): the
 * envelope is serialized whole into {@code kafka_event_outbox}, keyed by aggregate id, with the
 * row's {@code createdAt} stamped from the injected clock.
 */
@DisplayName("pos-accounting OutboxEventWriter — transactional outbox row shape")
class OutboxEventWriterTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID JOURNAL_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-00000000000e");

    private final KafkaOutboxEventRepository repository = mock(KafkaOutboxEventRepository.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final OutboxEventWriter writer = new OutboxEventWriter(objectMapper, repository);

    private DomainEventEnvelope<InvoiceGlPostedV1> envelope() {
        return DomainEventEnvelope.of(
                InvoiceGlPostedV1.EVENT_TYPE,
                InvoiceGlPostedV1.SCHEMA_VERSION,
                INVOICE_ID,
                0L,
                "pos-accounting",
                null,
                null,
                new InvoiceGlPostedV1(
                        INVOICE_ID, JOURNAL_ENTRY_ID, InvoiceGlPostedV1.PostingKind.POSTED, NOW, NOW, null),
                TEST_CLOCK);
    }

    @Test
    @DisplayName("writes one row carrying the topic, the aggregate-id record key, the JSON envelope and the clock time")
    void writesSerializedEnvelopeRow() {
        DomainEventEnvelope<InvoiceGlPostedV1> envelope = envelope();

        writer.publish("accounting.events.v1", envelope);

        ArgumentCaptor<KafkaOutboxEvent> row = ArgumentCaptor.forClass(KafkaOutboxEvent.class);
        verify(repository).save(row.capture());
        KafkaOutboxEvent saved = row.getValue();
        assertThat(saved.getTopic()).isEqualTo("accounting.events.v1");
        assertThat(saved.getRecordKey()).isEqualTo(INVOICE_ID.toString());
        // createdAt is stamped by JPA auditing (@CreatedDate, ADR-0024), not by the writer.
        assertThat(saved.getCreatedAt()).isNull();
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(saved.getAttempts()).isZero();

        JsonNode json = objectMapper.readTree(saved.getPayload());
        assertThat(json.path("eventId").stringValue())
                .isEqualTo(envelope.eventId().toString());
        assertThat(json.path("eventType").stringValue()).isEqualTo("accounting.invoice.gl-posted");
        assertThat(json.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(json.path("aggregateId").stringValue()).isEqualTo(INVOICE_ID.toString());
        assertThat(json.path("sourceService").stringValue()).isEqualTo("pos-accounting");
        assertThat(json.path("payload").path("journalEntryId").stringValue()).isEqualTo(JOURNAL_ENTRY_ID.toString());
        assertThat(json.path("payload").path("postingKind").stringValue()).isEqualTo("POSTED");
        assertThat(json.path("payload").path("invoiceId").stringValue()).isEqualTo(INVOICE_ID.toString());
    }

    @Test
    @DisplayName("a serialization failure surfaces as IllegalStateException and writes nothing")
    void serializationFailureWritesNothing() {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new IllegalArgumentException("boom"));
        OutboxEventWriter failingWriter = new OutboxEventWriter(failing, repository);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> failingWriter.publish("accounting.events.v1", envelope()))
                .withMessageContaining("accounting.invoice.gl-posted");

        verify(repository, never()).save(any());
    }
}

package com.positivity.customer.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.entity.OutboxEvent;
import com.positivity.customer.internal.repository.OutboxEventRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link OutboxEventWriter} (ADR-0044 §4, issue #842).
 *
 * <p>
 * The outbox exists so an event is queued if and only if the business
 * transaction committed. What this class must get right is the row it writes:
 *
 * <ul>
 * <li><b>The record key is the aggregate id.</b> Kafka orders only within a
 * partition, so a key that varied per event would let two facts about one party
 * arrive out of order.</li>
 * <li><b>The stored payload is the whole envelope</b>, not just the domain
 * payload — {@code OutboxPublisher} forwards the row's bytes verbatim.</li>
 * <li><b>A serialization failure is fatal.</b> Writing an unreadable row would
 * produce an event that can never be published; because the write is
 * {@code MANDATORY} on the caller's transaction, throwing correctly rolls the
 * business change back with it.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxEventWriter — transactional outbox row contract")
class OutboxEventWriterTest {

    private static final UUID PARTY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxEventWriter writer;

    @BeforeEach
    void setUp() {
        writer = new OutboxEventWriter(clock, new ObjectMapper(), outboxEventRepository);
    }

    private static DomainEventEnvelope<Object> envelope() {
        return DomainEventEnvelope.of(
                CustomerPartyUpdatedV1.EVENT_TYPE,
                1,
                PARTY_ID,
                4L,
                "pos-customer",
                null,
                null,
                new CustomerPartyUpdatedV1(
                        PARTY_ID,
                        "PERSON",
                        "C-1001",
                        "Ada Lovelace",
                        null,
                        null,
                        "ACTIVE",
                        "STANDARD",
                        true,
                        null,
                        null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OutboxEvent captureSaved() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("keys the row on the aggregate id and stores the serialized envelope")
    void publishWritesEnvelopeRow() {
        writer.publish("customer.events.v1", envelope());

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTopic()).isEqualTo("customer.events.v1");
        assertThat(saved.getRecordKey()).isEqualTo(PARTY_ID.toString());
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getPayload())
                .contains(CustomerPartyUpdatedV1.EVENT_TYPE)
                .contains(PARTY_ID.toString())
                .contains("pos-customer");
    }

    @Test
    @DisplayName("stores a pre-serialized command verbatim under the caller's ordering key")
    void publishRawStoresMessageUnchanged() {
        writer.publishRaw("people.commands.v1", "person-42", "{\"commandType\":\"x\"}");

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTopic()).isEqualTo("people.commands.v1");
        assertThat(saved.getRecordKey()).isEqualTo("person-42");
        assertThat(saved.getPayload()).isEqualTo("{\"commandType\":\"x\"}");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("fails the transaction rather than queueing an envelope it could not serialize")
    void serializationFailureIsFatal() {
        ObjectMapper failing = org.mockito.Mockito.mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new IllegalStateException("boom"));
        OutboxEventWriter failingWriter = new OutboxEventWriter(clock, failing, outboxEventRepository);

        assertThatThrownBy(() -> failingWriter.publish("customer.events.v1", envelope()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CustomerPartyUpdatedV1.EVENT_TYPE);

        verify(outboxEventRepository, never()).save(any());
    }
}

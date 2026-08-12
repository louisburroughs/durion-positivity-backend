package com.positivity.marketing.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.marketing.MarketingCampaignScheduledV1;
import com.positivity.marketing.internal.entity.OutboxEvent;
import com.positivity.marketing.internal.repository.OutboxEventRepository;
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
 * Unit tests for {@link OutboxEventWriter}.
 *
 * <p>
 * The outbox exists so an event is queued if and only if the business
 * transaction committed. What this class must get right is the row it writes:
 *
 * <ul>
 * <li><b>The record key is the aggregate id.</b> Kafka only orders within a
 * partition, so a key that varied per event would let two facts about one
 * aggregate arrive out of order.</li>
 * <li><b>The stored payload is the whole envelope</b>, not just the domain
 * payload — the publisher forwards the row's bytes verbatim.</li>
 * <li><b>A serialization failure is fatal, not swallowed.</b> Writing an
 * unreadable row would produce an event that can never be published, and
 * because the write is transactional, throwing correctly rolls the business
 * change back with it.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxEventWriter — transactional outbox row contract")
class OutboxEventWriterTest {

    private static final UUID AGGREGATE_ID = UUID.fromString("01960004-0000-7000-8000-0000000000c1");
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
                MarketingCampaignScheduledV1.EVENT_TYPE,
                1,
                AGGREGATE_ID,
                0L,
                "pos-marketing",
                null,
                null,
                new MarketingCampaignScheduledV1(AGGREGATE_ID, "SPRING", "COMMERCIAL", UUID.randomUUID(), NOW),
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
        writer.publish("marketing.events.v1", envelope());

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTopic()).isEqualTo("marketing.events.v1");
        assertThat(saved.getRecordKey()).isEqualTo(AGGREGATE_ID.toString());
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getPayload())
                .contains(MarketingCampaignScheduledV1.EVENT_TYPE)
                .contains(AGGREGATE_ID.toString())
                .contains("pos-marketing");
        // Not yet drained: publishedAt is what OutboxPublisher stamps.
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(saved.getAttempts()).isZero();
    }

    @Test
    @DisplayName("stores a pre-serialized command verbatim under the caller's ordering key")
    void publishRawStoresMessageUnchanged() {
        writer.publishRaw("customer.commands.v1", "segment-42", "{\"commandType\":\"x\"}");

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTopic()).isEqualTo("customer.commands.v1");
        assertThat(saved.getRecordKey()).isEqualTo("segment-42");
        assertThat(saved.getPayload()).isEqualTo("{\"commandType\":\"x\"}");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("fails the transaction rather than queueing an envelope it could not serialize")
    void serializationFailureIsFatal() {
        ObjectMapper failing = org.mockito.Mockito.mock(ObjectMapper.class);
        when(failing.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("boom"));
        OutboxEventWriter failingWriter = new OutboxEventWriter(clock, failing, outboxEventRepository);

        assertThatThrownBy(() -> failingWriter.publish("marketing.events.v1", envelope()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MarketingCampaignScheduledV1.EVENT_TYPE);

        verify(outboxEventRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }
}

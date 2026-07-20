package com.positivity.invoice.internal.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.payment.SettlementProviderConfigV1;
import com.positivity.domainevents.payment.SettlementReportedV1;
import com.positivity.invoice.internal.config.OutboxEventWriter;
import com.positivity.invoice.internal.config.SettlementEventPublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** Unit tests for the settlement/config outbox emission (story F1b, issue #962). */
@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementEventPublisher")
class SettlementEventPublisherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxEventWriter;

    @Mock
    private OutboxEventWriter writer;

    private SettlementEventPublisher publisher() {
        return new SettlementEventPublisher(CLOCK, outboxEventWriter);
    }

    private static SettlementReportedV1 settlement() {
        return new SettlementReportedV1(
                UUID.randomUUID(),
                1,
                "stl_123",
                "STRIPE",
                java.time.LocalDate.of(2026, 6, 15),
                "USD",
                new BigDecimal("1000.00"),
                new BigDecimal("30.00"),
                new BigDecimal("970.00"),
                List.of(new SettlementReportedV1.Line(
                        "txn_1",
                        SettlementReportedV1.LineType.CHARGE,
                        new BigDecimal("1000.00"),
                        null,
                        null,
                        UUID.randomUUID().toString())),
                null);
    }

    private static SettlementProviderConfigV1 config() {
        return new SettlementProviderConfigV1(
                UUID.randomUUID(),
                1,
                "STRIPE",
                "Stripe",
                SettlementProviderConfigV1.MatchReferenceField.PAYMENT_REFERENCE,
                new BigDecimal("0.00"),
                SettlementProviderConfigV1.FeeRepresentation.HEADER_ONLY,
                null);
    }

    @Test
    @DisplayName("publishes a settlement fact to payment.events.v1")
    void publishesSettlementFact() {
        when(outboxEventWriter.getIfAvailable()).thenReturn(writer);
        SettlementReportedV1 payload = settlement();

        publisher().publishSettlementReported(payload);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("payment.events.v1"), captor.capture());
        DomainEventEnvelope<?> envelope = captor.getValue();
        assertThat(envelope.eventType()).isEqualTo(SettlementReportedV1.EVENT_TYPE);
        assertThat(envelope.sourceService()).isEqualTo("pos-invoice");
        assertThat(envelope.payload()).isEqualTo(payload);
        // stable key derived from settlementId "stl_123" (non-UUID → name-based UUID)
        assertThat(envelope.recordKey())
                .isEqualTo(UUID.nameUUIDFromBytes("stl_123".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString());
    }

    @Test
    @DisplayName("publishes provider config to the compacted config topic")
    void publishesProviderConfig() {
        when(outboxEventWriter.getIfAvailable()).thenReturn(writer);
        SettlementProviderConfigV1 payload = config();

        publisher().publishProviderConfig(payload);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("payment.settlement-config.v1"), captor.capture());
        DomainEventEnvelope<?> envelope = captor.getValue();
        assertThat(envelope.eventType()).isEqualTo(SettlementProviderConfigV1.EVENT_TYPE);
        assertThat(envelope.payload()).isEqualTo(payload);
        // compaction key is stable per providerCode
        assertThat(envelope.recordKey())
                .isEqualTo(UUID.nameUUIDFromBytes("STRIPE".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString());
    }

    @Test
    @DisplayName("is a no-op when the outbox writer bean is absent (Kafka disabled)")
    void noOpWhenWriterUnavailable() {
        when(outboxEventWriter.getIfAvailable()).thenReturn(null);

        publisher().publishSettlementReported(settlement());
        publisher().publishProviderConfig(config());

        verifyNoInteractions(writer);
    }
}

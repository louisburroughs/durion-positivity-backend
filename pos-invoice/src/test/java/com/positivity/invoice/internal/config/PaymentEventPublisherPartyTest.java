package com.positivity.invoice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.payment.PaymentSettledV1;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.PaymentIntent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * CAP:550 S9 (spec §4.4 item 1, §7.3): every {@code payment.payment.settled} queued by pos-invoice
 * is schema version 2 and names the party; a missing party is a defect that is still published
 * (the capture already happened) but logged at ERROR and counted.
 */
@DisplayName("PaymentEventPublisher party guarantee (CAP:550 S9)")
class PaymentEventPublisherPartyTest {

    private static final String PARTY_ID = "00000000-0000-0000-0000-0000000000ca";

    private OutboxEventWriter writer;
    private SimpleMeterRegistry registry;
    private PaymentEventPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        writer = mock(OutboxEventWriter.class);
        ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        InvoiceCurrencySource currencySource = mock(InvoiceCurrencySource.class);
        when(currencySource.currencyCode()).thenReturn("USD");
        registry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(registry);
        Clock clock = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);
        publisher = new PaymentEventPublisher(clock, currencySource, writerProvider, registryProvider);
    }

    private static PaymentIntent capturedIntent(String partyId) {
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setInvoiceNumber("INV-000042");
        invoice.setPartyId(partyId);
        PaymentIntent intent = new PaymentIntent();
        intent.setId(UUID.randomUUID());
        intent.setInvoice(invoice);
        intent.setCapturedAmount(new BigDecimal("42.00"));
        return intent;
    }

    private DomainEventEnvelope<?> queuedEnvelope() {
        ArgumentCaptor<DomainEventEnvelope<?>> envelope = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(anyString(), envelope.capture());
        return envelope.getValue();
    }

    /** AC4 + AC5: the queued envelope is schema version 2 and the payload names the party. */
    @Test
    @DisplayName("a captured intent on the CASH party queues schemaVersion 2 with that partyId")
    void settledFactCarriesPartyAndSchemaVersionTwo() {
        publisher.publishPaymentSettled(capturedIntent(PARTY_ID));

        DomainEventEnvelope<?> envelope = queuedEnvelope();
        assertThat(envelope.schemaVersion()).isEqualTo(2);
        assertThat(envelope.eventType()).isEqualTo(PaymentSettledV1.EVENT_TYPE);
        PaymentSettledV1 payload = (PaymentSettledV1) envelope.payload();
        assertThat(payload.partyId()).isEqualTo(PARTY_ID);
        assertThat(registry.get(PaymentEventPublisher.PARTY_MISSING_COUNTER)
                        .counter()
                        .count())
                .isZero();
    }

    /** Defect path: the fact is still queued (pos-order must complete the order) and the counter fires. */
    @Test
    @DisplayName("a missing party is still published, but counted on payment.settled.party_missing")
    void missingPartyIsPublishedAndCounted() {
        publisher.publishPaymentSettled(capturedIntent(null));

        DomainEventEnvelope<?> envelope = queuedEnvelope();
        assertThat(envelope.schemaVersion()).isEqualTo(2);
        assertThat(((PaymentSettledV1) envelope.payload()).partyId()).isNull();
        assertThat(registry.get(PaymentEventPublisher.PARTY_MISSING_COUNTER)
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a blank party counts as missing")
    void blankPartyIsCounted() {
        publisher.publishPaymentSettled(capturedIntent("  "));

        queuedEnvelope();
        assertThat(registry.get(PaymentEventPublisher.PARTY_MISSING_COUNTER)
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }
}

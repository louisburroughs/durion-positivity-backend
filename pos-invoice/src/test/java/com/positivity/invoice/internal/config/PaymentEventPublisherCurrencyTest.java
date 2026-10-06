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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** Payment events state the same currency as the rest of pos-invoice (#2318). */
class PaymentEventPublisherCurrencyTest {

    @Test
    @SuppressWarnings("unchecked")
    void settledEventReadsTheModuleCurrencySource() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        ObjectProvider<OutboxEventWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(writer);
        InvoiceCurrencySource currencySource = mock(InvoiceCurrencySource.class);
        when(currencySource.currencyCode()).thenReturn("CAD");
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC);
        ObjectProvider<MeterRegistry> meterRegistry = mock(ObjectProvider.class);
        PaymentEventPublisher publisher = new PaymentEventPublisher(clock, currencySource, provider, meterRegistry);

        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setPartyId(UUID.randomUUID().toString());
        PaymentIntent intent = new PaymentIntent();
        intent.setId(UUID.randomUUID());
        intent.setInvoice(invoice);
        intent.setCapturedAmount(new BigDecimal("10.00"));

        publisher.publishPaymentSettled(intent);

        ArgumentCaptor<DomainEventEnvelope<?>> envelope = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(anyString(), envelope.capture());
        assertThat(((PaymentSettledV1) envelope.getValue().payload()).currencyCode())
                .isEqualTo("CAD");
    }
}

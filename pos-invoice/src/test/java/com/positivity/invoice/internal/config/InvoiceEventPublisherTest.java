package com.positivity.invoice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.repository.InvoiceLineTaxRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for the {@code invoice.invoice.updated} tax-breakdown projection (#982): the producer
 * is authoritative over an invoice's tax rows, so it must emit an <em>empty</em> breakdown (not
 * {@code null}) when the invoice has none — that is what lets the accounting listener clear its
 * {@code ext_invoice_tax} replica on a taxed&rarr;untaxed transition.
 */
@DisplayName("InvoiceEventPublisher taxBreakdown null-vs-empty contract (#982)")
class InvoiceEventPublisherTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC);

    private InvoiceLineTaxRepository invoiceLineTaxRepository;
    private OutboxEventWriter writer;
    private InvoiceEventPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EntityManager entityManager = mock(EntityManager.class);
        invoiceLineTaxRepository = mock(InvoiceLineTaxRepository.class);
        writer = mock(OutboxEventWriter.class);
        ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        publisher = new InvoiceEventPublisher(FIXED_CLOCK, entityManager, writerProvider, invoiceLineTaxRepository);
    }

    private Invoice finalizedInvoice() {
        Invoice invoice = mock(Invoice.class);
        when(invoice.getId()).thenReturn(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        when(invoice.getInvoiceNumber()).thenReturn("INV-2026-000123");
        when(invoice.getWorkorderId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        when(invoice.getPartyId()).thenReturn("party-1");
        when(invoice.getStatus()).thenReturn(InvoiceStatus.FINALIZED);
        when(invoice.getSubtotal()).thenReturn(new BigDecimal("200.00"));
        when(invoice.getTax()).thenReturn(BigDecimal.ZERO);
        when(invoice.getTotal()).thenReturn(new BigDecimal("200.00"));
        when(invoice.getAdjustmentsAmount()).thenReturn(BigDecimal.ZERO);
        when(invoice.getCreatedAt()).thenReturn(Instant.parse("2026-07-20T09:00:00Z"));
        when(invoice.getFinalizedAt()).thenReturn(Instant.parse("2026-07-20T10:00:00Z"));
        when(invoice.getVersion()).thenReturn(2);
        return invoice;
    }

    @SuppressWarnings("unchecked")
    private InvoiceUpdatedV1 capturePayload(Invoice invoice) {
        publisher.publishInvoiceUpdated(invoice);
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(any(), captor.capture());
        return (InvoiceUpdatedV1) captor.getValue().payload();
    }

    @Test
    @DisplayName("no tax rows → emits an empty (non-null) breakdown so the replica is cleared")
    void emptyRowsEmitEmptyList() {
        Invoice invoice = finalizedInvoice();
        when(invoiceLineTaxRepository.findByInvoiceId(eq(invoice.getId()))).thenReturn(List.of());

        InvoiceUpdatedV1 payload = capturePayload(invoice);

        assertThat(payload.taxBreakdown()).isNotNull().isEmpty();
    }
}

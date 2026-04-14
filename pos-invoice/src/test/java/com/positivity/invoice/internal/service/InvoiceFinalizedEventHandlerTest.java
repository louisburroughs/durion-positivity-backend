package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.positivity.invoice.internal.dto.InvoiceFinalizedEvent;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvoiceFinalizedEventHandlerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private InvoiceRepository invoiceRepository;

    @InjectMocks
    private InvoiceFinalizedEventHandler eventHandler;

    private Invoice invoice;
    private InvoiceFinalizedEvent event;
    private UUID invoiceId;

    @BeforeEach
    void setUp() {
        invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus(InvoiceStatus.FINALIZED);

        event = new InvoiceFinalizedEvent(
                invoiceId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "test-actor",
                Instant.now(TEST_CLOCK),
                BigDecimal.valueOf(100.00));
    }

    @Test
    void onInvoiceFinalized_shouldUpdateStatusToPosted() {
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        eventHandler.onInvoiceFinalized(event);

        verify(invoiceRepository).findById(invoiceId);
        verify(invoiceRepository).save(invoice);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.POSTED);
        assertThat(invoice.getGlEntryId()).isNotNull();
    }

    @Test
    void onInvoiceFinalized_shouldDoNothingIfInvoiceAlreadyPosted() {
        invoice.setStatus(InvoiceStatus.POSTED);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        eventHandler.onInvoiceFinalized(event);

        verify(invoiceRepository).findById(invoiceId);
        verify(invoiceRepository, never()).save(any(Invoice.class));
    }

    @Test
    void onInvoiceFinalized_shouldUpdateStatusToErrorOnException() {
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        // First save (success path) throws; second save (error-state persist) succeeds
        doThrow(new RuntimeException("DB error"))
                .doReturn(invoice)
                .when(invoiceRepository)
                .save(invoice);

        eventHandler.onInvoiceFinalized(event);

        // The handler should try to save twice: once for the success path (which
        // fails),
        // and once for the error path.
        verify(invoiceRepository, times(2)).save(invoice);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ERROR);
    }

    @Test
    void onInvoiceFinalized_shouldDoNothingIfInvoiceNotFound() {
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        eventHandler.onInvoiceFinalized(event);

        verify(invoiceRepository).findById(invoiceId);
        verify(invoiceRepository, never()).save(any(Invoice.class));
    }
}

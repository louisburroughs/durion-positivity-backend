package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.service.InvoiceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceImplTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @InjectMocks
    private InvoiceServiceImpl invoiceService;

    @Test
    void getInvoiceById_shouldReturnInvoice() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        Optional<Invoice> result = invoiceService.getInvoiceById(invoiceId);

        assertTrue(result.isPresent());
        assertEquals(invoiceId, result.get().getId());
    }

    @Test
    void getInvoiceById_shouldReturnEmptyWhenNotFound() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        Optional<Invoice> result = invoiceService.getInvoiceById(invoiceId);

        assertTrue(result.isEmpty());
    }
}

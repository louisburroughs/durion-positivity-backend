package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.invoice.internal.client.DocumentRenderClient;
import com.positivity.invoice.internal.config.InvoiceCurrencySource;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.ReceiptRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The invoice document states the currency its totals are in (#2318, ADR-0067 DF-13). */
class InvoiceArtifactServiceTest {

    private static final String TOKEN = "token";

    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final ReceiptRepository receiptRepository = mock(ReceiptRepository.class);
    private final DocumentRenderClient documentRenderClient = mock(DocumentRenderClient.class);
    private final ArtifactTokenService tokenService = mock(ArtifactTokenService.class);
    private final InvoiceCurrencySource currencySource = mock(InvoiceCurrencySource.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private InvoiceArtifactService service;
    private Invoice invoice;
    private String invoiceRef;

    @BeforeEach
    void setUp() {
        service = new InvoiceArtifactService(
                invoiceRepository, receiptRepository, documentRenderClient, tokenService, currencySource, objectMapper);
        invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setInvoiceNumber("INV-1");
        invoice.setSubtotal(new BigDecimal("100.00"));
        invoice.setTax(new BigDecimal("8.00"));
        invoice.setTotal(new BigDecimal("108.00"));
        invoiceRef = new ArtifactRef(ArtifactRef.Type.INVOICE, invoice.getId()).encode();
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(documentRenderClient.renderPdf(anyString(), anyString())).thenReturn(new byte[] {1});
    }

    @Test
    void invoiceContentStatesTheModuleCurrency() throws Exception {
        when(currencySource.currencyCode()).thenReturn("USD");

        JsonNode content = renderedInvoiceContent();

        assertThat(content.path("currencyCode").asText()).isEqualTo("USD");
    }

    @Test
    void invoiceContentFollowsTheCurrencySource() throws Exception {
        when(currencySource.currencyCode()).thenReturn("CAD");

        JsonNode content = renderedInvoiceContent();

        assertThat(content.path("currencyCode").asText()).isEqualTo("CAD");
    }

    @Test
    void currencyCodeSitsBesideTheTotals() throws Exception {
        when(currencySource.currencyCode()).thenReturn("USD");

        List<String> keys = new ArrayList<>();
        renderedInvoiceContent().fieldNames().forEachRemaining(keys::add);

        // The document renders content fields in order, so the code must head the amounts it governs.
        int currency = keys.indexOf("currencyCode");
        assertThat(currency).isNotNegative();
        assertThat(keys.subList(currency + 1, currency + 5)).containsExactly("subtotal", "tax", "adjustments", "total");
    }

    private JsonNode renderedInvoiceContent() throws Exception {
        service.downloadArtifact(invoice.getId(), invoiceRef, TOKEN);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(documentRenderClient).renderPdf(eq("invoice-default"), content.capture());
        return objectMapper.readTree(content.getValue());
    }
}

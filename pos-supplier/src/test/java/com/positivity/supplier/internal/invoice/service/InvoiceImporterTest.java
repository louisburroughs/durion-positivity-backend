package com.positivity.supplier.internal.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierInvoiceReceivedV1;
import com.positivity.supplier.internal.domain.model.SupplierInvoice;
import com.positivity.supplier.internal.entity.SupplierInvoiceEntity;
import com.positivity.supplier.internal.repository.SupplierInvoiceRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Importing fetched vendor invoices (CAP-321 #1227).
 *
 * <p>The whole of this class exists for one property: a debt is recorded once. Fetch windows
 * overlap deliberately, so the same invoice arrives repeatedly by design — and every one of those
 * arrivals is an opportunity to bill the business twice.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvoiceImporter — a debt recorded once (#1227)")
class InvoiceImporterTest {

    private static final UUID PROFILE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a01");
    private static final String REF = "michelin-de";
    private static final Instant NOW = Instant.parse("2026-08-16T08:00:00Z");
    private static final LocalDate ISSUED = LocalDate.of(2026, 8, 14);

    @Mock
    private SupplierInvoiceRepository invoiceRepository;

    @Mock
    private SupplierOutboxEventWriter outboxEventWriter;

    private InvoiceImporter importer;

    @BeforeEach
    void setUp() {
        importer = new InvoiceImporter(invoiceRepository, outboxEventWriter, Clock.fixed(NOW, ZoneOffset.UTC));
        when(invoiceRepository.findByVendorProfileIdAndVendorInvoiceNumberAndInvoiceDate(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(invoiceRepository.save(any())).thenAnswer(call -> {
            SupplierInvoiceEntity saved = call.getArgument(0);
            saved.setSupplierInvoiceId(UUID.randomUUID());
            return saved;
        });
    }

    private static SupplierInvoice invoice(String number, SupplierInvoice.Type type, String total) {
        return new SupplierInvoice(
                number,
                type,
                ISSUED,
                "EUR",
                new BigDecimal("240.00"),
                new BigDecimal("48.00"),
                new BigDecimal(total),
                List.of(new SupplierInvoice.Line(
                        1,
                        "5012345678900",
                        "MI-2055516",
                        4,
                        new BigDecimal("60.00"),
                        new BigDecimal("240.00"),
                        "PO-778")));
    }

    private SupplierInvoiceReceivedV1 published() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxEventWriter).publish(eq("supplier.events.v1"), captor.capture());
        return (SupplierInvoiceReceivedV1) captor.getValue().payload();
    }

    @Test
    @DisplayName("a new invoice is stored and published with the vendor's own figures")
    void newInvoiceIsStoredAndPublished() {
        int imported = importer.importInvoices(
                PROFILE, REF, List.of(invoice("INV-1", SupplierInvoice.Type.INVOICE, "288.00")));

        assertThat(imported).isEqualTo(1);
        SupplierInvoiceReceivedV1 fact = published();
        assertThat(fact.vendorInvoiceNumber()).isEqualTo("INV-1");
        assertThat(fact.totalGrossAmount()).isEqualByComparingTo("288.00");
        assertThat(fact.isPayable()).isTrue();
        // The order reference travels so the consumer can attempt a match; resolving it is not this
        // module's business, and an unresolvable one is no reason to withhold the invoice.
        assertThat(fact.vendorOrderReference()).isEqualTo("PO-778");
    }

    @Test
    @DisplayName("an invoice already held from an overlapping window is not published again")
    void alreadyHeldInvoiceIsDropped() {
        when(invoiceRepository.findByVendorProfileIdAndVendorInvoiceNumberAndInvoiceDate(PROFILE, "INV-1", ISSUED))
                .thenReturn(Optional.of(new SupplierInvoiceEntity()));

        int imported = importer.importInvoices(
                PROFILE, REF, List.of(invoice("INV-1", SupplierInvoice.Type.INVOICE, "288.00")));

        // Windows overlap so a failed fetch can simply be repeated. Publishing again would put the
        // same debt in front of AP a second time, with only its own guard between that and a
        // duplicate payment.
        assertThat(imported).isZero();
        verify(invoiceRepository, never()).save(any());
        verify(outboxEventWriter, never()).publish(any(), any());
    }

    @Test
    @DisplayName("a credit note is published as one, not as a negative invoice")
    void creditNoteKeepsItsType() {
        importer.importInvoices(PROFILE, REF, List.of(invoice("CN-9", SupplierInvoice.Type.CREDIT_NOTE, "50.00")));

        SupplierInvoiceReceivedV1 fact = published();
        // The direction is carried as a type rather than as a sign, so a consumer cannot mistake a
        // refund for a debt by reading the amount alone.
        assertThat(fact.type()).isEqualTo(SupplierInvoiceReceivedV1.Type.CREDIT_NOTE);
        assertThat(fact.isPayable()).isFalse();
        assertThat(fact.totalGrossAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("a batch imports what is new and skips what is not")
    void mixedBatchImportsOnlyTheNew() {
        when(invoiceRepository.findByVendorProfileIdAndVendorInvoiceNumberAndInvoiceDate(PROFILE, "INV-1", ISSUED))
                .thenReturn(Optional.of(new SupplierInvoiceEntity()));

        int imported = importer.importInvoices(
                PROFILE,
                REF,
                List.of(
                        invoice("INV-1", SupplierInvoice.Type.INVOICE, "288.00"),
                        invoice("INV-2", SupplierInvoice.Type.INVOICE, "120.00")));

        assertThat(imported).isEqualTo(1);
        verify(invoiceRepository).save(any());
    }
}

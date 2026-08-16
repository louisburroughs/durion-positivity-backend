package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.Vendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.repository.VendorRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Vendor invoices becoming AP bills (CAP-321 #1227).
 *
 * <p>These tests pin the three judgments documented on the listener, because each is a decision
 * somebody could reasonably have made differently and none of them is enforced by a type.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupplierInvoiceEventsListener — vendor invoices as AP bills (#1227)")
class SupplierInvoiceEventsListenerTest {

    private static final UUID PROFILE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a01");
    private static final Instant NOW = Instant.parse("2026-08-16T09:00:00Z");

    /** Envelope event ids are UUIDv7 in this system; the listener records one on the bill. */
    private static final String EVENT_1 = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7b01";

    private static final String EVENT_2 = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7b02";
    private static final String EVENT_3 = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7b03";
    private static final String EVENT_4 = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7b04";
    private static final String EVENT_5 = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7b05";
    private static final String EVENT_6 = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7b06";
    private static final String EVENT_7 = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7b07";
    private static final String EVENT_8 = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7b08";

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private VendorBillRepository vendorBillRepository;

    @Mock
    private VendorRepository vendorRepository;

    private SupplierInvoiceEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new SupplierInvoiceEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(),
                processedEventRepository,
                vendorBillRepository,
                vendorRepository);
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(vendorBillRepository.findByVendorIdAndBillNumber(any(), any())).thenReturn(Optional.empty());
        when(vendorRepository.existsById(any())).thenReturn(false);
    }

    private static String event(String eventId, String number, String type, String total) {
        return """
            {"eventId":"%s","eventType":"supplier.invoice.received","payload":{
              "vendorProfileId":"%s","supplierRef":"michelin-de","vendorInvoiceNumber":"%s",
              "invoiceDate":"2026-08-14","type":"%s","currency":"EUR",
              "totalNetAmount":240.00,"totalTaxAmount":48.00,"totalGrossAmount":%s,
              "vendorOrderReference":"PO-778","occurredAt":"2026-08-16T08:00:00Z","lines":[]}}
            """.formatted(eventId, PROFILE, number, type, total);
    }

    private VendorBill captured() {
        ArgumentCaptor<VendorBill> captor = ArgumentCaptor.forClass(VendorBill.class);
        verify(vendorBillRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("an unmatched invoice waits for its receipt rather than becoming an exception")
    void unmatchedInvoiceIsPendingNotException() {
        listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "288.00"));

        // Judgment 1. An invoice arriving before its goods receipt is the ordinary early state of a
        // perfectly good invoice; filing it as an exception would fill the exception queue with
        // normal paperwork until the queue meant nothing.
        assertThat(captured().getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
    }

    @Test
    @DisplayName("no journal entry is created on ingest")
    void noJournalEntryOnIngest() {
        listener.onSupplierEvent(event(EVENT_2, "INV-2", "INVOICE", "288.00"));

        // Judgment 2. An unapproved vendor invoice is a claim, not a liability anyone agreed to.
        // Posting on arrival would move our accounts on the vendor's say-so alone.
        assertThat(captured().getJournalEntry()).isNull();
    }

    @Test
    @DisplayName("the supplier's profile id is the accounting vendor id, and the directory learns it")
    void vendorDirectoryEntryIsCreated() {
        listener.onSupplierEvent(event(EVENT_3, "INV-3", "INVOICE", "288.00"));

        // Judgment 3. Vendor states its id is assigned by the system owning the relationship, which
        // for an EDIWheel vendor is pos-supplier. A separate accounting id plus a mapping table
        // would create the ambiguity that rule exists to avoid.
        ArgumentCaptor<Vendor> captor = ArgumentCaptor.forClass(Vendor.class);
        verify(vendorRepository).save(captor.capture());
        assertThat(captor.getValue().getVendorId()).isEqualTo(PROFILE);
        assertThat(captured().getVendorId()).isEqualTo(PROFILE);
    }

    @Test
    @DisplayName("the bill carries the vendor's own number, not one we mint")
    void billNumberIsTheVendorsOwn() {
        listener.onSupplierEvent(event(EVENT_4, "INV-40021", "INVOICE", "288.00"));

        // It is what an AP clerk quotes back to the vendor; a number of our own would be
        // meaningless in that conversation.
        assertThat(captured().getBillNumber()).isEqualTo("INV-40021");
        assertThat(captured().getPurchaseOrderNumber()).isEqualTo("PO-778");
    }

    @Test
    @DisplayName("a credit note is recorded as money owed back")
    void creditNoteIsNegative() {
        listener.onSupplierEvent(event(EVENT_5, "CN-9", "CREDIT_NOTE", "50.00"));

        // The sign comes from the document type, not from the figure: a vendor may state a credit
        // as a positive number on a document that declares itself a credit note.
        assertThat(captured().getTotalAmount()).isEqualByComparingTo("-50.00");
    }

    @Test
    @DisplayName("the same invoice fetched again does not become a second bill")
    void refetchDoesNotDuplicateTheDebt() {
        VendorBill existing = new VendorBill();
        existing.setTotalAmount(new BigDecimal("288.00"));
        when(vendorBillRepository.findByVendorIdAndBillNumber(PROFILE, "INV-1")).thenReturn(Optional.of(existing));

        listener.onSupplierEvent(event(EVENT_6, "INV-1", "INVOICE", "288.00"));

        // A re-fetch carries a new event id, so the event guard would not catch it. This is the
        // guard that stops the business being billed twice.
        verify(vendorBillRepository, never()).save(any());
    }

    @Test
    @DisplayName("a re-issue for a different amount is flagged, not silently overwritten")
    void reissueAtADifferentAmountIsFlagged() {
        VendorBill existing = new VendorBill();
        existing.setTotalAmount(new BigDecimal("288.00"));
        existing.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
        when(vendorBillRepository.findByVendorIdAndBillNumber(PROFILE, "INV-1")).thenReturn(Optional.of(existing));

        listener.onSupplierEvent(event(EVENT_7, "INV-1", "INVOICE", "412.00"));

        // Overwriting would erase a change somebody may already have approved against.
        assertThat(existing.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
        assertThat(existing.getTotalAmount()).isEqualByComparingTo("288.00");
    }

    @Test
    @DisplayName("a redelivered event changes nothing")
    void replayIsANoOp() {
        when(processedEventRepository.existsById(EVENT_1)).thenReturn(true);

        listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "288.00"));

        verify(vendorBillRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("transient database trouble is retried, not swallowed")
    void transientFailureIsRethrown() {
        when(vendorBillRepository.save(any())).thenThrow(new QueryTimeoutException("statement timed out"));

        assertThatThrownBy(() -> listener.onSupplierEvent(event(EVENT_8, "INV-8", "INVOICE", "288.00")))
                .isInstanceOf(QueryTimeoutException.class);

        // The supplier side has already published this invoice and will not publish it again;
        // marking it processed would lose a vendor debt permanently.
        verify(processedEventRepository, never()).save(any());
    }
}

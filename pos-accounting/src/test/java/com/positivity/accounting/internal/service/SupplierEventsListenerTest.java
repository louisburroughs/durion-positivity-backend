package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillReissue;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.SupplierInvoiceHoldRepository;
import com.positivity.accounting.internal.repository.VendorBillReissueRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import tools.jackson.databind.ObjectMapper;

/**
 * Vendor invoices becoming AP bills (CAP-321 #1227).
 *
 * <p>These tests pin the three judgments documented on the listener, because each is a decision
 * somebody could reasonably have made differently and none of them is enforced by a type.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupplierEventsListener — vendor invoices as AP bills (#1227; S24 vendor key)")
class SupplierEventsListenerTest {

    private static final UUID PROFILE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a01");

    /** The pos-supplier vendor the facts name (S24): the bill's vendor, never the profile. */
    private static final UUID VENDOR = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a02");

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
    private ExtSupplierVendorRepository vendorCopy;

    @Mock
    private SupplierInvoiceHoldRepository holds;

    @Mock
    private VendorBillReissueRepository reissueRepository;

    @Mock
    private VendorBillLocks locks;

    @Mock
    private KafkaFactIngestionRecorder ingestionRecorder;

    private final VendorBillStatedTax statedTax = mock(VendorBillStatedTax.class);

    private SupplierEventsListener listener;

    @BeforeEach
    void setUp() {
        ObjectProvider<MeterRegistry> noMeters = mock();
        listener = new SupplierEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(),
                processedEventRepository,
                vendorBillRepository,
                vendorCopy,
                holds,
                new LedgerCurrency("USD"),
                ingestionRecorder,
                // The real guard over the mocked repository: the listener's lookup is the rule's query.
                new VendorBillDuplicateGuard(vendorBillRepository, noMeters),
                reissueRepository,
                locks,
                statedTax,
                noMeters,
                mock(PlatformTransactionManager.class));
        // The lock re-reads the bill as it is now; here it is unchanged.
        lenient().when(locks.lock(any())).thenAnswer(inv -> inv.getArgument(0));
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(vendorBillRepository.findLiveDuplicate(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(vendorCopy.findById(VENDOR)).thenReturn(Optional.of(activeVendor()));
    }

    /** VENDOR as accounting's copy holds it: active (S24). */
    private static ExtSupplierVendor activeVendor() {
        ExtSupplierVendor vendor = new ExtSupplierVendor();
        vendor.setVendorId(VENDOR);
        vendor.setVendorNumber("V-000123");
        vendor.setDisplayName("Michelin Deutschland");
        vendor.setStatus(ExtSupplierVendor.ACTIVE);
        vendor.setCreatedBy("buyer.ben");
        vendor.setAggregateVersion(1);
        return vendor;
    }

    private static String event(String eventId, String number, String type, String total) {
        return event(eventId, number, type, total, "USD");
    }

    private static String event(String eventId, String number, String type, String total, String currency) {
        return event(eventId, number, type, total, currency, "2026-08-14");
    }

    /** An invoice whose totals add up (AW47): tax 48.00 and the net the rest of the gross. */
    private static String event(
            String eventId, String number, String type, String total, String currency, String invoiceDate) {
        String net = new BigDecimal(total).subtract(new BigDecimal("48.00")).toPlainString();
        return event(eventId, number, type, currency, invoiceDate, total, net, "48.00", "[]");
    }

    private static String event(
            String eventId,
            String number,
            String type,
            String currency,
            String invoiceDate,
            String gross,
            String net,
            String tax,
            String lines) {
        return """
            {"eventId":"%s","eventType":"supplier.invoice.received","payload":{
              "vendorProfileId":"%s","supplierRef":"michelin-de","vendorInvoiceNumber":"%s",
              "invoiceDate":"%s","type":"%s","currency":"%s",
              "totalNetAmount":%s,"totalTaxAmount":%s,"totalGrossAmount":%s,
              "vendorOrderReference":"PO-778","occurredAt":"2026-08-16T08:00:00Z","lines":%s,
              "vendorId":"%s"}}
            """.formatted(eventId, PROFILE, number, invoiceDate, type, currency, net, tax, gross, lines, VENDOR);
    }

    /** The rule's window for the default invoice date, 2026-08-14. */
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 8, 14, 0, 0);

    private void liveOriginal(String key, VendorBill original) {
        when(vendorBillRepository.findLiveDuplicate(VENDOR, key, DAY, DAY.plusDays(1), null))
                .thenReturn(Optional.of(original));
    }

    private static final String EVENT_9 = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7b09";

    private VendorBill captured() {
        ArgumentCaptor<VendorBill> captor = ArgumentCaptor.forClass(VendorBill.class);
        verify(vendorBillRepository).saveAndFlush(captor.capture());
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
    @DisplayName("#2433: a new bill is recorded as one PROCESSED, nothing-to-post ingestion row under pos-supplier")
    void newBillIsRecordedNothingToPost() {
        listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "288.00"));

        verify(ingestionRecorder)
                .record(
                        eq("pos-supplier"),
                        eq("supplier.invoice.received"),
                        eq(EVENT_1),
                        any(),
                        eq(java.time.LocalDateTime.of(2026, 8, 14, 0, 0)),
                        any(),
                        eq(new FactPostingOutcome.NothingToPost()));
        assertThat(SupplierEventsListener.RECORDED_EVENT_TYPES).containsExactly("supplier.invoice.received");
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
    @DisplayName("S24: the fact's pos-supplier vendorId is the bill's vendor, named as the copy names it")
    void vendorIdIsTheBillsVendor() {
        listener.onSupplierEvent(event(EVENT_3, "INV-3", "INVOICE", "288.00"));

        // Judgment 3 (S24): one vendor key, the pos-supplier vendor id, never the vendor profile id.
        VendorBill bill = captured();
        assertThat(bill.getVendorId()).isEqualTo(VENDOR).isNotEqualTo(PROFILE);
        assertThat(bill.getVendorName()).isEqualTo("Michelin Deutschland");
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
        liveOriginal("INV1", existing);

        listener.onSupplierEvent(event(EVENT_6, "INV-1", "INVOICE", "288.00"));

        // A re-fetch carries a new event id, so the event guard would not catch it. This is the
        // guard that stops the business being billed twice.
        verify(vendorBillRepository, never()).save(any());
        verify(vendorBillRepository, never()).saveAndFlush(any());
        // #2433: the identity guard is a duplicate key, recorded DUPLICATE_IGNORED with no entry.
        verify(ingestionRecorder)
                .record(
                        eq("pos-supplier"),
                        eq("supplier.invoice.received"),
                        eq(EVENT_6),
                        any(),
                        any(),
                        any(),
                        eq(new FactPostingOutcome.AlreadyPosted(null, null)));
    }

    @Test
    @DisplayName("AW39 (#2509): a new EDI bill keeps the net and tax its document states")
    void newBillKeepsTheStatedNetAndTax() {
        listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "288.00"));

        VendorBill created = captured();
        assertThat(created.getTotalAmount()).isEqualByComparingTo("288.00");
        assertThat(created.getNetAmount()).isEqualByComparingTo("240.00");
        assertThat(created.getTaxAmount()).isEqualByComparingTo("48.00");
    }

    @Nested
    @DisplayName("the vendor's own totals: gross vs net + tax (AW47)")
    class Totals {

        private VendorBill created(String gross, String net, String tax, String lines) {
            listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "USD", "2026-08-14", gross, net, tax, lines));
            return captured();
        }

        @Test
        @DisplayName("AC(a): gross 1,085.00 / net 1,000.00 / tax 70.00 is created in MATCH_EXCEPTION, explained")
        void apartBeyondTheToleranceIsAnException() {
            VendorBill bill = created("1085.00", "1000.00", "70.00", "[]");

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            assertThat(bill.getRejectionReason())
                    .isEqualTo("The vendor's totals don't add up: net 1000.00 + tax 70.00 ≠ total 1085.00");
            assertThat(bill.getTotalAmount()).isEqualByComparingTo("1085.00");
            assertThat(bill.getStatedLineCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC(c): 0.01 apart on a one-line document waits for its receipt as usual")
        void withinTheToleranceIsPending() {
            assertThat(created("1070.01", "1000.00", "70.00", "[]").getStatus())
                    .isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
        }

        @Test
        @DisplayName("No net stated: net = gross - tax; a net and no tax: tax = gross - net; neither: tax 0")
        void missingAmountsAreDerived() {
            VendorBill noNet = created("1070.00", "null", "70.00", "[]");
            assertThat(noNet.getNetAmount()).isEqualByComparingTo("1000.00");
            assertThat(noNet.getTaxAmount()).isEqualByComparingTo("70.00");
            assertThat(noNet.getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
        }

        @Test
        @DisplayName(
                "AW47 ruling (#2509 comment 6059252089): gross 1,085.00 and net 1,000.00 with no tax: the tax is 0,"
                        + " the totals are checked, MATCH_EXCEPTION with a difference of 85.00")
        void missingTaxIsZeroAndChecked() {
            VendorBill noTax = created("1085.00", "1000.00", "null", "[]");

            assertThat(noTax.getTaxAmount()).isEqualByComparingTo("0");
            assertThat(noTax.getNetAmount()).isEqualByComparingTo("1000.00");
            assertThat(noTax.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            assertThat(noTax.getRejectionReason())
                    .isEqualTo("The vendor's totals don't add up: net 1000.00 + tax 0.00 ≠ total 1085.00");
            assertThat(VendorBillTotals.of(noTax).orElseThrow().difference()).isEqualByComparingTo("85.00");
        }

        @Test
        @DisplayName("Neither net nor tax stated: net = gross, tax 0")
        void grossAlone() {
            VendorBill gross = created("1070.00", "null", "null", "[]");
            assertThat(gross.getNetAmount()).isEqualByComparingTo("1070.00");
            assertThat(gross.getTaxAmount()).isEqualByComparingTo("0");
            assertThat(gross.getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
        }
    }

    @Test
    @DisplayName("AW39: a credit note's net and tax carry its sign, like its total")
    void creditNoteNetAndTaxAreNegative() {
        listener.onSupplierEvent(event(EVENT_1, "CN-1", "CREDIT_NOTE", "288.00"));

        VendorBill created = captured();
        assertThat(created.getTotalAmount()).isEqualByComparingTo("-288.00");
        assertThat(created.getNetAmount()).isEqualByComparingTo("-240.00");
        assertThat(created.getTaxAmount()).isEqualByComparingTo("-48.00");
    }

    @Test
    @DisplayName("a re-issue for a different amount is flagged, not silently overwritten")
    void reissueAtADifferentAmountIsFlagged() {
        VendorBill existing = new VendorBill();
        existing.setTotalAmount(new BigDecimal("288.00"));
        existing.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
        liveOriginal("INV1", existing);

        listener.onSupplierEvent(event(EVENT_7, "INV-1", "INVOICE", "412.00"));

        // Overwriting would erase a change somebody may already have approved against.
        assertThat(existing.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
        assertThat(existing.getTotalAmount()).isEqualByComparingTo("288.00");
        // #2433: flagging acted on the bill, so the fact is recorded as new, with nothing posted.
        verify(ingestionRecorder)
                .record(any(), any(), eq(EVENT_7), any(), any(), any(), eq(new FactPostingOutcome.NothingToPost()));
    }

    @Test
    @DisplayName("a redelivered event changes nothing")
    void replayIsANoOp() {
        when(processedEventRepository.existsById(EVENT_1)).thenReturn(true);

        listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "288.00"));

        verify(vendorBillRepository, never()).save(any());
        verify(vendorBillRepository, never()).saveAndFlush(any());
        verify(processedEventRepository, never()).save(any());
        verifyNoInteractions(ingestionRecorder);
    }

    @Test
    @DisplayName("transient database trouble is retried, not swallowed")
    void transientFailureIsRethrown() {
        when(vendorBillRepository.saveAndFlush(any())).thenThrow(new QueryTimeoutException("statement timed out"));

        assertThatThrownBy(() -> listener.onSupplierEvent(event(EVENT_8, "INV-8", "INVOICE", "288.00")))
                .isInstanceOf(QueryTimeoutException.class);

        // The supplier side has already published this invoice and will not publish it again;
        // marking it processed would lose a vendor debt permanently.
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("lost-connection database trouble is retried, not swallowed")
    void lostConnectionFailureIsRethrown() {
        when(vendorBillRepository.saveAndFlush(any()))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));

        assertThatThrownBy(() -> listener.onSupplierEvent(event(EVENT_8, "INV-8", "INVOICE", "288.00")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        // The supplier side has already published this invoice and will not publish it again;
        // marking it processed would lose a vendor debt permanently.
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a transaction that cannot commit is retried, not marked processed (#2355)")
    void transactionFailureIsRethrownBeforeTheMark() {
        // Not a DataAccessException, so the catch above the mark does not see it: before #2355 it
        // fell into the "malformed" path and the event was recorded as processed.
        when(vendorBillRepository.saveAndFlush(any())).thenThrow(new TransactionSystemException("could not commit"));

        assertThatThrownBy(() -> listener.onSupplierEvent(event(EVENT_8, "INV-8", "INVOICE", "288.00")))
                .isInstanceOf(TransactionSystemException.class);

        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("an invoice with no readable total is flagged, not filed as a nil bill")
    void absentTotalIsFlaggedNotZero() {
        String noTotal = """
            {"eventId":"%s","eventType":"supplier.invoice.received","payload":{
              "vendorProfileId":"%s","supplierRef":"michelin-de","vendorInvoiceNumber":"INV-X",
              "invoiceDate":"2026-08-14","type":"INVOICE","currency":"USD",
              "totalNetAmount":null,"totalTaxAmount":null,"totalGrossAmount":null,
              "vendorOrderReference":null,"occurredAt":"2026-08-16T08:00:00Z","lines":[],
              "vendorId":"%s"}}
            """.formatted(EVENT_1, PROFILE, VENDOR);

        listener.onSupplierEvent(noTotal);

        // The codec records an unreadable amount as absent precisely so it stays distinguishable
        // from a nil invoice. Filing it as a zero-value bill would undo that: it looks settled,
        // sits at the bottom of every ageing report, and is noticed when the vendor chases payment.
        assertThat(captured().getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
    }

    @Test
    @DisplayName("a non-transient database failure is retried, not acknowledged")
    void nonTransientDatabaseFailureIsRethrown() {
        when(vendorBillRepository.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("value too long"));

        assertThatThrownBy(() -> listener.onSupplierEvent(event(EVENT_2, "INV-2", "INVOICE", "288.00")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        // A column-length violation is not a malformed message. Acknowledging it would lose a
        // vendor debt permanently: the supplier side has already published this invoice and will
        // not publish it again.
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("#2439: a recorder failure is retried, not marked processed over the rolled-back bill")
    void recorderFailureIsRethrownUnmarked() {
        // Neither a DataAccessException nor retryable: before #2439 the generic catch filed it as a
        // malformed message and marked the event processed, losing the bill and its record.
        org.mockito.Mockito.doThrow(new IllegalStateException("recorder broke"))
                .when(ingestionRecorder)
                .record(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> listener.onSupplierEvent(event(EVENT_9, "INV-9", "INVOICE", "288.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recorder broke");

        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("#2439: a recorder integrity violation is retried, not marked processed")
    void recorderIntegrityViolationIsRethrownUnmarked() {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"))
                .when(ingestionRecorder)
                .record(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> listener.onSupplierEvent(event(EVENT_9, "INV-9", "INVOICE", "288.00")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        verify(processedEventRepository, never()).save(any());
    }

    @Nested
    @DisplayName("#2501: one duplicate rule (vendor, normalised invoice number, invoice date)")
    class DuplicateRule {

        private static final UUID ORIGINAL_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7c01");

        private static final String INDEX_VIOLATION = "could not execute statement [ERROR: duplicate key value"
                + " violates unique constraint \"uq_vendor_bill_duplicate_rule\"]";

        private VendorBill held(String amount, VendorBillStatus status) {
            VendorBill bill = new VendorBill(ORIGINAL_ID);
            bill.setVendorId(VENDOR);
            bill.setBillNumber("INV-1");
            bill.setBillDate(DAY);
            bill.setTotalAmount(new BigDecimal(amount));
            bill.setCurrency("USD");
            bill.setStatus(status);
            return bill;
        }

        @Test
        @DisplayName("criterion 8: the same invoice, written differently, is recorded as ignored against the original")
        void identicalDuplicateIsIgnoredAgainstTheOriginal() {
            VendorBill original = held("100.00", VendorBillStatus.APPROVED);
            liveOriginal("INV1", original);

            listener.onSupplierEvent(event(EVENT_1, "inv-1", "INVOICE", "100.00"));

            verify(vendorBillRepository, never()).saveAndFlush(any());
            verify(vendorBillRepository, never()).save(any());
            assertThat(original.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
            verify(ingestionRecorder)
                    .record(
                            eq("pos-supplier"),
                            eq("supplier.invoice.received"),
                            eq(EVENT_1),
                            eq(ORIGINAL_ID),
                            any(),
                            any(),
                            eq(new FactPostingOutcome.AlreadyPosted(null, null)));
            verify(processedEventRepository).save(any());
        }

        @Test
        @DisplayName("criterion 9: a duplicate at another amount flags the original and names both amounts")
        void changedDuplicateFlagsTheOriginal() {
            VendorBill original = held("100.00", VendorBillStatus.PENDING_RECEIPT_MATCH);
            liveOriginal("INV1", original);

            listener.onSupplierEvent(event(EVENT_1, "inv-1", "INVOICE", "120.00"));

            assertThat(original.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            // "120.0": the fact's amount as the envelope tree carries it; the flagging text is unchanged.
            assertThat(original.getRejectionReason()).contains("120.0 USD").contains("100.00 USD");
            assertThat(original.getTotalAmount()).isEqualByComparingTo("100.00");
            verify(vendorBillRepository, never()).saveAndFlush(any());
            verify(ingestionRecorder)
                    .record(
                            any(),
                            any(),
                            eq(EVENT_1),
                            eq(ORIGINAL_ID),
                            any(),
                            any(),
                            eq(new FactPostingOutcome.NothingToPost()));
            verify(processedEventRepository).save(any());
        }

        @Test
        @DisplayName("B-MAJ3: the original is locked and re-read first; one voided meanwhile is retried, never flagged")
        void originalDecidedMeanwhileIsRetried() {
            VendorBill found = held("100.00", VendorBillStatus.APPROVED);
            liveOriginal("INV1", found);
            when(locks.lock(found)).thenAnswer(inv -> {
                found.setStatus(VendorBillStatus.VOIDED);
                return found;
            });

            assertThatThrownBy(() -> listener.onSupplierEvent(event(EVENT_1, "inv-1", "INVOICE", "120.00")))
                    .isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
            verify(reissueRepository, never()).save(any());
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("L5: a bill awaiting approval re-issued at another amount goes to MATCH_EXCEPTION without its"
                + " submission, proposal or difference")
        void reissueOfABillAwaitingApprovalClearsItsSubmission() {
            VendorBill original = held("100.00", VendorBillStatus.AWAITING_APPROVAL);
            original.setSubmittedAt(NOW);
            original.setSubmittedBy("clerk.ana");
            original.setSubmissionJustification("Checked with the vendor");
            original.setProposedDebitClass(com.positivity.accounting.internal.enums.VendorBillDebitClass.GOODS);
            original.setProposedExpenseMappingKey("EXPENSE_SHOP_SUPPLIES");
            original.setDifferenceClass(com.positivity.accounting.internal.enums.VendorBillDifferenceClass.FREIGHT);
            original.setDifferenceJustification("Freight on the invoice");
            liveOriginal("INV1", original);

            listener.onSupplierEvent(event(EVENT_1, "inv-1", "INVOICE", "120.00"));

            assertThat(original.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            assertThat(original.getSubmittedAt()).isNull();
            assertThat(original.getSubmittedBy()).isNull();
            assertThat(original.getSubmissionJustification()).isNull();
            assertThat(original.getProposedDebitClass()).isNull();
            assertThat(original.getProposedExpenseMappingKey()).isNull();
            assertThat(original.getDifferenceClass()).isNull();
            assertThat(original.getDifferenceJustification()).isNull();
        }

        @Test
        @DisplayName("AC14 (#2509): an APPROVED bill re-issued at another amount stays APPROVED with its approval, and"
                + " one exception item linked to it records both amounts")
        void reissueOfAnApprovedBillIsAnExceptionItem() {
            VendorBill original = held("100.00", VendorBillStatus.APPROVED);
            original.setApprovedBy("controller.cfo");
            original.setApprovedAt(NOW);
            original.setApprovalJustification("Checked against the delivery note");
            liveOriginal("INV1", original);

            listener.onSupplierEvent(event(EVENT_1, "inv-1", "INVOICE", "120.00"));

            assertThat(original.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
            assertThat(original.getApprovedBy()).isEqualTo("controller.cfo");
            assertThat(original.getApprovedAt()).isEqualTo(NOW);
            assertThat(original.getApprovalJustification()).isEqualTo("Checked against the delivery note");
            assertThat(original.getRejectionReason()).isNull();
            assertThat(original.getTotalAmount()).isEqualByComparingTo("100.00");
            verify(vendorBillRepository, never()).save(any());
            verify(vendorBillRepository, never()).saveAndFlush(any());
            ArgumentCaptor<VendorBillReissue> item = ArgumentCaptor.forClass(VendorBillReissue.class);
            verify(reissueRepository).save(item.capture());
            assertThat(item.getValue().getVendorBillId()).isEqualTo(ORIGINAL_ID);
            assertThat(item.getValue().getIncomingBillNumber()).isEqualTo("inv-1");
            assertThat(item.getValue().getIncomingBillDate()).isEqualTo(DAY.toLocalDate());
            assertThat(item.getValue().getIncomingAmount()).isEqualByComparingTo("120.00");
            assertThat(item.getValue().getIncomingCurrencyCode()).isEqualTo("USD");
            assertThat(item.getValue().getHeldAmount()).isEqualByComparingTo("100.00");
            assertThat(item.getValue().getHeldCurrencyCode()).isEqualTo("USD");
            assertThat(item.getValue().getSourceEventId()).isEqualTo(UUID.fromString(EVENT_1));
            assertThat(item.getValue().getCreatedAt())
                    .as("ADR-0024: auditing stamps createdAt on insert (VendorBillDuplicateRulePostgresIT)")
                    .isNull();
        }

        @Test
        @DisplayName("AC14: a redelivered re-issue of an approved bill records no second exception item")
        void redeliveredReissueOfAnApprovedBillRecordsOneItem() {
            liveOriginal("INV1", held("100.00", VendorBillStatus.APPROVED));
            when(reissueRepository.existsBySourceEventId(UUID.fromString(EVENT_1)))
                    .thenReturn(true);

            listener.onSupplierEvent(event(EVENT_1, "inv-1", "INVOICE", "120.00"));

            verify(reissueRepository, never()).save(any());
        }

        @Test
        @DisplayName(
                "criterion 10: when the rule answers that no live bill holds the number, the fact becomes a new bill")
        void factWithNoLiveOriginalBecomesANewBill() {
            // Which bills count as live (not VOIDED, not REJECTED) is the query's business, pinned on
            // Postgres by VendorBillDuplicateRulePostgresIT#reissueAfterAVoidOrRejectionBecomesANewBill.
            // What the listener owes is to ask the rule and, on "none", to create and touch nothing else.
            listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "100.00"));

            verify(vendorBillRepository).findLiveDuplicate(VENDOR, "INV1", DAY, DAY.plusDays(1), null);
            VendorBill created = captured();
            assertThat(created.getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
            assertThat(created.getBillNumberKey()).isEqualTo("INV1");
            // save() is the flagging path's write to an existing bill: no existing bill was written.
            verify(vendorBillRepository, never()).save(any());
        }

        @Test
        @DisplayName("a flagged duplicate logs one WARN, an ignored one none (#2501: one WARN per refusal or flag)")
        void flaggedDuplicateWarnsOnceAndIgnoredDuplicateNotAtAll() {
            Logger serviceLoggers = (Logger) LoggerFactory.getLogger("com.positivity.accounting.internal.service");
            ListAppender<ILoggingEvent> captured = new ListAppender<>();
            captured.start();
            serviceLoggers.addAppender(captured);
            try {
                liveOriginal("INV1", held("100.00", VendorBillStatus.PENDING_RECEIPT_MATCH));
                listener.onSupplierEvent(event(EVENT_1, "inv-1", "INVOICE", "120.00"));

                assertThat(captured.list)
                        .filteredOn(logged -> logged.getLevel() == Level.WARN)
                        .singleElement()
                        .satisfies(warned -> assertThat(warned.getFormattedMessage())
                                .contains("channel=edi")
                                .contains("outcome=flagged")
                                .contains("key=INV1")
                                .contains(ORIGINAL_ID.toString()));

                captured.list.clear();
                liveOriginal("INV1", held("100.00", VendorBillStatus.APPROVED));
                listener.onSupplierEvent(event(EVENT_2, "inv-1", "INVOICE", "100.00"));

                assertThat(captured.list)
                        .as("overlapping fetch windows republish by design")
                        .noneMatch(logged -> logged.getLevel().isGreaterOrEqual(Level.WARN));
            } finally {
                serviceLoggers.detachAppender(captured);
            }
        }

        @Test
        @DisplayName("criterion 11 (BR-3): the same number on another date is asked of that date, and is a new bill")
        void sameNumberOnAnotherDateIsANewBill() {
            // Last year's bill is live, on its own date; the rule is asked about this invoice's date.
            VendorBill lastYear = held("100.00", VendorBillStatus.PAID);
            LocalDateTime lastYearDay = LocalDateTime.of(2025, 10, 1, 0, 0);
            when(vendorBillRepository.findLiveDuplicate(VENDOR, "INV1", lastYearDay, lastYearDay.plusDays(1), null))
                    .thenReturn(Optional.of(lastYear));

            listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "100.00", "USD", "2026-10-01"));

            LocalDateTime thisYearDay = LocalDateTime.of(2026, 10, 1, 0, 0);
            verify(vendorBillRepository).findLiveDuplicate(VENDOR, "INV1", thisYearDay, thisYearDay.plusDays(1), null);
            assertThat(captured().getBillDate()).isEqualTo(thisYearDay);
            assertThat(lastYear.getStatus()).isEqualTo(VendorBillStatus.PAID);
        }

        @Test
        @DisplayName("criterion 12: an insert that loses the race runs the handler once more and records the duplicate")
        void collisionRunsTheHandlerOnceMore() {
            VendorBill original = held("100.00", VendorBillStatus.PENDING_RECEIPT_MATCH);
            // The check sees nothing, the competing writer commits, the second run's check sees its bill.
            when(vendorBillRepository.findLiveDuplicate(VENDOR, "INV1", DAY, DAY.plusDays(1), null))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(original));
            when(vendorBillRepository.saveAndFlush(any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(INDEX_VIOLATION));

            listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "100.00"));

            verify(vendorBillRepository, times(1)).saveAndFlush(any());
            verify(vendorBillRepository, times(2)).findLiveDuplicate(VENDOR, "INV1", DAY, DAY.plusDays(1), null);
            verify(ingestionRecorder)
                    .record(
                            any(),
                            any(),
                            eq(EVENT_1),
                            eq(ORIGINAL_ID),
                            any(),
                            any(),
                            eq(new FactPostingOutcome.AlreadyPosted(null, null)));
            verify(processedEventRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("criterion 12: a second collision is rethrown for retry, unmarked")
        void secondCollisionIsRethrownUnmarked() {
            when(vendorBillRepository.saveAndFlush(any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(INDEX_VIOLATION));

            assertThatThrownBy(() -> listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "100.00")))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_vendor_bill_duplicate_rule");

            verify(vendorBillRepository, times(2)).saveAndFlush(any());
            verify(processedEventRepository, never()).save(any());
            verifyNoInteractions(ingestionRecorder);
        }

        @Test
        @DisplayName("criterion 12: any other integrity violation is rethrown at once, unmarked, with no second run")
        void anotherIntegrityViolationIsNotRunAgain() {
            when(vendorBillRepository.saveAndFlush(any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "duplicate key value violates unique constraint \"vendor_bill_pkey\""));

            assertThatThrownBy(() -> listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "100.00")))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

            verify(vendorBillRepository, times(1)).saveAndFlush(any());
            verify(processedEventRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the invoice's currency (ADR-0067 DF-1, #2309)")
    class Currency {

        @Test
        @DisplayName("a ledger-currency invoice records its currency and waits for its receipt as before")
        void ledgerCurrencyInvoiceIsPending() {
            listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "288.00", "USD"));

            VendorBill bill = captured();
            assertThat(bill.getCurrency()).isEqualTo("USD");
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
        }

        @Test
        @DisplayName("an invoice in another currency is held with a currency reason, never booked at par")
        void foreignCurrencyInvoiceIsHeld() {
            listener.onSupplierEvent(event(EVENT_9, "INV-9", "INVOICE", "288.00", "EUR"));

            VendorBill bill = captured();
            assertThat(bill.getCurrency()).isEqualTo("EUR");
            assertThat(bill.getStatus())
                    .isEqualTo(VendorBillStatus.CURRENCY_HOLD)
                    .isNotEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
            assertThat(bill.getRejectionReason()).contains("EUR").contains("USD");
            assertThat(bill.getTotalAmount()).isEqualByComparingTo("288.00");
        }

        @Test
        @DisplayName("a re-issue under the same number in a different currency is flagged")
        void reissueInADifferentCurrencyIsFlagged() {
            VendorBill existing = new VendorBill();
            existing.setTotalAmount(new BigDecimal("288.00"));
            existing.setCurrency("USD");
            existing.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
            liveOriginal("INV1", existing);

            listener.onSupplierEvent(event(EVENT_7, "INV-1", "INVOICE", "288.00", "CAD"));

            assertThat(existing.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            assertThat(existing.getCurrency()).isEqualTo("USD");
            assertThat(existing.getRejectionReason()).contains("CAD");
        }

        @Test
        @DisplayName("a re-issue of a held bill keeps it held, never releasing it to the exception queue")
        void reissueOfAHeldBillStaysHeld() {
            VendorBill existing = new VendorBill();
            existing.setTotalAmount(new BigDecimal("288.00"));
            existing.setCurrency("EUR");
            existing.setStatus(VendorBillStatus.CURRENCY_HOLD);
            liveOriginal("INV1", existing);

            listener.onSupplierEvent(event(EVENT_7, "INV-1", "INVOICE", "412.00", "EUR"));

            assertThat(existing.getStatus()).isEqualTo(VendorBillStatus.CURRENCY_HOLD);
            assertThat(existing.getTotalAmount()).isEqualByComparingTo("288.00");
        }

        /**
         * The held bill keeps its original currency and amount. Moving it to MATCH_EXCEPTION on a currency
         * change would put a EUR bill in the queue a person approves from (and approval posts it, AW37,
         * AW43), so it stays held, with the change in its reason.
         */
        @Test
        @DisplayName("a held bill re-issued in yet another currency stays held and names both currencies")
        void reissueOfAHeldBillInAnotherCurrencyStaysHeld() {
            VendorBill existing = new VendorBill();
            existing.setTotalAmount(new BigDecimal("288.00"));
            existing.setCurrency("EUR");
            existing.setStatus(VendorBillStatus.CURRENCY_HOLD);
            liveOriginal("INV1", existing);

            listener.onSupplierEvent(event(EVENT_7, "INV-1", "INVOICE", "288.00", "CAD"));

            assertThat(existing.getStatus()).isEqualTo(VendorBillStatus.CURRENCY_HOLD);
            assertThat(existing.getCurrency()).isEqualTo("EUR");
            assertThat(existing.getRejectionReason()).contains("EUR").contains("CAD");
        }
    }

    // ---- CAP:550 S32d item 10, AC 9: every bill stores its stated tax by type (G11) ---------------------------

    private static String withTaxes(String event, String taxes) {
        return event.replace("\"vendorId\"", "\"taxes\":" + taxes + ",\"vendorId\"");
    }

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> storedTaxByType() {
        ArgumentCaptor<Map<String, BigDecimal>> byType = ArgumentCaptor.forClass(Map.class);
        verify(statedTax).storeFromDocument(any(VendorBill.class), byType.capture());
        return byType.getValue();
    }

    @Test
    @DisplayName("S32d AC 9: an EDI invoice stores its tax by type as stated, whatever the tenant")
    void ediInvoiceStoresItsTaxByType() {
        listener.onSupplierEvent(withTaxes(
                event(EVENT_1, "INV-1", "INVOICE", "USD", "2026-08-14", "1120.00", "1000.00", "120.00", "[]"),
                "[{\"taxType\":\"GST\",\"amount\":50.00},{\"taxType\":\"PST\",\"amount\":70.00}]"));

        assertThat(captured().getNetAmount()).isEqualByComparingTo("1000.00");
        assertThat(storedTaxByType())
                .containsExactly(Map.entry("GST", new BigDecimal("50.00")), Map.entry("PST", new BigDecimal("70.00")));
    }

    @Test
    @DisplayName("S32d AC 9: a credit note's tax by type is signed like its total; a type stated twice is added up")
    void creditNoteTaxByTypeIsSigned() {
        listener.onSupplierEvent(withTaxes(
                event(EVENT_1, "CN-1", "CREDIT_NOTE", "USD", "2026-08-14", "105.00", "100.00", "5.00", "[]"),
                "[{\"taxType\":\"GST\",\"amount\":2.00},{\"taxType\":\"GST\",\"amount\":3.00}]"));

        assertThat(storedTaxByType()).containsExactly(Map.entry("GST", new BigDecimal("-5.00")));
    }

    @Test
    @DisplayName("S32d AC 9: an invoice without tax by type stores none")
    void invoiceWithoutTaxByType() {
        listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "288.00"));

        verify(statedTax).storeFromDocument(any(VendorBill.class), org.mockito.ArgumentMatchers.isNull());
    }
}

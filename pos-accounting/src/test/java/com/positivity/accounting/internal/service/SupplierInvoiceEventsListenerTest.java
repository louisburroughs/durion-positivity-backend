package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.positivity.accounting.internal.entity.Vendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.repository.VendorRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    @Mock
    private KafkaFactIngestionRecorder ingestionRecorder;

    private SupplierInvoiceEventsListener listener;

    @BeforeEach
    void setUp() {
        ObjectProvider<MeterRegistry> noMeters = mock();
        listener = new SupplierInvoiceEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(),
                processedEventRepository,
                vendorBillRepository,
                vendorRepository,
                new LedgerCurrency("USD"),
                ingestionRecorder,
                // The real guard over the mocked repository: the listener's lookup is the rule's query.
                new VendorBillDuplicateGuard(vendorBillRepository, noMeters),
                mock(PlatformTransactionManager.class));
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(vendorBillRepository.findLiveDuplicate(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(vendorRepository.existsById(any())).thenReturn(false);
    }

    private static String event(String eventId, String number, String type, String total) {
        return event(eventId, number, type, total, "USD");
    }

    private static String event(String eventId, String number, String type, String total, String currency) {
        return event(eventId, number, type, total, currency, "2026-08-14");
    }

    private static String event(
            String eventId, String number, String type, String total, String currency, String invoiceDate) {
        return """
            {"eventId":"%s","eventType":"supplier.invoice.received","payload":{
              "vendorProfileId":"%s","supplierRef":"michelin-de","vendorInvoiceNumber":"%s",
              "invoiceDate":"%s","type":"%s","currency":"%s",
              "totalNetAmount":240.00,"totalTaxAmount":48.00,"totalGrossAmount":%s,
              "vendorOrderReference":"PO-778","occurredAt":"2026-08-16T08:00:00Z","lines":[]}}
            """.formatted(eventId, PROFILE, number, invoiceDate, type, currency, total);
    }

    /** The rule's window for the default invoice date, 2026-08-14. */
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 8, 14, 0, 0);

    private void liveOriginal(String key, VendorBill original) {
        when(vendorBillRepository.findLiveDuplicate(PROFILE, key, DAY, DAY.plusDays(1), null))
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
        assertThat(SupplierInvoiceEventsListener.RECORDED_EVENT_TYPES).containsExactly("supplier.invoice.received");
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
              "vendorOrderReference":null,"occurredAt":"2026-08-16T08:00:00Z","lines":[]}}
            """.formatted(EVENT_1, PROFILE);

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
            bill.setVendorId(PROFILE);
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
        @DisplayName(
                "criterion 10: when the rule answers that no live bill holds the number, the fact becomes a new bill")
        void factWithNoLiveOriginalBecomesANewBill() {
            // Which bills count as live (not VOIDED, not REJECTED) is the query's business, pinned on
            // Postgres by VendorBillDuplicateRulePostgresIT#reissueAfterAVoidOrRejectionBecomesANewBill.
            // What the listener owes is to ask the rule and, on "none", to create and touch nothing else.
            listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "100.00"));

            verify(vendorBillRepository).findLiveDuplicate(PROFILE, "INV1", DAY, DAY.plusDays(1), null);
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
            when(vendorBillRepository.findLiveDuplicate(PROFILE, "INV1", lastYearDay, lastYearDay.plusDays(1), null))
                    .thenReturn(Optional.of(lastYear));

            listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "100.00", "USD", "2026-10-01"));

            LocalDateTime thisYearDay = LocalDateTime.of(2026, 10, 1, 0, 0);
            verify(vendorBillRepository).findLiveDuplicate(PROFILE, "INV1", thisYearDay, thisYearDay.plusDays(1), null);
            assertThat(captured().getBillDate()).isEqualTo(thisYearDay);
            assertThat(lastYear.getStatus()).isEqualTo(VendorBillStatus.PAID);
        }

        @Test
        @DisplayName("criterion 12: an insert that loses the race runs the handler once more and records the duplicate")
        void collisionRunsTheHandlerOnceMore() {
            VendorBill original = held("100.00", VendorBillStatus.PENDING_RECEIPT_MATCH);
            // The check sees nothing, the competing writer commits, the second run's check sees its bill.
            when(vendorBillRepository.findLiveDuplicate(PROFILE, "INV1", DAY, DAY.plusDays(1), null))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(original));
            when(vendorBillRepository.saveAndFlush(any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(INDEX_VIOLATION));

            listener.onSupplierEvent(event(EVENT_1, "INV-1", "INVOICE", "100.00"));

            verify(vendorBillRepository, times(1)).saveAndFlush(any());
            verify(vendorBillRepository, times(2)).findLiveDuplicate(PROFILE, "INV1", DAY, DAY.plusDays(1), null);
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
         * The held bill keeps its original currency and amount, and MATCH_EXCEPTION resolves by
         * ACCEPT straight to APPROVED with no currency check (VendorBillServiceImpl
         * #resolveMatchException). Moving it there on a currency change would let a EUR bill be
         * approved as a ledger payable at par, so it stays held, with the change in its reason.
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
}

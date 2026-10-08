package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillMatchCandidate;
import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import com.positivity.accounting.internal.enums.MatchConfidence;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.InvalidDateRangeException;
import com.positivity.accounting.internal.exception.VendorBillDuplicateException;
import com.positivity.accounting.internal.exception.VendorBillMatchNotFoundException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchEvidenceRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Unit tests for VendorBillServiceImpl.
 * Covers invoice matching (#2509: a HIGH match goes to approval and no match writes an approval field, G12; the
 * billed amounts and the evidence are kept, AW39) and bill retrieval. The decisions are VendorBillApprovalServiceTest's.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VendorBillService Unit Tests")
class VendorBillServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = FIXED_CLOCK;

    @Spy
    AccountingCalendarZoneResolver zoneResolver = TestZoneResolvers.utc(FIXED_CLOCK);

    @Mock
    private VendorBillRepository billRepository;

    @Mock
    private VendorBillLineRepository billLineRepository;

    @Mock
    private VendorBillMatchCandidateRepository matchCandidateRepository;

    @Mock
    private VendorBillMatchEvidenceRepository evidenceRepository;

    @Mock
    private AccountingAuditLogRepository auditLogs;

    @Mock
    private VendorBillReader reader;

    @Mock
    private VendorBillLocks locks;

    @Mock
    private VendorDirectoryService vendorDirectoryService;

    /** A mock answers "no duplicate"; the {@link DuplicateRule} tests build a service over a real guard. */
    @Mock
    private VendorBillDuplicateGuard duplicateGuard;

    /** The goods-receipt create runs in a TransactionTemplate (#2501); a mock manager just runs it. */
    @Mock
    private PlatformTransactionManager transactionManager;

    /** The per-tenant counter goods-receipt bill numbers are drawn from (ADR-0062 section 9). */
    @Mock
    private AccountingSequenceLocker sequenceLocker;

    private VendorBillServiceImpl vendorBillService;

    private UUID testVendorId;
    private UUID testBillId;
    private UUID testProductId1;
    private UUID testProductId2;

    // Invoice date: 2026-01-15
    private static final LocalDateTime INVOICE_DATE = LocalDateTime.of(2026, 1, 15, 12, 0);
    // Bill date within 7 days → 20 pts
    private static final LocalDateTime BILL_DATE_CLOSE = LocalDateTime.of(2026, 1, 16, 12, 0);
    // Bill date 10 days away → 10 pts
    private static final LocalDateTime BILL_DATE_MEDIUM = LocalDateTime.of(2026, 1, 5, 12, 0);

    @BeforeEach
    void setUp() {
        when(reader.read(any(VendorBill.class))).thenAnswer(inv -> response(inv.getArgument(0)));
        // The lock re-reads the bill as it is now; unchanged unless a test says otherwise.
        when(locks.lock(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceRepository.save(any(VendorBillMatchEvidence.class))).thenAnswer(inv -> inv.getArgument(0));
        vendorBillService = service(duplicateGuard);
        testVendorId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        testBillId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        testProductId1 = UUID.fromString("00000000-0000-0000-0000-000000000011");
        testProductId2 = UUID.fromString("00000000-0000-0000-0000-000000000021");
    }

    // ========================================
    // handleGoodsReceivedEvent - Idempotency
    // ========================================

    @Test
    @DisplayName("handleGoodsReceivedEvent should return existing bill for duplicate event")
    void handleGoodsReceivedEvent_DuplicateEvent_ReturnsExisting() {
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000031");
        VendorBill existingBill = buildBill(
                testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
        existingBill.setOriginEventId(eventId);

        when(billRepository.findByOriginEventId(eventId)).thenReturn(Optional.of(existingBill));

        GoodsReceivedEvent event = buildGoodsReceivedEvent(eventId);
        VendorBillResponse result = vendorBillService.handleGoodsReceivedEvent(event);

        assertThat(result).isNotNull();
        assertThat(result.getVendorBillId()).isEqualTo(testBillId);
        // No new bill saved
        verify(billRepository).findByOriginEventId(eventId);
    }

    // ========================================
    // handleVendorInvoiceReceivedEvent Tests
    // ========================================

    @Nested
    @DisplayName("handleVendorInvoiceReceivedEvent")
    class HandleVendorInvoiceTests {

        @Test
        @DisplayName("NO_MATCH: should throw VendorBillMatchNotFoundException when no pending bills found")
        void noMatch_ThrowsException() {
            when(billRepository.findByVendorIdAndStatus(testVendorId, VendorBillStatus.PENDING_RECEIPT_MATCH))
                    .thenReturn(List.of());

            VendorInvoiceReceivedEvent event = buildInvoiceEvent(testVendorId, new BigDecimal("1300.00"));

            assertThatThrownBy(() -> vendorBillService.handleVendorInvoiceReceivedEvent(event))
                    .isInstanceOf(VendorBillMatchNotFoundException.class)
                    .hasMessageContaining("No pending receipt found");
        }

        @Test
        @DisplayName("AC2: a HIGH match goes to AWAITING_APPROVAL, submitted by SYSTEM, with no approval field and its"
                + " evidence (score 95: amount 40, products 30, date 20, purchase order 5)")
        void highConfidenceMatch_GoesToApproval() {
            VendorBill bill = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
            bill.setPurchaseOrderId(UUID.randomUUID()); // +5 pts
            pending(bill);
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(testBillId))
                    .thenReturn(receivedLines(testBillId));

            VendorBillResponse result = vendorBillService.handleVendorInvoiceReceivedEvent(
                    buildInvoiceEvent(testVendorId, new BigDecimal("1300.00")));

            assertThat(result.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
            assertThat(bill.getSubmittedBy()).isEqualTo("SYSTEM");
            assertThat(bill.getSubmittedAt()).isEqualTo(FIXED_CLOCK.instant());
            assertNoApprovalField(bill);
            assertThat(bill.getBillNumber()).isEqualTo("INV-2026-001");
            assertThat(bill.getDueDate()).isEqualTo(LocalDateTime.of(2026, 2, 15, 0, 0));
            VendorBillMatchEvidence evidence = savedEvidence();
            assertThat(evidence.getConfidence()).isEqualTo(MatchConfidence.HIGH_CONFIDENCE);
            assertThat(evidence.getScore()).isEqualTo(95);
            assertThat(List.of(
                            evidence.getAmountPoints(),
                            evidence.getProductPoints(),
                            evidence.getDatePoints(),
                            evidence.getPurchaseOrderPoints()))
                    .containsExactly(40, 30, 20, 5);
            assertThat(evidence.isWithinTolerance()).isTrue();
            assertThat(evidence.getSource()).isEqualTo(VendorBillMatchEvidence.Source.MATCH);
            assertThat(evidence.getCurrencyCode()).isEqualTo("USD");
        }

        @Test
        @DisplayName("AW39: a price outside tolerance is MATCH_EXCEPTION; the bill keeps the billed price and total and"
                + " writes no approval field")
        void priceDiscrepancy_KeepsWhatWasBilled() {
            VendorBill bill = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
            bill.setPurchaseOrderId(UUID.randomUUID());
            pending(bill);
            List<VendorBillLine> lines = receivedLines(testBillId);
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(testBillId))
                    .thenReturn(lines);
            VendorInvoiceReceivedEvent event = buildInvoiceEvent(testVendorId, new BigDecimal("1400.00"));
            event.getLineItems().get(0).setUnitPrice(new BigDecimal("13.50")); // 8% over the received 12.50

            VendorBillResponse result = vendorBillService.handleVendorInvoiceReceivedEvent(event);

            assertThat(result.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            assertNoApprovalField(bill);
            assertThat(bill.getSubmittedBy()).isNull();
            assertThat(bill.getTotalAmount()).isEqualByComparingTo("1400.00");
            assertThat(lines.get(0).getBilledQuantity()).isEqualByComparingTo("100.00");
            assertThat(lines.get(0).getBilledUnitPrice()).isEqualByComparingTo("13.50");
            assertThat(lines.get(1).getBilledUnitPrice()).isEqualByComparingTo("50.00");
            VendorBillMatchEvidence evidence = savedEvidence();
            assertThat(evidence.isWithinTolerance()).isFalse();
            assertThat(evidence.getReceivedTotal()).isEqualByComparingTo("1300.00");
            assertThat(evidence.getBilledTotal()).isEqualByComparingTo("1400.00");
            assertThat(evidence.getLineComparison()).hasSize(2);
            assertThat(evidence.getLineComparison().get(0))
                    .containsEntry(VendorBillMatchEvidence.PRICE_WITHIN_TOLERANCE, false);
        }

        @Test
        @DisplayName("AC1: a MEDIUM match is MATCH_EXCEPTION with null approvedBy, approvedAt and"
                + " approvalJustification, and its evidence holds the score and points")
        void mediumConfidenceMatch_SetsMatchException() {
            // Score = amount(40) + date within 30 days(10) + no lines(0) + no PO(0) = 50 → MEDIUM_CONFIDENCE
            VendorBill bill = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_MEDIUM);
            pending(bill);
            // Scoring sees no lines (0 pts); the tolerance check and the billed lines see them.
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(testBillId))
                    .thenReturn(List.of())
                    .thenReturn(receivedLines(testBillId));

            VendorBillResponse result = vendorBillService.handleVendorInvoiceReceivedEvent(
                    buildInvoiceEvent(testVendorId, new BigDecimal("1300.00")));

            assertThat(result.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            assertNoApprovalField(bill);
            VendorBillMatchEvidence evidence = savedEvidence();
            assertThat(evidence.getConfidence()).isEqualTo(MatchConfidence.MEDIUM_CONFIDENCE);
            assertThat(evidence.getScore()).isEqualTo(50);
            assertThat(evidence.getAmountPoints()).isEqualTo(40);
            assertThat(evidence.getDatePoints()).isEqualTo(10);
            verify(auditLogs).save(any());
        }

        @Test
        @DisplayName("AMBIGUOUS: the candidates keep their points and the invoice; the best one is MATCH_EXCEPTION"
                + " with its evidence and no approval field")
        void ambiguousMatch_PersistsCandidates() {
            UUID billId2 = UUID.fromString("00000000-0000-0000-0000-000000000005");
            VendorBill bill1 = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
            bill1.setPurchaseOrderId(UUID.randomUUID());
            VendorBill bill2 = buildBill(
                    billId2, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
            bill2.setPurchaseOrderId(UUID.randomUUID());
            when(billRepository.findByVendorIdAndStatus(testVendorId, VendorBillStatus.PENDING_RECEIPT_MATCH))
                    .thenReturn(List.of(bill1, bill2));
            when(billRepository.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));
            when(matchCandidateRepository.save(any(VendorBillMatchCandidate.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(testBillId))
                    .thenReturn(receivedLines(testBillId));
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(billId2))
                    .thenReturn(receivedLines(billId2));

            VendorBillResponse result = vendorBillService.handleVendorInvoiceReceivedEvent(
                    buildInvoiceEvent(testVendorId, new BigDecimal("1300.00")));

            assertThat(result.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            assertNoApprovalField(bill1);
            ArgumentCaptor<VendorBillMatchCandidate> candidates =
                    ArgumentCaptor.forClass(VendorBillMatchCandidate.class);
            verify(matchCandidateRepository, times(2)).save(candidates.capture());
            VendorBillMatchCandidate first = candidates.getAllValues().get(0);
            assertThat(List.of(
                            first.getAmountPoints(),
                            first.getProductPoints(),
                            first.getDatePoints(),
                            first.getPurchaseOrderPoints()))
                    .containsExactly(40, 30, 20, 5);
            assertThat(first.getInvoiceReference()).isEqualTo("INV-2026-001");
            assertThat(first.getInvoiceTotalAmount()).isEqualByComparingTo("1300.00");
            assertThat(VendorBillInvoiceMatcher.fromJson(first.getInvoiceLines()))
                    .extracting(VendorBillInvoiceMatcher.InvoiceLine::productId)
                    .containsExactly(testProductId1, testProductId2);
            assertThat(savedEvidence().getConfidence()).isEqualTo(MatchConfidence.AMBIGUOUS);
        }
    }

    @Nested
    @DisplayName("#2509 review: the received baseline, the invoice date and the lock")
    class Review {

        @Test
        @DisplayName("AW46(a): a matched bill takes the invoice date; the evidence keeps the receipt date")
        void matchedBillTakesTheInvoiceDate() {
            VendorBill bill = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
            bill.setPurchaseOrderId(UUID.randomUUID());
            pending(bill);
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(testBillId))
                    .thenReturn(receivedLines(testBillId));

            vendorBillService.handleVendorInvoiceReceivedEvent(
                    buildInvoiceEvent(testVendorId, new BigDecimal("1300.00")));

            assertThat(bill.getBillDate()).isEqualTo(INVOICE_DATE);
            VendorBillMatchEvidence evidence = savedEvidence();
            assertThat(evidence.getInvoiceDate()).isEqualTo(INVOICE_DATE);
            assertThat(evidence.getReceivedDate()).isEqualTo(BILL_DATE_CLOSE);
            verify(duplicateGuard)
                    .refuseIfDuplicate(
                            VendorBillDuplicateGuard.Channel.MATCH,
                            testVendorId,
                            "INV-2026-001",
                            INVOICE_DATE,
                            testBillId);
        }

        @Test
        @DisplayName("B-MAJ2: the amount points compare the invoice with the received lines, not a total an earlier"
                + " match left on the bill")
        void amountPointsUseTheReceivedTotal() {
            VendorBill bill = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("9999.00"), BILL_DATE_CLOSE);
            bill.setPurchaseOrderId(UUID.randomUUID());
            pending(bill);
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(testBillId))
                    .thenReturn(receivedLines(testBillId));

            vendorBillService.handleVendorInvoiceReceivedEvent(
                    buildInvoiceEvent(testVendorId, new BigDecimal("1300.00")));

            VendorBillMatchEvidence evidence = savedEvidence();
            assertThat(evidence.getAmountPoints()).isEqualTo(40);
            assertThat(evidence.getReceivedTotal()).isEqualByComparingTo("1300.00");
            assertThat(evidence.isWithinTolerance()).isTrue();
        }

        @Test
        @DisplayName("B-MAJ2: an EDI bill is never a match candidate")
        void ediBillIsNoCandidate() {
            VendorBill edi = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
            edi.setOriginEventType(VendorBillReader.ORIGIN_SUPPLIER_INVOICE);
            pending(edi);

            assertThatThrownBy(() -> vendorBillService.handleVendorInvoiceReceivedEvent(
                            buildInvoiceEvent(testVendorId, new BigDecimal("1300.00"))))
                    .isInstanceOf(VendorBillMatchNotFoundException.class);
            verify(billRepository, never()).save(any());
        }

        @Test
        @DisplayName("B-MAJ3: a bill decided while the invoice was scored is 409 OPTIMISTIC_LOCK; nothing is written")
        void billDecidedMeanwhileIsRefused() {
            VendorBill bill = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
            bill.setPurchaseOrderId(UUID.randomUUID());
            pending(bill);
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(testBillId))
                    .thenReturn(receivedLines(testBillId));
            when(locks.lock(bill)).thenAnswer(inv -> {
                bill.setStatus(VendorBillStatus.VOIDED);
                return bill;
            });

            assertThatThrownBy(() -> vendorBillService.handleVendorInvoiceReceivedEvent(
                            buildInvoiceEvent(testVendorId, new BigDecimal("1300.00"))))
                    .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class)
                    .hasMessageContaining("VOIDED");
            verify(billRepository, never()).save(any());
            verify(evidenceRepository, never()).save(any());
            verify(matchCandidateRepository, never()).save(any());
        }
    }

    private void pending(VendorBill bill) {
        when(billRepository.findByVendorIdAndStatus(testVendorId, VendorBillStatus.PENDING_RECEIPT_MATCH))
                .thenReturn(List.of(bill));
        when(billRepository.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private List<VendorBillLine> receivedLines(UUID billId) {
        return List.of(
                buildBillLine(billId, testProductId1, new BigDecimal("100.00"), new BigDecimal("12.50")),
                buildBillLine(billId, testProductId2, new BigDecimal("1.00"), new BigDecimal("50.00")));
    }

    private VendorBillMatchEvidence savedEvidence() {
        ArgumentCaptor<VendorBillMatchEvidence> saved = ArgumentCaptor.forClass(VendorBillMatchEvidence.class);
        verify(evidenceRepository).save(saved.capture());
        return saved.getValue();
    }

    /** G12, #2509: no matching path writes an approval field. */
    private static void assertNoApprovalField(VendorBill bill) {
        assertThat(bill.getApprovedBy()).isNull();
        assertThat(bill.getApprovedAt()).isNull();
        assertThat(bill.getApprovalJustification()).isNull();
    }

    // ========================================
    // getBillById and getBillByOriginEventId Tests
    // ========================================

    @Test
    @DisplayName("getBillById should return bill response when bill exists")
    void getBillById_Found() {
        VendorBill bill = buildBill(testBillId, VendorBillStatus.APPROVED, new BigDecimal("1000.00"), BILL_DATE_CLOSE);
        when(billRepository.findById(testBillId)).thenReturn(Optional.of(bill));

        Optional<VendorBillResponse> result = vendorBillService.getBillById(testBillId);

        assertThat(result).isPresent();
        assertThat(result.get().getVendorBillId()).isEqualTo(testBillId);
        assertThat(result.get().getStatus()).isEqualTo(VendorBillStatus.APPROVED);
    }

    @Test
    @DisplayName("getBillById should return empty Optional when bill not found")
    void getBillById_NotFound() {
        when(billRepository.findById(testBillId)).thenReturn(Optional.empty());

        Optional<VendorBillResponse> result = vendorBillService.getBillById(testBillId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getBillByOriginEventId should return bill response when original event found")
    void getBillByOriginEventId_Found() {
        UUID originEventId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        VendorBill bill = buildBill(
                testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
        bill.setOriginEventId(originEventId);
        when(billRepository.findByOriginEventId(originEventId)).thenReturn(Optional.of(bill));

        Optional<VendorBillResponse> result = vendorBillService.getBillByOriginEventId(originEventId);

        assertThat(result).isPresent();
        assertThat(result.get().getOriginEventId()).isEqualTo(originEventId);
    }

    @Test
    @DisplayName("getBillByOriginEventId should return empty Optional when not found")
    void getBillByOriginEventId_NotFound() {
        UUID originEventId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(billRepository.findByOriginEventId(originEventId)).thenReturn(Optional.empty());

        Optional<VendorBillResponse> result = vendorBillService.getBillByOriginEventId(originEventId);

        assertThat(result).isEmpty();
    }

    @Nested
    @DisplayName("listByDueDateWindow (Wave 2 E9, issue #1597)")
    class ListByDueDateWindowTests {

        private final java.time.LocalDate dueFrom = java.time.LocalDate.of(2026, 6, 1);
        private final java.time.LocalDate dueTo = java.time.LocalDate.of(2026, 6, 30);

        @Test
        @DisplayName("Rejects dueTo before dueFrom")
        void rejectsInvalidRange() {
            assertThatThrownBy(() -> vendorBillService.listByDueDateWindow(
                            dueTo, dueFrom, null, org.springframework.data.domain.PageRequest.of(0, 20)))
                    .isInstanceOf(InvalidDateRangeException.class);
        }

        @Test
        @DisplayName("Rejects a window wider than 366 days")
        void rejectsWindowTooWide() {
            java.time.LocalDate wideTo = dueFrom.plusDays(400);

            assertThatThrownBy(() -> vendorBillService.listByDueDateWindow(
                            dueFrom, wideTo, null, org.springframework.data.domain.PageRequest.of(0, 20)))
                    .isInstanceOf(InvalidDateRangeException.class);
        }

        @Test
        @DisplayName("Accepts a window of exactly 366 days")
        void acceptsMaxWindow() {
            java.time.LocalDate maxTo = dueFrom.plusDays(366);
            when(billRepository.findByDueDateBetween(any(), any(), any()))
                    .thenReturn(org.springframework.data.domain.Page.empty());

            vendorBillService.listByDueDateWindow(
                    dueFrom, maxTo, null, org.springframework.data.domain.PageRequest.of(0, 20));

            verify(billRepository).findByDueDateBetween(any(), any(), any());
        }

        @Test
        @DisplayName("With no status filter, delegates to findByDueDateBetween and maps rows")
        void noStatusFilterMapsRows() {
            VendorBill bill = buildBill(
                    testBillId,
                    VendorBillStatus.APPROVED,
                    new BigDecimal("500.00"),
                    LocalDateTime.of(2026, 6, 5, 0, 0));
            bill.setDueDate(LocalDateTime.of(2026, 6, 10, 0, 0));
            when(billRepository.findByDueDateBetween(any(), any(), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(bill)));

            org.springframework.data.domain.Page<com.positivity.accounting.internal.dto.VendorBillListRow> result =
                    vendorBillService.listByDueDateWindow(
                            dueFrom, dueTo, null, org.springframework.data.domain.PageRequest.of(0, 20));

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getBillId()).isEqualTo(testBillId);
            assertThat(result.getContent().get(0).getAmount()).isEqualByComparingTo("500.00");
            assertThat(result.getContent().get(0).getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        }

        @Test
        @DisplayName("Issue #1892: the row carries billNumber and vendorName, not only the identifiers")
        void mapsHumanReadableDisplayValues() {
            VendorBill bill = buildBill(
                    testBillId,
                    VendorBillStatus.APPROVED,
                    new BigDecimal("500.00"),
                    LocalDateTime.of(2026, 6, 5, 0, 0));
            bill.setDueDate(LocalDateTime.of(2026, 6, 10, 0, 0));
            when(billRepository.findByDueDateBetween(any(), any(), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(bill)));

            com.positivity.accounting.internal.dto.VendorBillListRow row = vendorBillService
                    .listByDueDateWindow(dueFrom, dueTo, null, org.springframework.data.domain.PageRequest.of(0, 20))
                    .getContent()
                    .get(0);

            assertThat(row.getBillNumber()).isEqualTo("BILL-001");
            assertThat(row.getVendorName()).isEqualTo("Test Vendor");
            // The identifiers stay in the payload: the frontend routes and follows up on them.
            assertThat(row.getBillId()).isEqualTo(testBillId);
            assertThat(row.getVendorId()).isEqualTo(testVendorId);
        }

        @Test
        @DisplayName("Issue #1892: an unnamed vendor leaves vendorName null rather than substituting the UUID")
        void unnamedVendorLeavesDisplayNameNull() {
            VendorBill bill = buildBill(
                    testBillId,
                    VendorBillStatus.APPROVED,
                    new BigDecimal("500.00"),
                    LocalDateTime.of(2026, 6, 5, 0, 0));
            bill.setDueDate(LocalDateTime.of(2026, 6, 10, 0, 0));
            bill.setVendorName(null);
            when(billRepository.findByDueDateBetween(any(), any(), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(bill)));

            com.positivity.accounting.internal.dto.VendorBillListRow row = vendorBillService
                    .listByDueDateWindow(dueFrom, dueTo, null, org.springframework.data.domain.PageRequest.of(0, 20))
                    .getContent()
                    .get(0);

            assertThat(row.getVendorName()).isNull();
            assertThat(row.getBillNumber()).isEqualTo("BILL-001");
        }

        @Test
        @DisplayName("With a status filter, delegates to findByDueDateBetweenAndStatus")
        void statusFilterDelegatesToFilteredQuery() {
            when(billRepository.findByDueDateBetweenAndStatus(any(), any(), any(), any()))
                    .thenReturn(org.springframework.data.domain.Page.empty());

            vendorBillService.listByDueDateWindow(
                    dueFrom, dueTo, VendorBillStatus.APPROVED, org.springframework.data.domain.PageRequest.of(0, 20));

            verify(billRepository)
                    .findByDueDateBetweenAndStatus(
                            any(), any(), org.mockito.ArgumentMatchers.eq(VendorBillStatus.APPROVED), any());
            verify(billRepository, org.mockito.Mockito.never()).findByDueDateBetween(any(), any(), any());
        }

        @Test
        @DisplayName("Caps the effective page size at the module's hard cap regardless of the requested size")
        void capsPageSize() {
            when(billRepository.findByDueDateBetween(any(), any(), any()))
                    .thenReturn(org.springframework.data.domain.Page.empty());

            vendorBillService.listByDueDateWindow(
                    dueFrom, dueTo, null, org.springframework.data.domain.PageRequest.of(0, 10_000));

            org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCaptor =
                    org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
            verify(billRepository).findByDueDateBetween(any(), any(), pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
        }

        @Test
        @DisplayName("Ignores any caller-supplied sort and enforces dueDate ascending server-side")
        void ignoresCallerSuppliedSort() {
            when(billRepository.findByDueDateBetween(any(), any(), any()))
                    .thenReturn(org.springframework.data.domain.Page.empty());

            vendorBillService.listByDueDateWindow(
                    dueFrom,
                    dueTo,
                    null,
                    org.springframework.data.domain.PageRequest.of(
                            0,
                            20,
                            org.springframework.data.domain.Sort.by(
                                    org.springframework.data.domain.Sort.Direction.DESC, "billDate")));

            org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCaptor =
                    org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
            verify(billRepository).findByDueDateBetween(any(), any(), pageableCaptor.capture());
            org.springframework.data.domain.Sort.Order order =
                    pageableCaptor.getValue().getSort().getOrderFor("dueDate");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(org.springframework.data.domain.Sort.Direction.ASC);
        }
    }

    // ========================================
    // The duplicate rule (#2501)
    // ========================================

    @Nested
    @DisplayName("#2501: one duplicate rule (vendor, normalised bill number, bill date)")
    class DuplicateRule {

        private static final Pattern UUID_PATTERN =
                Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

        private final UUID originalId = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

        private VendorBillServiceImpl service;

        @BeforeEach
        void realGuard() {
            ObjectProvider<MeterRegistry> noMeters = mock();
            service = service(new VendorBillDuplicateGuard(billRepository, noMeters));
            when(billRepository.findLiveDuplicate(any(), any(), any(), any(), any()))
                    .thenReturn(Optional.empty());
        }

        private VendorBill original(String billNumber, LocalDateTime billDate, VendorBillStatus status) {
            VendorBill bill = buildBill(originalId, status, new BigDecimal("1300.00"), billDate);
            bill.setBillNumber(billNumber);
            bill.setVendorName("Acme Tire");
            return bill;
        }

        /** The number the service generates for the test vendor on the fixed clock's date with sequence 7. */
        private static final String GENERATED = "BILL_00000000_20260115_0000007";

        /** The tenant's counter for the fixed clock's month, about to hand out {@code next}. */
        private AccountingSequence counterAt(long next) {
            AccountingSequence counter = new AccountingSequence();
            counter.setScopeKey("BILL-202601");
            counter.setNextValue(next);
            when(sequenceLocker.lockOrProvision("BILL-202601")).thenReturn(counter);
            return counter;
        }

        @Test
        @DisplayName("criterion 4: a goods-receipt bill that repeats a live bill's vendor, key and date is refused")
        void goodsReceiptDuplicateIsRefusedBeforeAnythingIsWritten() {
            UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000032");
            when(billRepository.findByOriginEventId(eventId)).thenReturn(Optional.empty());
            AccountingSequence counter = counterAt(7);
            VendorBill live = original(GENERATED, BILL_DATE_CLOSE.withHour(9), VendorBillStatus.APPROVED);
            when(billRepository.findLiveDuplicate(
                            testVendorId,
                            "BILL00000000202601150000007",
                            LocalDateTime.of(2026, 1, 16, 0, 0),
                            LocalDateTime.of(2026, 1, 17, 0, 0),
                            null))
                    .thenReturn(Optional.of(live));

            assertThatThrownBy(() -> service.handleGoodsReceivedEvent(buildGoodsReceivedEvent(eventId)))
                    .isInstanceOfSatisfying(VendorBillDuplicateException.class, refused -> {
                        assertThat(refused.getOriginalBillId()).isEqualTo(originalId);
                        assertThat(refused.getMessage())
                                .isEqualTo("Bill " + GENERATED
                                        + " from Acme Tire dated 2026-01-16 already exists (APPROVED)");
                        assertThat(UUID_PATTERN.matcher(refused.getMessage()).find())
                                .as("ADR-0064: the message names no id")
                                .isFalse();
                    });

            verify(billRepository, never()).saveAndFlush(any());
            verify(billRepository, never()).save(any());
            verify(billLineRepository, never()).save(any());
            verify(vendorDirectoryService, never()).recordVendorInCurrentTransaction(any(), any());
            // The number was drawn from the tenant's counter in the same transaction as the refused
            // bill; that transaction rolls back, and the increment with it (Postgres IT).
            assertThat(counter.getNextValue()).isEqualTo(8L);
        }

        @Test
        @DisplayName("criterion 4: a bill with no vendor name is described as from this vendor")
        void refusalWithoutAVendorNameSaysThisVendor() {
            VendorBill live = original("INV-00123", LocalDateTime.of(2026, 10, 1, 9, 30), VendorBillStatus.PAID);
            live.setVendorName(null);

            assertThat(new VendorBillDuplicateException(live))
                    .hasMessage("Bill INV-00123 from this vendor dated 2026-10-01 already exists (PAID)");
        }

        @Test
        @DisplayName("criterion 4: an insert refused by the unique index gives the same refusal, with the original")
        void goodsReceiptThatLosesTheRaceIsRefusedWithTheOriginal() {
            UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000033");
            when(billRepository.findByOriginEventId(eventId)).thenReturn(Optional.empty());
            counterAt(7);
            VendorBill live = original(GENERATED, BILL_DATE_CLOSE, VendorBillStatus.PENDING_RECEIPT_MATCH);
            // The pre-check sees nothing; the competing writer commits; the read after the collision sees it.
            when(billRepository.findLiveDuplicate(any(), any(), any(), any(), any()))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(live));
            when(billRepository.saveAndFlush(any(VendorBill.class)))
                    .thenThrow(new DataIntegrityViolationException(
                            "could not execute statement [ERROR: duplicate key value violates unique constraint"
                                    + " \"uq_vendor_bill_duplicate_rule\"]"));

            assertThatThrownBy(() -> service.handleGoodsReceivedEvent(buildGoodsReceivedEvent(eventId)))
                    .isInstanceOfSatisfying(
                            VendorBillDuplicateException.class,
                            refused -> assertThat(refused.getOriginalBillId()).isEqualTo(originalId));

            verify(vendorDirectoryService, never()).recordVendorInCurrentTransaction(any(), any());
        }

        @Test
        @DisplayName("criterion 4: any other integrity violation on the insert is not turned into a duplicate")
        void anotherIntegrityViolationIsNotADuplicate() {
            UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000034");
            when(billRepository.findByOriginEventId(eventId)).thenReturn(Optional.empty());
            counterAt(7);
            when(billRepository.saveAndFlush(any(VendorBill.class)))
                    .thenThrow(new DataIntegrityViolationException("value too long for type character varying(50)"));

            assertThatThrownBy(() -> service.handleGoodsReceivedEvent(buildGoodsReceivedEvent(eventId)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("value too long");
        }

        @Test
        @DisplayName("criterion 5 (BR-8): a replayed eventId returns the existing bill before the rule is asked")
        void replayedEventReturnsTheExistingBillWithoutAskingTheRule() {
            UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000035");
            VendorBill existing = original(GENERATED, BILL_DATE_CLOSE, VendorBillStatus.PENDING_RECEIPT_MATCH);
            existing.setOriginEventId(eventId);
            when(billRepository.findByOriginEventId(eventId)).thenReturn(Optional.of(existing));
            // Were the rule asked, it would find the bill itself and refuse.
            when(billRepository.findLiveDuplicate(any(), any(), any(), any(), any()))
                    .thenReturn(Optional.of(existing));

            VendorBillResponse replayed = service.handleGoodsReceivedEvent(buildGoodsReceivedEvent(eventId));

            assertThat(replayed.getVendorBillId()).isEqualTo(originalId);
            verify(billRepository, never()).findLiveDuplicate(any(), any(), any(), any(), any());
            verify(billRepository, never()).saveAndFlush(any());
        }

        /** A goods-receipt bill that scores HIGH_CONFIDENCE against {@link #buildInvoiceEvent}. */
        private VendorBill matchableGoodsReceiptBill() {
            VendorBill bill = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
            bill.setBillNumber(GENERATED);
            bill.setPurchaseOrderId(UUID.randomUUID());
            when(billRepository.findByVendorIdAndStatus(testVendorId, VendorBillStatus.PENDING_RECEIPT_MATCH))
                    .thenReturn(List.of(bill));
            when(billRepository.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(testBillId))
                    .thenReturn(List.of(
                            buildBillLine(
                                    testBillId, testProductId1, new BigDecimal("100.00"), new BigDecimal("12.50")),
                            buildBillLine(
                                    testBillId, testProductId2, new BigDecimal("1.00"), new BigDecimal("50.00"))));
            return bill;
        }

        private VendorInvoiceReceivedEvent invoiceNamed(String invoiceReference) {
            VendorInvoiceReceivedEvent event = buildInvoiceEvent(testVendorId, new BigDecimal("1300.00"));
            event.setInvoiceReference(invoiceReference);
            return event;
        }

        @Test
        @DisplayName(
                "criterion 6: a match onto a live bill's number is refused and the goods-receipt bill is untouched")
        void matchOntoALiveBillsNumberIsRefused() {
            VendorBill goodsReceiptBill = matchableGoodsReceiptBill();
            VendorBill live = original("INV-77", INVOICE_DATE.withHour(8), VendorBillStatus.PENDING_RECEIPT_MATCH);
            // Same vendor, same key, the invoice's date (AW46: the bill is about to take it), the goods-receipt
            // bill excluded.
            when(billRepository.findLiveDuplicate(
                            testVendorId,
                            "INV77",
                            LocalDateTime.of(2026, 1, 15, 0, 0),
                            LocalDateTime.of(2026, 1, 16, 0, 0),
                            testBillId))
                    .thenReturn(Optional.of(live));

            assertThatThrownBy(() -> service.handleVendorInvoiceReceivedEvent(invoiceNamed("inv 77")))
                    .isInstanceOfSatisfying(
                            VendorBillDuplicateException.class,
                            refused -> assertThat(refused.getOriginalBillId()).isEqualTo(originalId));

            assertThat(goodsReceiptBill.getBillNumber()).isEqualTo(GENERATED);
            assertThat(goodsReceiptBill.getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
            assertThat(goodsReceiptBill.getApprovedBy()).isNull();
            assertThat(goodsReceiptBill.getApprovedAt()).isNull();
            assertThat(goodsReceiptBill.getApprovalJustification()).isNull();
            assertThat(goodsReceiptBill.getRejectionReason()).isNull();
            assertThat(goodsReceiptBill.getDueDate()).isNull();
            assertThat(goodsReceiptBill.getBillDate()).as("AW46(b): untouched").isEqualTo(BILL_DATE_CLOSE);
            verify(billRepository, never()).save(any());
            verify(matchCandidateRepository, never()).save(any());
            verify(evidenceRepository, never()).save(any());
        }

        @Test
        @DisplayName("criterion 7: with the other bill voided the rule finds no live original and the match proceeds")
        void matchProceedsWhenNoLiveBillHoldsTheNumber() {
            VendorBill goodsReceiptBill = matchableGoodsReceiptBill();
            // A VOIDED bill is not returned by findLiveDuplicate (the status filter is pinned on
            // Postgres by VendorBillDuplicateRulePostgresIT), so the stub from realGuard() stands.

            VendorBillResponse matched = service.handleVendorInvoiceReceivedEvent(invoiceNamed("inv 77"));

            assertThat(matched.getBillNumber()).isEqualTo("inv 77");
            assertThat(goodsReceiptBill.getBillNumberKey()).isEqualTo("INV77");
            assertThat(goodsReceiptBill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
            verify(billRepository)
                    .findLiveDuplicate(
                            testVendorId,
                            "INV77",
                            LocalDateTime.of(2026, 1, 15, 0, 0),
                            LocalDateTime.of(2026, 1, 16, 0, 0),
                            testBillId);
        }
    }

    // ========================================
    // Helper Methods
    // ========================================

    private VendorBill buildBill(UUID billId, VendorBillStatus status, BigDecimal totalAmount, LocalDateTime billDate) {
        VendorBill bill = new VendorBill();
        bill.setVendorBillId(billId);
        bill.setVendorId(testVendorId);
        bill.setVendorName("Test Vendor");
        bill.setBillNumber("BILL-001");
        bill.setTotalAmount(totalAmount);
        bill.setBillDate(billDate);
        bill.setStatus(status);
        return bill;
    }

    private VendorBillServiceImpl service(VendorBillDuplicateGuard guard) {
        return new VendorBillServiceImpl(
                clock,
                billRepository,
                billLineRepository,
                matchCandidateRepository,
                vendorDirectoryService,
                guard,
                sequenceLocker,
                transactionManager,
                TestZoneResolvers.utc(FIXED_CLOCK),
                new VendorBillInvoiceMatcher(clock, billLineRepository, evidenceRepository, new LedgerCurrency("USD")),
                reader,
                auditLogs,
                locks);
    }

    /** What the reader answers in these tests: the bill's own fields, enough to assert the routing. */
    private static VendorBillResponse response(VendorBill bill) {
        return VendorBillResponse.builder()
                .vendorBillId(bill.getVendorBillId())
                .billNumber(bill.getBillNumber())
                .status(bill.getStatus())
                .originEventId(bill.getOriginEventId())
                .totalAmount(bill.getTotalAmount())
                .build();
    }

    private VendorBillLine buildBillLine(UUID vendorBillId, UUID productId, BigDecimal quantity, BigDecimal unitPrice) {
        VendorBillLine line = new VendorBillLine();
        line.setLineNumber(productId.equals(testProductId1) ? 1 : 2);
        line.setVendorBillId(vendorBillId);
        line.setProductId(productId);
        line.setQuantity(quantity);
        line.setUnitPrice(unitPrice);
        return line;
    }

    private GoodsReceivedEvent buildGoodsReceivedEvent(UUID eventId) {
        return GoodsReceivedEvent.builder()
                .eventId(eventId)
                .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000041"))
                .purchaseOrderId(UUID.fromString("00000000-0000-0000-0000-000000000009"))
                .vendorId(testVendorId)
                .vendorName("Test Vendor")
                .receivedDate(BILL_DATE_CLOSE)
                .lineItems(List.of(
                        GoodsReceivedEvent.ReceivedLineItem.builder()
                                .productId(testProductId1)
                                .description("Widget A")
                                .quantity(new BigDecimal("100.00"))
                                .unitPrice(new BigDecimal("12.50"))
                                .isInventoryItem(true)
                                .build(),
                        GoodsReceivedEvent.ReceivedLineItem.builder()
                                .productId(testProductId2)
                                .description("Shipping")
                                .quantity(new BigDecimal("1.00"))
                                .unitPrice(new BigDecimal("50.00"))
                                .isInventoryItem(false)
                                .build()))
                .build();
    }

    private VendorInvoiceReceivedEvent buildInvoiceEvent(UUID vendorId, BigDecimal invoiceTotal) {
        // total = 1300.00 = 100*12.50 + 1*50
        return VendorInvoiceReceivedEvent.builder()
                .eventId(UUID.randomUUID())
                .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000041"))
                .vendorId(vendorId)
                .invoiceReference("INV-2026-001")
                .invoiceDate(INVOICE_DATE)
                .dueDate(LocalDateTime.of(2026, 2, 15, 0, 0))
                .lineItems(List.of(
                        VendorInvoiceReceivedEvent.InvoiceLineItem.builder()
                                .productId(testProductId1)
                                .description("Widget A")
                                .quantity(new BigDecimal("100.00"))
                                .unitPrice(new BigDecimal("12.50"))
                                .build(),
                        VendorInvoiceReceivedEvent.InvoiceLineItem.builder()
                                .productId(testProductId2)
                                .description("Shipping")
                                .quantity(new BigDecimal("1.00"))
                                .unitPrice(new BigDecimal("50.00"))
                                .build()))
                .build();
    }
}

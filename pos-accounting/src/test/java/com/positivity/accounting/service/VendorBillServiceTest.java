package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillMatchCandidate;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.VendorBillServiceImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for VendorBillServiceImpl.
 * Covers invoice matching, match exception resolution, and bill retrieval.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VendorBillService Unit Tests")
class VendorBillServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = FIXED_CLOCK;

    @Mock
    private VendorBillRepository billRepository;

    @Mock
    private VendorBillLineRepository billLineRepository;

    @Mock
    private VendorBillMatchCandidateRepository matchCandidateRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private VendorDirectoryService vendorDirectoryService;

    @InjectMocks
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
        @DisplayName("NO_MATCH: should throw IllegalArgumentException when no pending bills found")
        void noMatch_ThrowsException() {
            when(billRepository.findByVendorIdAndStatus(testVendorId, VendorBillStatus.PENDING_RECEIPT_MATCH))
                    .thenReturn(List.of());

            VendorInvoiceReceivedEvent event = buildInvoiceEvent(testVendorId, new BigDecimal("1300.00"));

            assertThatThrownBy(() -> vendorBillService.handleVendorInvoiceReceivedEvent(event))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No pending receipt found");
        }

        @Test
        @DisplayName("HIGH_CONFIDENCE match should auto-approve bill to APPROVED status")
        void highConfidenceMatch_AutoApproves() {
            // Score = amount(40) + lines(30) + date(20) + po(5) = 95 → HIGH_CONFIDENCE
            VendorBill bill = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
            bill.setPurchaseOrderId(UUID.randomUUID()); // +5 pts

            when(billRepository.findByVendorIdAndStatus(testVendorId, VendorBillStatus.PENDING_RECEIPT_MATCH))
                    .thenReturn(List.of(bill));
            when(billRepository.getNextBillSequence()).thenReturn(1L);
            when(billRepository.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));

            // Scoring: return matching bill lines (for Jaccard=1.0 → 30 pts)
            VendorBillLine line1 =
                    buildBillLine(testBillId, testProductId1, new BigDecimal("100.00"), new BigDecimal("12.50"));
            VendorBillLine line2 =
                    buildBillLine(testBillId, testProductId2, new BigDecimal("1.00"), new BigDecimal("50.00"));
            // Both scoring and validation calls return matching lines
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(testBillId))
                    .thenReturn(List.of(line1, line2));

            VendorInvoiceReceivedEvent event = buildInvoiceEvent(testVendorId, new BigDecimal("1300.00"));

            VendorBillResponse result = vendorBillService.handleVendorInvoiceReceivedEvent(event);

            assertThat(result).isNotNull();
            assertThat(result.getVendorBillId()).isEqualTo(testBillId);
            // Bill should be set to APPROVED (but approval justification overridden)
            verify(billRepository).save(any(VendorBill.class));
        }

        @Test
        @DisplayName("HIGH_CONFIDENCE match with discrepancy should set MATCH_EXCEPTION")
        void highConfidenceMatch_WithDiscrepancy_SetsMatchException() {
            // Score = amount(40) + lines(30, from matching productIds in scoring) + date(20) + po(5) = 95 →
            // HIGH_CONFIDENCE
            VendorBill bill = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
            bill.setPurchaseOrderId(UUID.randomUUID());

            when(billRepository.findByVendorIdAndStatus(testVendorId, VendorBillStatus.PENDING_RECEIPT_MATCH))
                    .thenReturn(List.of(bill));
            when(billRepository.getNextBillSequence()).thenReturn(1L);
            when(billRepository.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));

            VendorBillLine line1 =
                    buildBillLine(testBillId, testProductId1, new BigDecimal("100.00"), new BigDecimal("12.50"));
            VendorBillLine line2 =
                    buildBillLine(testBillId, testProductId2, new BigDecimal("1.00"), new BigDecimal("50.00"));
            // First call (scoring): return 2 lines → Jaccard = 1.0 → 30 pts
            // Second call (validation): return only 1 line → count mismatch → discrepancy
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(testBillId))
                    .thenReturn(List.of(line1, line2)) // scoring call
                    .thenReturn(List.of(line1)); // validation call: count mismatch (2 vs 1)

            VendorInvoiceReceivedEvent event = buildInvoiceEvent(testVendorId, new BigDecimal("1300.00"));

            VendorBillResponse result = vendorBillService.handleVendorInvoiceReceivedEvent(event);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
        }

        @Test
        @DisplayName("MEDIUM_CONFIDENCE match should set MATCH_EXCEPTION requiring review")
        void mediumConfidenceMatch_SetsMatchException() {
            // Score = amount(40) + date within 30 days(10) + no lines(0) + no PO(0) = 50 → MEDIUM_CONFIDENCE
            // Bill date is 10 days before invoice → 10 pts
            VendorBill bill = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_MEDIUM);
            // No PO set → 0 pts

            when(billRepository.findByVendorIdAndStatus(testVendorId, VendorBillStatus.PENDING_RECEIPT_MATCH))
                    .thenReturn(List.of(bill));
            when(billRepository.getNextBillSequence()).thenReturn(1L);
            when(billRepository.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));

            VendorBillLine line1 =
                    buildBillLine(testBillId, testProductId1, new BigDecimal("100.00"), new BigDecimal("12.50"));
            VendorBillLine line2 =
                    buildBillLine(testBillId, testProductId2, new BigDecimal("1.00"), new BigDecimal("50.00"));
            // Scoring: return empty (0 pts line items)
            // Validation: return matching lines to avoid discrepancy → reach confidence branch
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(testBillId))
                    .thenReturn(List.of()) // scoring: empty, 0 pts
                    .thenReturn(List.of(line1, line2)); // validation: matching lines, no discrepancy

            VendorInvoiceReceivedEvent event = buildInvoiceEvent(testVendorId, new BigDecimal("1300.00"));

            VendorBillResponse result = vendorBillService.handleVendorInvoiceReceivedEvent(event);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
        }

        @Test
        @DisplayName("AMBIGUOUS match should persist candidates and set MATCH_EXCEPTION")
        void ambiguousMatch_PersistsCandidates() {
            UUID billId2 = UUID.fromString("00000000-0000-0000-0000-000000000005");
            // Two bills both scoring ≥50 → AMBIGUOUS
            VendorBill bill1 = buildBill(
                    testBillId, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
            bill1.setPurchaseOrderId(UUID.randomUUID());

            VendorBill bill2 = buildBill(
                    billId2, VendorBillStatus.PENDING_RECEIPT_MATCH, new BigDecimal("1300.00"), BILL_DATE_CLOSE);
            bill2.setPurchaseOrderId(UUID.randomUUID());

            when(billRepository.findByVendorIdAndStatus(testVendorId, VendorBillStatus.PENDING_RECEIPT_MATCH))
                    .thenReturn(List.of(bill1, bill2));
            when(billRepository.getNextBillSequence()).thenReturn(1L);
            when(billRepository.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));
            when(matchCandidateRepository.save(any(VendorBillMatchCandidate.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Both bills: scoring calls return matching lines for each
            VendorBillLine l1 =
                    buildBillLine(testBillId, testProductId1, new BigDecimal("100.00"), new BigDecimal("12.50"));
            VendorBillLine l2 =
                    buildBillLine(testBillId, testProductId2, new BigDecimal("1.00"), new BigDecimal("50.00"));
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(testBillId))
                    .thenReturn(List.of(l1, l2));
            VendorBillLine l3 =
                    buildBillLine(billId2, testProductId1, new BigDecimal("100.00"), new BigDecimal("12.50"));
            VendorBillLine l4 = buildBillLine(billId2, testProductId2, new BigDecimal("1.00"), new BigDecimal("50.00"));
            when(billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(billId2))
                    .thenReturn(List.of(l3, l4));

            VendorInvoiceReceivedEvent event = buildInvoiceEvent(testVendorId, new BigDecimal("1300.00"));

            VendorBillResponse result = vendorBillService.handleVendorInvoiceReceivedEvent(event);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            // Both candidates should have been persisted
            verify(matchCandidateRepository, times(2)).save(any(VendorBillMatchCandidate.class));
        }
    }

    // ========================================
    // resolveMatchException Tests
    // ========================================

    @Nested
    @DisplayName("resolveMatchException")
    class ResolveMatchExceptionTests {

        @Test
        @DisplayName("should throw IllegalArgumentException when bill not found")
        void billNotFound_Throws() {
            when(billRepository.findById(testBillId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            vendorBillService.resolveMatchException(testBillId, "ACCEPT", "Valid reason", "operator-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Vendor bill not found");
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when bill is not in MATCH_EXCEPTION status")
        void billNotInMatchException_Throws() {
            VendorBill bill =
                    buildBill(testBillId, VendorBillStatus.APPROVED, new BigDecimal("1000.00"), BILL_DATE_CLOSE);
            when(billRepository.findById(testBillId)).thenReturn(Optional.of(bill));

            assertThatThrownBy(() ->
                            vendorBillService.resolveMatchException(testBillId, "ACCEPT", "Valid reason", "operator-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not in MATCH_EXCEPTION status");
        }

        @Test
        @DisplayName("ACCEPT action should approve the bill")
        void acceptAction_ApprovesEBill() {
            VendorBill bill =
                    buildBill(testBillId, VendorBillStatus.MATCH_EXCEPTION, new BigDecimal("1000.00"), BILL_DATE_CLOSE);
            when(billRepository.findById(testBillId)).thenReturn(Optional.of(bill));
            when(billRepository.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));

            VendorBillResponse result =
                    vendorBillService.resolveMatchException(testBillId, "ACCEPT", "Discrepancy accepted", "operator-1");

            assertThat(result.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
            verify(billRepository).save(any(VendorBill.class));
        }

        @Test
        @DisplayName("VOID action should void the bill")
        void voidAction_VoidsBill() {
            VendorBill bill =
                    buildBill(testBillId, VendorBillStatus.MATCH_EXCEPTION, new BigDecimal("1000.00"), BILL_DATE_CLOSE);
            when(billRepository.findById(testBillId)).thenReturn(Optional.of(bill));
            when(billRepository.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));

            VendorBillResponse result =
                    vendorBillService.resolveMatchException(testBillId, "VOID", "Bill is incorrect", "operator-1");

            assertThat(result.getStatus()).isEqualTo(VendorBillStatus.VOIDED);
        }

        @Test
        @DisplayName("CORRECT action should return bill to PENDING_RECEIPT_MATCH for re-matching")
        void correctAction_ReturnsToReceiptMatch() {
            VendorBill bill =
                    buildBill(testBillId, VendorBillStatus.MATCH_EXCEPTION, new BigDecimal("1000.00"), BILL_DATE_CLOSE);
            when(billRepository.findById(testBillId)).thenReturn(Optional.of(bill));
            when(billRepository.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));

            VendorBillResponse result = vendorBillService.resolveMatchException(
                    testBillId, "CORRECT", "Re-submit for matching", "operator-1");

            assertThat(result.getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
        }

        @Test
        @DisplayName("invalid action should throw IllegalArgumentException")
        void invalidAction_Throws() {
            VendorBill bill =
                    buildBill(testBillId, VendorBillStatus.MATCH_EXCEPTION, new BigDecimal("1000.00"), BILL_DATE_CLOSE);
            when(billRepository.findById(testBillId)).thenReturn(Optional.of(bill));

            assertThatThrownBy(() -> vendorBillService.resolveMatchException(
                            testBillId, "UNKNOWN_ACTION", "Reason", "operator-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid resolution action");
        }
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

    private VendorBillLine buildBillLine(UUID vendorBillId, UUID productId, BigDecimal quantity, BigDecimal unitPrice) {
        VendorBillLine line = new VendorBillLine();
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

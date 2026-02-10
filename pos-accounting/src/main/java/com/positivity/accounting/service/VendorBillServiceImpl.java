package com.positivity.accounting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.events.EmitEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of Vendor Bill lifecycle management (Issue #130).
 * 
 * <p>
 * Receipt Accrual Workflow:
 * <ol>
 * <li>GoodsReceivedEvent → createsBill in PENDING_RECEIPT_MATCH</li>
 * <li>VendorInvoiceReceivedEvent → three-way match → MATCHED or
 * MATCH_EXCEPTION</li>
 * <li>Manual resolution (if needed) → APPROVED</li>
 * <li>Payment → PAID</li>
 * </ol>
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/130">Issue
 *      #130</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VendorBillServiceImpl implements VendorBillService {

    private final VendorBillRepository billRepository;

    // Tolerance thresholds for three-way matching
    private static final BigDecimal QUANTITY_TOLERANCE_PERCENT = new BigDecimal("0.001"); // 0.1%
    private static final BigDecimal PRICE_TOLERANCE_PERCENT = new BigDecimal("0.05"); // 5%

    @Override
    @Transactional
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_CREATE", apiVersion = "1")
    public @NonNull VendorBillResponse handleGoodsReceivedEvent(@NonNull GoodsReceivedEvent event) {
        log.info("Processing GoodsReceivedEvent | eventId={} | vendorId={} | poId={}",
                event.getEventId(), event.getVendorId(), event.getPurchaseOrderId());

        // Step 1: Idempotency check
        Optional<VendorBill> existingBill = billRepository.findByOriginEventId(event.getEventId());
        if (existingBill.isPresent()) {
            log.warn("Duplicate GoodsReceivedEvent ignored | eventId={}", event.getEventId());
            return toResponse(existingBill.get());
        }

        // Step 2: Create vendor bill
        VendorBill bill = new VendorBill();
        bill.setVendorId(event.getVendorId());
        bill.setVendorName(event.getVendorName());
        bill.setBillNumber(generateBillNumber(event.getVendorId()));
        bill.setBillDate(event.getReceivedDate());
        bill.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
        bill.setOriginEventId(event.getEventId());
        bill.setOriginEventType("GOODS_RECEIVED");
        bill.setCreatedBy("system");
        bill.setModifiedBy("system");

        // Step 3: Calculate total amount from line items
        BigDecimal totalAmount = event.getLineItems().stream()
                .map(line -> line.getQuantity().multiply(line.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        bill.setTotalAmount(totalAmount);

        // Step 4: Save bill
        VendorBill savedBill = billRepository.save(bill);

        log.info("Vendor bill created | billId={} | eventId={} | totalAmount={} | status={}",
                savedBill.getVendorBillId(), event.getEventId(), totalAmount, savedBill.getStatus());

        // TODO: Emit event for GL posting (Dr Inventory/Expense, Cr AP)
        // This will be handled in task 6

        return toResponse(savedBill);
    }

    @Override
    @Transactional
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_MATCH", apiVersion = "1")
    public @NonNull VendorBillResponse handleVendorInvoiceReceivedEvent(@NonNull VendorInvoiceReceivedEvent event) {
        log.info("Processing VendorInvoiceReceivedEvent | eventId={} | vendorId={} | invoiceRef={}",
                event.getEventId(), event.getVendorId(), event.getInvoiceReference());

        // Step 1: Find pending bill by vendor (assume single pending bill for now)
        // In production, would need more sophisticated matching logic (PO reference,
        // etc.)
        Optional<VendorBill> pendingBill = billRepository
                .findByVendorIdAndStatus(event.getVendorId(), VendorBillStatus.PENDING_RECEIPT_MATCH)
                .stream()
                .min(Comparator.comparing(VendorBill::getBillDate));

        if (pendingBill.isEmpty()) {
            log.error("No pending bill found for invoice | vendorId={} | invoiceRef={}",
                    event.getVendorId(), event.getInvoiceReference());
            throw new IllegalArgumentException(
                    "No pending receipt found for vendor invoice: " + event.getInvoiceReference());
        }

        VendorBill bill = pendingBill.get();

        // Step 2: Perform three-way match validation
        boolean hasDiscrepancy = validateMatchConsistency(bill, event);

        if (hasDiscrepancy) {
            // Transition to MATCH_EXCEPTION
            bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
            bill.setRejectionReason("Quantity or price mismatch detected during three-way match");
            bill.setModifiedBy("system");
            billRepository.save(bill);

            log.warn("Three-way match exception | billId={} | invoiceRef={}",
                    bill.getVendorBillId(), event.getInvoiceReference());

            return toResponse(bill);
        }

        // Step 3: Match successful - transition to MATCHED → APPROVED
        bill.setStatus(VendorBillStatus.APPROVED);
        bill.setBillNumber(event.getInvoiceReference());
        bill.setDueDate(event.getDueDate());
        bill.setModifiedBy("system");
        bill.setApprovedBy("system");
        bill.setApprovedAt(Instant.now());
        bill.setApprovalJustification("Auto-approved: three-way match successful");
        billRepository.save(bill);

        log.info("Three-way match successful | billId={} | invoiceRef={} | status=APPROVED",
                bill.getVendorBillId(), event.getInvoiceReference());

        return toResponse(bill);
    }

    @Override
    @Transactional
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_MATCH_EXCEPTION_RESOLVE", apiVersion = "1")
    public @NonNull VendorBillResponse resolveMatchException(
            @NonNull UUID billId,
            @NonNull String resolutionAction,
            @NonNull String reason,
            @NonNull String operatorId) {

        VendorBill bill = billRepository.findById(billId)
                .orElseThrow(() -> new IllegalArgumentException("Vendor bill not found: " + billId));

        if (bill.getStatus() != VendorBillStatus.MATCH_EXCEPTION) {
            throw new IllegalArgumentException("Bill is not in MATCH_EXCEPTION status: " + bill.getStatus());
        }

        switch (resolutionAction.toUpperCase()) {
            case "ACCEPT":
                // Operator accepts discrepancy - approve bill
                bill.setStatus(VendorBillStatus.APPROVED);
                bill.setApprovedBy(operatorId);
                bill.setApprovedAt(Instant.now());
                bill.setApprovalJustification("Match exception accepted: " + reason);
                bill.setModifiedBy(operatorId);
                break;

            case "VOID":
                // Void bill - requires reversal of GL posting
                bill.setStatus(VendorBillStatus.VOIDED);
                bill.setRejectedBy(operatorId);
                bill.setRejectedAt(Instant.now());
                bill.setRejectionReason("Match exception voided: " + reason);
                bill.setModifiedBy(operatorId);
                break;

            case "CORRECT":
                // Return to PENDING_RECEIPT_MATCH for manual correction
                // In production, would update line items here
                bill.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
                bill.setModifiedBy(operatorId);
                break;

            default:
                throw new IllegalArgumentException("Invalid resolution action: " + resolutionAction);
        }

        VendorBill savedBill = billRepository.save(bill);
        log.info("Match exception resolved | billId={} | action={} | newStatus={} | operator={}",
                billId, resolutionAction, savedBill.getStatus(), operatorId);

        return toResponse(savedBill);
    }

    @Override
    @Transactional(readOnly = true)
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_GET", apiVersion = "1")
    public @NonNull Optional<VendorBillResponse> getBillById(@NonNull UUID billId) {
        return billRepository.findById(billId)
                .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_GET_BY_EVENT", apiVersion = "1")
    public @NonNull Optional<VendorBillResponse> getBillByOriginEventId(@NonNull UUID originEventId) {
        return billRepository.findByOriginEventId(originEventId)
                .map(this::toResponse);
    }

    /**
     * Validates three-way match consistency between bill and invoice.
     * 
     * @param bill  Existing vendor bill (from GoodsReceivedEvent)
     * @param event Vendor invoice event
     * @return true if discrepancy detected, false if match successful
     */
    private boolean validateMatchConsistency(@NonNull VendorBill bill, @NonNull VendorInvoiceReceivedEvent event) {
        // For now, simple total amount comparison
        // In production, would compare line-by-line quantities and prices

        BigDecimal invoiceTotalAmount = event.getLineItems().stream()
                .map(line -> line.getQuantity().multiply(line.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate tolerance (5% of bill total)
        BigDecimal tolerance = bill.getTotalAmount().multiply(PRICE_TOLERANCE_PERCENT);
        BigDecimal difference = bill.getTotalAmount().subtract(invoiceTotalAmount).abs();

        boolean hasDiscrepancy = difference.compareTo(tolerance) > 0;

        if (hasDiscrepancy) {
            log.warn("Amount mismatch detected | billTotal={} | invoiceTotal={} | difference={} | tolerance={}",
                    bill.getTotalAmount(), invoiceTotalAmount, difference, tolerance);
        }

        return hasDiscrepancy;
    }

    /**
     * Generate bill number (placeholder logic).
     * In production, use sequence generator or external service.
     */
    private @NonNull String generateBillNumber(@NonNull UUID vendorId) {
        return "BILL-" + System.currentTimeMillis();
    }

    /**
     * Map VendorBill entity to VendorBillResponse DTO.
     */
    private @NonNull VendorBillResponse toResponse(@NonNull VendorBill bill) {
        return VendorBillResponse.builder()
                .vendorBillId(bill.getVendorBillId())
                .vendorId(bill.getVendorId())
                .vendorName(bill.getVendorName())
                .billNumber(bill.getBillNumber())
                .billDate(bill.getBillDate())
                .dueDate(bill.getDueDate())
                .totalAmount(bill.getTotalAmount())
                .status(bill.getStatus())
                .originEventId(bill.getOriginEventId())
                .originEventType(bill.getOriginEventType())
                .journalEntryId(bill.getJournalEntryId())
                .paymentTransactionId(bill.getPaymentTransactionId())
                .createdAt(bill.getCreatedAt())
                .createdBy(bill.getCreatedBy())
                .approvalJustification(bill.getApprovalJustification())
                .rejectionReason(bill.getRejectionReason())
                .build();
    }
}

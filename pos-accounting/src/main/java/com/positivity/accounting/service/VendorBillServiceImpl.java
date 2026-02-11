package com.positivity.accounting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.internal.dto.BillMatchResult;
import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillGLPostingEvent;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.enums.MatchConfidence;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of Vendor Bill lifecycle management (Issue #130).
 * 
 * <p>
 * Receipt Accrual Workflow:
 * <ol>
 * <li>GoodsReceivedEvent → createsBill in PENDING_RECEIPT_MATCH</li>
 * <li>VendorInvoiceReceivedEvent → three-way match → APPROVED (on success) or
 * MATCH_EXCEPTION (on discrepancy)</li>
 * <li>Manual resolution (if MATCH_EXCEPTION) → APPROVED</li>
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
        private final VendorBillLineRepository billLineRepository;
        private final ApplicationEventPublisher eventPublisher;

        // Tolerance thresholds for three-way matching
        private static final BigDecimal QUANTITY_TOLERANCE_PERCENT = new BigDecimal("0.001"); // 0.1%
        private static final BigDecimal PRICE_TOLERANCE_PERCENT = new BigDecimal("0.05"); // 5%

        @Override
        @Transactional
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
                bill.setPurchaseOrderId(event.getPurchaseOrderId()); // Store PO reference
                bill.setCreatedBy("system");
                bill.setModifiedBy("system");

                // Step 3: Calculate total amount from line items
                BigDecimal totalAmount = event.getLineItems().stream()
                                .map(line -> line.getQuantity().multiply(line.getUnitPrice()))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                bill.setTotalAmount(totalAmount);

                // Step 4: Save bill
                VendorBill savedBill = billRepository.save(bill);

                // Step 5: Save line items for three-way matching
                int lineNumber = 1;
                for (GoodsReceivedEvent.ReceivedLineItem eventLine : event.getLineItems()) {
                        VendorBillLine billLine = new VendorBillLine();
                        billLine.setVendorBillId(savedBill.getVendorBillId());
                        billLine.setLineNumber(lineNumber++);
                        billLine.setProductId(eventLine.getProductId());
                        billLine.setDescription(eventLine.getDescription());
                        billLine.setQuantity(eventLine.getQuantity());
                        billLine.setUnitPrice(eventLine.getUnitPrice());
                        billLine.setLineTotal(eventLine.getQuantity().multiply(eventLine.getUnitPrice()));
                        billLine.setInventoryItem(eventLine.isInventoryItem());
                        billLineRepository.save(billLine);
                }

                log.info("Vendor bill created | billId={} | eventId={} | poId={} | totalAmount={} | lineCount={} | status={}",
                                savedBill.getVendorBillId(), event.getEventId(), event.getPurchaseOrderId(),
                                totalAmount, event.getLineItems().size(), savedBill.getStatus());

                // Step 6: Emit GL posting event for journal entry creation
                // Dr Inventory (for inventory items) or Dr Expense (for non-inventory)
                // Cr Accounts Payable
                VendorBillGLPostingEvent glPostingEvent = VendorBillGLPostingEvent.builder()
                                .eventId(UUID.randomUUID())
                                .vendorBillId(savedBill.getVendorBillId())
                                .vendorId(savedBill.getVendorId())
                                .vendorName(savedBill.getVendorName())
                                .purchaseOrderId(savedBill.getPurchaseOrderId())
                                .billNumber(savedBill.getBillNumber())
                                .billDate(savedBill.getBillDate())
                                .totalAmount(savedBill.getTotalAmount())
                                .lineItems(event.getLineItems().stream()
                                                .map(line -> VendorBillGLPostingEvent.BillLineItem.builder()
                                                                .productId(line.getProductId())
                                                                .description(line.getDescription())
                                                                .quantity(line.getQuantity())
                                                                .unitPrice(line.getUnitPrice())
                                                                .lineTotal(line.getQuantity()
                                                                                .multiply(line.getUnitPrice()))
                                                                .isInventoryItem(line.isInventoryItem())
                                                                .build())
                                                .collect(Collectors.toList()))
                                .build();

                eventPublisher.publishEvent(glPostingEvent);
                log.info("GL posting event emitted | billId={} | eventId={} | totalAmount={}",
                                savedBill.getVendorBillId(), glPostingEvent.getEventId(), totalAmount);

                return toResponse(savedBill);
        }

        @Override
        @Transactional
        public @NonNull VendorBillResponse handleVendorInvoiceReceivedEvent(@NonNull VendorInvoiceReceivedEvent event) {
                log.info("Processing VendorInvoiceReceivedEvent | eventId={} | vendorId={} | invoiceRef={}",
                                event.getEventId(), event.getVendorId(), event.getInvoiceReference());

                // Step 1: Find and match pending bill using enhanced scoring algorithm
                BillMatchResult matchResult = findBestMatchingBillWithConfidence(event);

                if (matchResult.getConfidence() == MatchConfidence.NO_MATCH) {
                        log.error("No matching bill found for invoice | vendorId={} | invoiceRef={} | invoiceAmount={} | details={}",
                                        event.getVendorId(), event.getInvoiceReference(), calculateInvoiceTotal(event),
                                        matchResult.getMatchingDetails());
                        throw new IllegalArgumentException(
                                        "No pending receipt found for vendor invoice: " + event.getInvoiceReference() +
                                                        ". " + matchResult.getMatchingDetails());
                }

                if (matchResult.getConfidence() == MatchConfidence.AMBIGUOUS) {
                        log.warn("Ambiguous match found | vendorId={} | invoiceRef={} | candidates={} | details={}",
                                        event.getVendorId(), event.getInvoiceReference(),
                                        matchResult.getAlternativeCandidates().size(),
                                        matchResult.getMatchingDetails());
                        // TODO: Create approval task with all candidates for manual selection
                        // For now, proceed with best match but mark for review
                        VendorBill bill = matchResult.getBestMatch();
                        bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
                        bill.setRejectionReason("Ambiguous match - multiple candidates found. "
                                        + matchResult.getMatchingDetails());
                        bill.setModifiedBy("system");
                        billRepository.save(bill);
                        return toResponse(bill);
                }

                VendorBill bill = matchResult.getBestMatch();

                // Log match confidence
                log.info("Bill matched | billId={} | invoiceRef={} | confidence={} | score={} | details={}",
                                bill.getVendorBillId(), event.getInvoiceReference(), matchResult.getConfidence(),
                                matchResult.getBestScore(), matchResult.getMatchingDetails());

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

                // Step 3: Auto-approve based on confidence
                if (matchResult.getConfidence() == MatchConfidence.HIGH_CONFIDENCE) {
                        // Auto-approve high confidence matches
                        bill.setStatus(VendorBillStatus.APPROVED);
                        bill.setApprovalJustification("Auto-approved: high confidence match (score="
                                        + matchResult.getBestScore() + ")");
                } else {
                        // Medium confidence - require manual review
                        bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
                        bill.setRejectionReason("Medium confidence match - requires review (score="
                                        + matchResult.getBestScore() + ")");
                }

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
        public @NonNull VendorBillResponse resolveMatchException(
                        @NonNull UUID billId,
                        @NonNull String resolutionAction,
                        @NonNull String reason,
                        @NonNull String operatorId) {

                VendorBill bill = billRepository.findById(billId)
                                .orElseThrow(() -> new IllegalArgumentException("Vendor bill not found: " + billId));

                if (bill.getStatus() != VendorBillStatus.MATCH_EXCEPTION) {
                        throw new IllegalArgumentException(
                                        "Bill is not in MATCH_EXCEPTION status: " + bill.getStatus());
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
        public @NonNull Optional<VendorBillResponse> getBillById(@NonNull UUID billId) {
                return billRepository.findById(billId)
                                .map(this::toResponse);
        }

        @Override
        @Transactional(readOnly = true)
        public @NonNull Optional<VendorBillResponse> getBillByOriginEventId(@NonNull UUID originEventId) {
                return billRepository.findByOriginEventId(originEventId)
                                .map(this::toResponse);
        }

        /**
         * Validates three-way match consistency between bill and invoice.
         * Performs line-by-line comparison with quantity and price tolerances.
         * 
         * @param bill  Existing vendor bill (from GoodsReceivedEvent)
         * @param event Vendor invoice event
         * @return true if discrepancy detected, false if match successful
         */
        private boolean validateMatchConsistency(@NonNull VendorBill bill, @NonNull VendorInvoiceReceivedEvent event) {
                // Load persisted bill line items from goods receipt
                List<VendorBillLine> persistedBillLines = billLineRepository
                                .findByVendorBillIdOrderByLineNumber(bill.getVendorBillId());
                var invoiceLineItems = event.getLineItems();

                // Check line count match
                if (persistedBillLines.size() != invoiceLineItems.size()) {
                        log.warn("Line item count mismatch | billLines={} | invoiceLines={}",
                                        persistedBillLines.size(), invoiceLineItems.size());
                        return true;
                }

                // Line-by-line validation (billLines already sorted by line number from query)
                for (int i = 0; i < persistedBillLines.size(); i++) {
                        VendorBillLine billLine = persistedBillLines.get(i);
                        var invoiceLine = invoiceLineItems.get(i);

                        // Validate quantity within tolerance (0.1%)
                        BigDecimal quantityDifference = billLine.getQuantity()
                                        .subtract(invoiceLine.getQuantity()).abs();
                        BigDecimal quantityTolerance = billLine.getQuantity()
                                        .multiply(QUANTITY_TOLERANCE_PERCENT);

                        if (quantityDifference.compareTo(quantityTolerance) > 0) {
                                log.warn("Quantity mismatch at line {} | billQty={} | invoiceQty={} | difference={} | tolerance={}",
                                                billLine.getLineNumber(), billLine.getQuantity(),
                                                invoiceLine.getQuantity(),
                                                quantityDifference, quantityTolerance);
                                return true;
                        }

                        // Validate unit price within tolerance (5%)
                        BigDecimal priceDifference = billLine.getUnitPrice()
                                        .subtract(invoiceLine.getUnitPrice()).abs();
                        BigDecimal priceTolerance = billLine.getUnitPrice()
                                        .multiply(PRICE_TOLERANCE_PERCENT);

                        if (priceDifference.compareTo(priceTolerance) > 0) {
                                log.warn("Price mismatch at line {} | billPrice={} | invoicePrice={} | difference={} | tolerance={}",
                                                billLine.getLineNumber(), billLine.getUnitPrice(),
                                                invoiceLine.getUnitPrice(),
                                                priceDifference, priceTolerance);
                                return true;
                        }
                }

                // Overall total amount validation as final check
                BigDecimal billTotalAmount = bill.getTotalAmount();
                BigDecimal invoiceTotalAmount = invoiceLineItems.stream()
                                .map(line -> line.getQuantity().multiply(line.getUnitPrice()))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal totalDifference = billTotalAmount.subtract(invoiceTotalAmount).abs();
                BigDecimal totalTolerance = billTotalAmount.multiply(PRICE_TOLERANCE_PERCENT);

                if (totalDifference.compareTo(totalTolerance) > 0) {
                        log.warn("Total amount mismatch | billTotal={} | invoiceTotal={} | difference={} | tolerance={}",
                                        billTotalAmount, invoiceTotalAmount, totalDifference, totalTolerance);
                        return true;
                }

                log.info("Three-way match validation passed | lineItemsValidated={}", persistedBillLines.size());
                return false;
        }

        /**
         * Find the best matching bill for an invoice using enhanced multi-criteria
         * scoring.
         * Returns BillMatchResult with confidence level for appropriate workflow
         * routing.
         * 
         * Scoring algorithm:
         * - Amount match (within 10% tolerance): 40 points
         * - Line item overlap (Jaccard similarity): 30 points
         * - Date proximity (within 30 days): 20 points
         * - PO reference match: 10 points
         * 
         * Confidence levels:
         * - HIGH_CONFIDENCE: Single candidate > 70 points (auto-approve)
         * - MEDIUM_CONFIDENCE: Single candidate 50-70 points (manual review)
         * - AMBIGUOUS: Multiple candidates > 50 points (select from list)
         * - NO_MATCH: No candidates > 50 points (procurement exception)
         * 
         * @param event Vendor invoice received event
         * @return BillMatchResult with best match and confidence level
         */
        private BillMatchResult findBestMatchingBillWithConfidence(@NonNull VendorInvoiceReceivedEvent event) {
                List<VendorBill> candidates = billRepository
                                .findByVendorIdAndStatus(event.getVendorId(), VendorBillStatus.PENDING_RECEIPT_MATCH);

                if (candidates.isEmpty()) {
                        return BillMatchResult.noMatch("No pending bills found for vendor " + event.getVendorId());
                }

                BigDecimal invoiceTotal = calculateInvoiceTotal(event);

                // Score each candidate with enhanced algorithm
                List<BillMatchResult.ScoredBill> scoredBills = candidates.stream()
                                .map(bill -> scoreBillMatch(bill, event, invoiceTotal))
                                .sorted(Comparator.comparingInt(BillMatchResult.ScoredBill::getTotalScore).reversed())
                                .collect(java.util.stream.Collectors.toList());

                // Log all scored candidates
                scoredBills.forEach(scored -> log.info(
                                "Bill matching score | billId={} | score={} | details={}",
                                scored.getBill().getVendorBillId(), scored.getTotalScore(),
                                scored.getScoreBreakdown()));

                // Find candidates meeting minimum threshold (50 points)
                List<BillMatchResult.ScoredBill> qualifiedCandidates = scoredBills.stream()
                                .filter(scored -> scored.getTotalScore() >= 50)
                                .collect(java.util.stream.Collectors.toList());

                if (qualifiedCandidates.isEmpty()) {
                        String details = String.format(
                                        "No qualified matches (min 50 points). Best score: %d. Candidates evaluated: %d",
                                        scoredBills.isEmpty() ? 0 : scoredBills.get(0).getTotalScore(),
                                        scoredBills.size());
                        return BillMatchResult.noMatch(details);
                }

                // Single qualified candidate - check confidence level
                if (qualifiedCandidates.size() == 1) {
                        BillMatchResult.ScoredBill best = qualifiedCandidates.get(0);
                        if (best.getTotalScore() >= 70) {
                                return BillMatchResult.highConfidence(best.getBill(), best.getTotalScore(),
                                                best.getScoreBreakdown());
                        } else {
                                return BillMatchResult.mediumConfidence(best.getBill(), best.getTotalScore(),
                                                best.getScoreBreakdown());
                        }
                }

                // Multiple qualified candidates - ambiguous match
                String details = String.format(
                                "Multiple qualified candidates found: %d bills with score >= 50",
                                qualifiedCandidates.size());
                return BillMatchResult.ambiguous(qualifiedCandidates, details);
        }

        /**
         * Score a single bill against an invoice using all matching criteria.
         */
        private BillMatchResult.ScoredBill scoreBillMatch(VendorBill bill, VendorInvoiceReceivedEvent event,
                        BigDecimal invoiceTotal) {
                int score = 0;
                StringBuilder details = new StringBuilder();

                // 1. Amount matching (40 points)
                BigDecimal amountDiff = bill.getTotalAmount().subtract(invoiceTotal).abs();
                BigDecimal tolerance = bill.getTotalAmount().multiply(new BigDecimal("0.10")); // 10%
                if (amountDiff.compareTo(tolerance) <= 0) {
                        score += 40;
                        details.append("amount_match(40);");
                } else {
                        details.append(String.format("amount_mismatch(bill=%s,invoice=%s);",
                                        bill.getTotalAmount(), invoiceTotal));
                }

                // 2. Line item overlap using Jaccard similarity (30 points)
                List<VendorBillLine> billLines = billLineRepository
                                .findByVendorBillIdOrderByLineNumber(bill.getVendorBillId());
                if (!billLines.isEmpty()) {
                        double similarity = calculateLineItemSimilarity(billLines, event.getLineItems());
                        int lineItemScore = (int) Math.round(30 * similarity);
                        score += lineItemScore;
                        details.append(String.format("line_item_similarity(%.2f=%d);", similarity, lineItemScore));
                } else {
                        details.append("line_items_missing(0);");
                }

                // 3. Date proximity (20 points)
                long daysDiff = Math.abs(
                                java.time.temporal.ChronoUnit.DAYS.between(
                                                bill.getBillDate(),
                                                event.getInvoiceDate()));
                if (daysDiff <= 7) {
                        score += 20;
                        details.append("date_match(20);");
                } else if (daysDiff <= 30) {
                        score += 10;
                        details.append("date_close(10);");
                } else {
                        details.append(String.format("date_far(%dd);", daysDiff));
                }

                // 4. PO reference match (10 points)
                // Note: VendorInvoiceReceivedEvent doesn't have poId - matching by vendorId
                // only
                // In future, add poId to invoice event for full three-way match
                if (bill.getPurchaseOrderId() != null) {
                        // Award partial credit if PO is present (validates receipt came from real PO)
                        score += 5;
                        details.append(String.format("po_present(%s=5);", bill.getPurchaseOrderId()));
                } else {
                        details.append("po_missing(0);");
                }

                return BillMatchResult.ScoredBill.builder()
                                .bill(bill)
                                .totalScore(score)
                                .scoreBreakdown(details.toString())
                                .build();
        }

        /**
         * Calculate line item similarity using Jaccard similarity coefficient.
         * Compares product IDs between bill lines and invoice lines.
         * 
         * @return similarity score 0.0 to 1.0
         */
        private double calculateLineItemSimilarity(List<VendorBillLine> billLines,
                        List<VendorInvoiceReceivedEvent.InvoiceLineItem> invoiceLines) {

                java.util.Set<UUID> billProducts = billLines.stream()
                                .map(VendorBillLine::getProductId)
                                .collect(java.util.stream.Collectors.toSet());

                java.util.Set<UUID> invoiceProducts = invoiceLines.stream()
                                .map(VendorInvoiceReceivedEvent.InvoiceLineItem::getProductId)
                                .collect(java.util.stream.Collectors.toSet());

                java.util.Set<UUID> intersection = new java.util.HashSet<>(billProducts);
                intersection.retainAll(invoiceProducts);

                java.util.Set<UUID> union = new java.util.HashSet<>(billProducts);
                union.addAll(invoiceProducts);

                return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
        }

        /**
         * Calculate total invoice amount from line items.
         */
        private BigDecimal calculateInvoiceTotal(@NonNull VendorInvoiceReceivedEvent event) {
                return event.getLineItems().stream()
                                .map(line -> line.getQuantity().multiply(line.getUnitPrice()))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /**
         * Generate bill number with vendor prefix, date, and database sequence.
         * Format: BILL_<VendorPrefix>_<YYYYMMDD>_<Sequence>
         * Example: BILL_A1B2C3D4_20250211_0001234
         * 
         * Uses PostgreSQL sequence for guaranteed uniqueness, cluster-awareness, and
         * restart resilience. Survives service restarts and multi-instance deployments.
         */
        private @NonNull String generateBillNumber(@NonNull UUID vendorId) {
                String vendorPrefix = vendorId.toString().substring(0, 8).toUpperCase();
                String dateStamp = java.time.LocalDate.now().format(
                                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
                long sequence = billRepository.getNextBillSequence();
                return String.format("BILL_%s_%s_%07d", vendorPrefix, dateStamp, sequence);
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

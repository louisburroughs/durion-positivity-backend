package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.billintake.service.BillIntakePort;
import com.positivity.accounting.internal.dto.BillMatchResult;
import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillListRow;
import com.positivity.accounting.internal.dto.VendorBillMatchCandidateResponse;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillMatchCandidate;
import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import com.positivity.accounting.internal.enums.MatchConfidence;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.InvalidDateRangeException;
import com.positivity.accounting.internal.exception.VendorBillMatchNotFoundException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of Vendor Bill lifecycle management (Issue #130), up to the point a person decides (#2509).
 *
 * <p>
 * Receipt Accrual Workflow:
 * <ol>
 * <li>GoodsReceivedEvent → creates a bill in PENDING_RECEIPT_MATCH; nothing posts (AW37)</li>
 * <li>VendorInvoiceReceivedEvent → three-way match → AWAITING_APPROVAL for a HIGH match (submitted by SYSTEM),
 * then APPROVED and posted by the system when it is within the automatic limit and nothing needs a person
 * ({@link VendorBillAutoApproval}, CAP:550 S13); MATCH_EXCEPTION for a MEDIUM match or a discrepancy, the candidates
 * kept for an ambiguous one. Every match keeps what the vendor billed and its evidence (AW39)</li>
 * <li>Approve, reject, resolve, select and void: {@link VendorBillApprovalServiceImpl}; the bill posts at approval
 * ({@link VendorBillPostingService})</li>
 * </ol>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/130">Issue
 *      #130</a>
 */
@Slf4j
@Service
public class VendorBillServiceImpl implements VendorBillService {
    private final Clock clock;

    private static final String SYSTEM_USER = "SYSTEM";

    private final VendorBillRepository billRepository;
    private final VendorBillLineRepository billLineRepository;
    private final VendorBillMatchCandidateRepository matchCandidateRepository;
    private final VendorBillDuplicateGuard duplicateGuard;
    private final VendorBillInvoiceMatcher matcher;
    private final VendorBillReader reader;
    private final AccountingAuditLogRepository auditLogs;
    private final VendorBillLocks locks;
    private final VendorBillAutoApproval autoApproval;

    /** The single writer of new bills (CAP:550 S25): a goods receipt is accepted through it. */
    private final BillIntakePort billIntake;

    public VendorBillServiceImpl(
            Clock clock,
            VendorBillRepository billRepository,
            VendorBillLineRepository billLineRepository,
            VendorBillMatchCandidateRepository matchCandidateRepository,
            VendorBillDuplicateGuard duplicateGuard,
            VendorBillInvoiceMatcher matcher,
            VendorBillReader reader,
            AccountingAuditLogRepository auditLogs,
            VendorBillLocks locks,
            VendorBillAutoApproval autoApproval,
            BillIntakePort billIntake) {
        this.clock = clock;
        this.billRepository = billRepository;
        this.billLineRepository = billLineRepository;
        this.matchCandidateRepository = matchCandidateRepository;
        this.duplicateGuard = duplicateGuard;
        this.matcher = matcher;
        this.reader = reader;
        this.auditLogs = auditLogs;
        this.locks = locks;
        this.autoApproval = autoApproval;
        this.billIntake = billIntake;
    }

    private String getCurrentUser() {
        return SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER);
    }

    /**
     * Hard cap on page size for {@link #listByDueDateWindow}; also the default when the caller's
     * requested page size is non-positive or over this cap.
     */
    private static final int MAX_LIST_PAGE_SIZE = 100;

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link BillIntakePort#acceptGoodsReceipt}, the single writer of new bills (CAP:550 S25, #2518):
     * the transaction shape, the duplicate rule (#2501), the vendor guards and the bill number all live there.
     */
    @Override
    public @NonNull VendorBillResponse handleGoodsReceivedEvent(@NonNull GoodsReceivedEvent event) {
        return billIntake.acceptGoodsReceipt(event);
    }

    @Override
    @Transactional
    public @NonNull VendorBillResponse handleVendorInvoiceReceivedEvent(@NonNull VendorInvoiceReceivedEvent event) {
        log.info(
                "Processing VendorInvoiceReceivedEvent | eventId={} | vendorId={} | invoiceRef={}",
                event.getEventId(),
                event.getVendorId(),
                event.getInvoiceReference());

        String currentUser = getCurrentUser();
        List<VendorBillInvoiceMatcher.InvoiceLine> invoiceLines = invoiceLines(event);

        // Step 1: Find and match pending bill using enhanced scoring algorithm
        BillMatchResult matchResult = findBestMatchingBillWithConfidence(event);

        if (matchResult.getConfidence() == MatchConfidence.NO_MATCH) {
            log.error(
                    "No matching bill found for invoice | vendorId={} | invoiceRef={} | invoiceAmount={} | details={}",
                    event.getVendorId(),
                    event.getInvoiceReference(),
                    calculateInvoiceTotal(event),
                    matchResult.getMatchingDetails());
            throw new VendorBillMatchNotFoundException("No pending receipt found for vendor invoice: "
                    + event.getInvoiceReference() + ". " + matchResult.getMatchingDetails());
        }

        // The bill the match names, locked and seen as it is now (#2509 review, B-MAJ3): candidates were read
        // without a lock, and a decision taken meanwhile must not be overwritten. A bill no longer pending is a 409
        // OPTIMISTIC_LOCK: nothing is written, and the invoice is sent again.
        BillMatchResult.ScoredBill top = matchResult.getConfidence() == MatchConfidence.AMBIGUOUS
                ? matchResult.getAlternativeCandidates().get(0)
                : Objects.requireNonNull(matchResult.getBestScored());
        VendorBill locked = locks.lock(top.getBill());
        if (locked.getStatus() != VendorBillStatus.PENDING_RECEIPT_MATCH) {
            throw new OptimisticLockingFailureException("Vendor bill " + locked.getBillNumber() + " became "
                    + locked.getStatus() + " while invoice " + event.getInvoiceReference() + " was matched to it");
        }

        if (matchResult.getConfidence() == MatchConfidence.AMBIGUOUS) {
            log.warn(
                    "Ambiguous match found | vendorId={} | invoiceRef={} | candidates={} | details={}",
                    event.getVendorId(),
                    event.getInvoiceReference(),
                    matchResult.getAlternativeCandidates().size(),
                    matchResult.getMatchingDetails());
            // Persist all candidates, with their points and the invoice, for a person to select one.
            persistMatchCandidates(event, invoiceLines, matchResult.getAlternativeCandidates());

            // Mark best-match bill as MATCH_EXCEPTION awaiting operator selection
            BillMatchResult.ScoredBill best = top;
            VendorBill bill = locked;
            bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
            bill.setRejectionReason("Ambiguous match - multiple candidates found. "
                    + "Use /match-candidates/{invoiceEventId} to list and select. "
                    + matchResult.getMatchingDetails());
            bill.setModifiedBy(currentUser);
            billRepository.save(bill);
            VendorBillMatchEvidence evidence = matcher.record(
                    bill,
                    event.getEventId(),
                    VendorBillMatchEvidence.Source.MATCH,
                    MatchConfidence.AMBIGUOUS,
                    points(best),
                    event.getInvoiceReference(),
                    event.getInvoiceDate(),
                    bill.getBillDate(),
                    bill.getBillNumber(),
                    matcher.compare(bill, invoiceLines),
                    currentUser);
            auditRouted(bill, evidence, "AMBIGUOUS", "");
            return reader.read(bill);
        }

        BillMatchResult.ScoredBill best = top;
        VendorBill bill = locked;

        // Log match confidence
        log.info(
                "Bill matched | billId={} | invoiceRef={} | confidence={} | score={} | details={}",
                bill.getVendorBillId(),
                event.getInvoiceReference(),
                matchResult.getConfidence(),
                matchResult.getBestScore(),
                matchResult.getMatchingDetails());

        // Step 2: Three-way match validation, before anything changes: the tolerance check compares the
        // invoice with what was received.
        VendorBillInvoiceMatcher.Comparison comparison = matcher.compare(bill, invoiceLines);
        boolean hasDiscrepancy = !comparison.withinTolerance();
        LocalDateTime receivedDate = bill.getBillDate();
        String receivedBillNumber = bill.getBillNumber();

        // The bill is about to take the vendor's invoice reference as its number and the invoice date as its date
        // (AW46), whatever the routing: the duplicate rule (#2501) is checked first on that number and date, the
        // bill itself excluded, so a refusal leaves it untouched.
        duplicateGuard.refuseIfDuplicate(
                VendorBillDuplicateGuard.Channel.MATCH,
                bill.getVendorId(),
                event.getInvoiceReference(),
                event.getInvoiceDate(),
                bill.getVendorBillId());

        // Step 3: Keep what the vendor billed (AW39): the billed lines and total, whatever the routing.
        matcher.applyBilled(bill, invoiceLines);

        String outcome;
        if (hasDiscrepancy) {
            bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
            bill.setRejectionReason("Quantity or price mismatch detected during three-way match");
            outcome = "DISCREPANCY";
            log.warn(
                    "Three-way match exception | billId={} | invoiceRef={}",
                    bill.getVendorBillId(),
                    event.getInvoiceReference());
        } else if (matchResult.getConfidence() == MatchConfidence.HIGH_CONFIDENCE) {
            // A HIGH match goes to approval; within the automatic limit the system then approves it, below.
            bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
            bill.setSubmittedBy(SYSTEM_USER);
            bill.setSubmittedAt(Instant.now(clock));
            bill.setSubmissionJustification(
                    "Matched to its delivery: score " + matchResult.getBestScore() + " (HIGH confidence)");
            outcome = "HIGH";
        } else {
            // Medium confidence - require manual review
            bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
            bill.setRejectionReason(
                    "Medium confidence match - requires review (score=" + matchResult.getBestScore() + ")");
            outcome = "MEDIUM";
        }
        // AW46: the vendor's number and date; the receipt date stays in the evidence. The invoice's due date replaces
        // one a person entered before the match (S13 ruling 7), audited in the routed row.
        bill.setBillNumber(event.getInvoiceReference());
        bill.setBillDate(event.getInvoiceDate());
        LocalDateTime dueBefore = bill.getDueDate();
        if (event.getDueDate() != null) {
            bill.setDueDate(event.getDueDate());
        }
        bill.setModifiedBy(currentUser);
        billRepository.save(bill);

        VendorBillMatchEvidence evidence = matcher.record(
                bill,
                event.getEventId(),
                VendorBillMatchEvidence.Source.MATCH,
                matchResult.getConfidence(),
                points(best),
                event.getInvoiceReference(),
                event.getInvoiceDate(),
                receivedDate,
                receivedBillNumber,
                comparison,
                currentUser);
        auditRouted(bill, evidence, outcome, VendorBillApprovalServiceImpl.dueDateChange(dueBefore, bill.getDueDate()));
        if ("HIGH".equals(outcome)) {
            // Automatic approval (S13, #2510): within the automatic limit and needing no person, the system approves
            // and posts in this transaction; otherwise the bill stays AWAITING_APPROVAL.
            autoApproval.approveIfEligible(bill, evidence, matchResult.getBestScore());
        }

        log.info(
                "Three-way match routed | billId={} | invoiceRef={} | status={}",
                bill.getVendorBillId(),
                event.getInvoiceReference(),
                bill.getStatus());

        return reader.read(bill);
    }

    /**
     * One {@code VENDOR_BILL_MATCH_ROUTED} audit row per routed match, written as the system's (#2509): where the
     * match sent the bill, its score and evidence. Nothing is approved here.
     */
    private void auditRouted(VendorBill bill, VendorBillMatchEvidence evidence, String outcome, String details) {
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType("VENDOR_BILL");
        row.setEntityId(bill.getVendorBillId());
        row.setOperation("VENDOR_BILL_MATCH_ROUTED");
        row.setUserId(SYSTEM_USER);
        row.setNewValue("billNumber=" + bill.getBillNumber() + ";outcome=" + outcome + ";status=" + bill.getStatus()
                + ";totalAmount=" + bill.getTotalAmount().toPlainString() + ";currencyCode="
                + evidence.getCurrencyCode() + ";matchScore=" + evidence.getScore() + ";evidenceId="
                + evidence.getMatchEvidenceId() + ";requestedBy=" + getCurrentUser() + details);
        auditLogs.save(row);
    }

    private static VendorBillInvoiceMatcher.Points points(BillMatchResult.ScoredBill scored) {
        return new VendorBillInvoiceMatcher.Points(
                scored.getAmountPoints(),
                scored.getProductPoints(),
                scored.getDatePoints(),
                scored.getPurchaseOrderPoints());
    }

    private static List<VendorBillInvoiceMatcher.InvoiceLine> invoiceLines(VendorInvoiceReceivedEvent event) {
        return event.getLineItems().stream()
                .map(line -> new VendorBillInvoiceMatcher.InvoiceLine(
                        line.getProductId(), line.getDescription(), line.getQuantity(), line.getUnitPrice()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<VendorBillResponse> getBillById(@NonNull UUID billId) {
        return billRepository.findById(billId).map(reader::read);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<VendorBillResponse> getBillByOriginEventId(@NonNull UUID originEventId) {
        return billRepository.findByOriginEventId(originEventId).map(reader::read);
    }

    /**
     * Find the best matching bill for an invoice using enhanced multi-criteria
     * scoring.
     * Returns BillMatchResult with confidence level for appropriate workflow
     * routing.
     *
     * Scoring algorithm (P3):
     * - Amount match (within 10% tolerance): 40 points
     * - Line item overlap (Jaccard similarity): 30 points
     * - Date proximity (within 7 days 20, within 30 days 10): 20 points
     * - Purchase order present on the receipt: 5 points
     *
     * Confidence levels:
     * - HIGH_CONFIDENCE: Single candidate with 70 points or more (sent for approval, #2509; approved by the system within the automatic limit, #2510)
     * - MEDIUM_CONFIDENCE: Single candidate with 50 to 69 points (manual review)
     * - AMBIGUOUS: Multiple candidates with 50 points or more (select from list)
     * - NO_MATCH: No candidate with 50 points or more (refused)
     *
     * @param event Vendor invoice received event
     * @return BillMatchResult with best match and confidence level
     */
    private BillMatchResult findBestMatchingBillWithConfidence(@NonNull VendorInvoiceReceivedEvent event) {
        // Receipt bills only: an EDI bill is the vendor's own invoice, never matched to one (#2509 review, B-MAJ2).
        // A bill still named by an open ambiguous match waits for that selection: matching it to another invoice would
        // send it for approval while its candidates are open, which no decision may then take (#2509 review, L-new-2).
        List<VendorBill> candidates =
                billRepository
                        .findByVendorIdAndStatus(event.getVendorId(), VendorBillStatus.PENDING_RECEIPT_MATCH)
                        .stream()
                        .filter(bill -> !VendorBillReader.ORIGIN_SUPPLIER_INVOICE.equals(bill.getOriginEventType()))
                        .filter(bill -> matchCandidateRepository
                                .findByVendorBill_VendorBillIdAndResolvedFalse(bill.getVendorBillId())
                                .isEmpty())
                        .toList();

        if (candidates.isEmpty()) {
            return BillMatchResult.noMatch("No pending bills found for vendor " + event.getVendorId());
        }

        BigDecimal invoiceTotal = calculateInvoiceTotal(event);

        // Score each candidate with enhanced algorithm
        List<BillMatchResult.ScoredBill> scoredBills = candidates.stream()
                .map(bill -> scoreBillMatch(bill, event, invoiceTotal))
                .sorted(Comparator.comparingInt(BillMatchResult.ScoredBill::getTotalScore)
                        .reversed())
                .toList();

        // Log all scored candidates
        scoredBills.forEach(scored -> log.info(
                "Bill matching score | billId={} | score={} | details={}",
                scored.getBill().getVendorBillId(),
                scored.getTotalScore(),
                scored.getScoreBreakdown()));

        // Find candidates meeting minimum threshold (50 points)
        List<BillMatchResult.ScoredBill> qualifiedCandidates = scoredBills.stream()
                .filter(scored -> scored.getTotalScore() >= 50)
                .toList();

        if (qualifiedCandidates.isEmpty()) {
            String details = String.format(
                    "No qualified matches (min 50 points). Best score: %d. Candidates evaluated: %d",
                    scoredBills.isEmpty() ? 0 : scoredBills.get(0).getTotalScore(), scoredBills.size());
            return BillMatchResult.noMatch(details);
        }

        // Single qualified candidate - check confidence level
        if (qualifiedCandidates.size() == 1) {
            BillMatchResult.ScoredBill best = qualifiedCandidates.get(0);
            if (best.getTotalScore() >= 70) {
                return BillMatchResult.highConfidence(best);
            } else {
                return BillMatchResult.mediumConfidence(best);
            }
        }

        // Multiple qualified candidates - ambiguous match
        String details = String.format(
                "Multiple qualified candidates found: %d bills with score >= 50", qualifiedCandidates.size());
        return BillMatchResult.ambiguous(qualifiedCandidates, details);
    }

    /**
     * Score a single bill against an invoice using all matching criteria.
     */
    private BillMatchResult.ScoredBill scoreBillMatch(
            VendorBill bill, VendorInvoiceReceivedEvent event, BigDecimal invoiceTotal) {
        int score = 0;
        int amountPoints = 0;
        int productPoints = 0;
        int datePoints = 0;
        int purchaseOrderPoints = 0;
        StringBuilder details = new StringBuilder();

        // 1. Amount matching (40 points), against what was received (#2509 review, B-MAJ2): the received lines'
        // total, never a total an earlier match left on the bill; a bill without lines keeps its own total.
        List<VendorBillLine> stored =
                billLineRepository.findByVendorBill_VendorBillIdOrderByLineNumber(bill.getVendorBillId());
        List<VendorBillLine> billLines = VendorBillInvoiceMatcher.received(stored);
        BigDecimal receivedTotal =
                billLines.isEmpty() ? bill.getTotalAmount() : VendorBillInvoiceMatcher.receivedTotal(billLines);
        BigDecimal amountDiff = receivedTotal.subtract(invoiceTotal).abs();
        BigDecimal tolerance = receivedTotal.multiply(new BigDecimal("0.10")); // 10%
        if (amountDiff.compareTo(tolerance) <= 0) {
            amountPoints = 40;
            score += 40;
            details.append("amount_match(40);");
        } else {
            details.append(String.format("amount_mismatch(bill=%s,invoice=%s);", receivedTotal, invoiceTotal));
        }

        // 2. Line item overlap using Jaccard similarity (30 points), over the received lines
        if (!billLines.isEmpty()) {
            double similarity = calculateLineItemSimilarity(billLines, event.getLineItems());
            int lineItemScore = (int) Math.round(30 * similarity);
            productPoints = lineItemScore;
            score += lineItemScore;
            details.append(String.format("line_item_similarity(%.2f=%d);", similarity, lineItemScore));
        } else {
            details.append("line_items_missing(0);");
        }

        // 3. Date proximity (20 points)
        // Calendar days between the two dates as written (no zone needed: both are already local dates, #2558).
        long daysDiff = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(
                bill.getBillDate().toLocalDate(), event.getInvoiceDate().toLocalDate()));
        if (daysDiff <= 7) {
            datePoints = 20;
            score += 20;
            details.append("date_match(20);");
        } else if (daysDiff <= 30) {
            datePoints = 10;
            score += 10;
            details.append("date_close(10);");
        } else {
            details.append(String.format("date_far(%dd);", daysDiff));
        }

        // 4. PO reference (5 points, P3)
        // Note: VendorInvoiceReceivedEvent doesn't have poId - matching by vendorId
        // only
        // In future, add poId to invoice event for full three-way match
        if (bill.getPurchaseOrderId() != null) {
            // Award partial credit if PO is present (validates receipt came from real PO)
            purchaseOrderPoints = 5;
            score += 5;
            details.append(String.format("po_present(%s=5);", bill.getPurchaseOrderId()));
        } else {
            details.append("po_missing(0);");
        }

        return BillMatchResult.ScoredBill.builder()
                .bill(bill)
                .totalScore(score)
                .scoreBreakdown(details.toString())
                .amountPoints(amountPoints)
                .productPoints(productPoints)
                .datePoints(datePoints)
                .purchaseOrderPoints(purchaseOrderPoints)
                .build();
    }

    /**
     * Calculate line item similarity using Jaccard similarity coefficient.
     * Compares product IDs between bill lines and invoice lines.
     *
     * @return similarity score 0.0 to 1.0
     */
    private double calculateLineItemSimilarity(
            List<VendorBillLine> billLines, List<VendorInvoiceReceivedEvent.InvoiceLineItem> invoiceLines) {

        java.util.Set<UUID> billProducts =
                billLines.stream().map(VendorBillLine::getProductId).collect(Collectors.toSet());

        java.util.Set<UUID> invoiceProducts = invoiceLines.stream()
                .map(VendorInvoiceReceivedEvent.InvoiceLineItem::getProductId)
                .collect(Collectors.toSet());

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

    // ===== Match Candidate Persistence & Selection =====

    /**
     * Persist all scored candidates for an ambiguous invoice match, with their points and the invoice they were
     * scored against (#2509), so a selection keeps what the vendor billed and writes the same evidence.
     * Denormalizes key bill fields for efficient listing without joins.
     */
    private void persistMatchCandidates(
            @NonNull VendorInvoiceReceivedEvent event,
            @NonNull List<VendorBillInvoiceMatcher.InvoiceLine> invoiceLines,
            @NonNull List<BillMatchResult.ScoredBill> candidates) {
        BigDecimal invoiceTotal = invoiceLines.stream()
                .map(VendorBillInvoiceMatcher.InvoiceLine::net)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (BillMatchResult.ScoredBill scored : candidates) {
            VendorBillMatchCandidate candidate = new VendorBillMatchCandidate();
            candidate.setInvoiceEventId(event.getEventId());
            candidate.setVendorBillId(scored.getBill().getVendorBillId());
            candidate.setVendorId(event.getVendorId());
            candidate.setBillNumber(scored.getBill().getBillNumber());
            candidate.setBillTotalAmount(scored.getBill().getTotalAmount());
            candidate.setMatchScore(scored.getTotalScore());
            candidate.setScoreBreakdown(scored.getScoreBreakdown());
            candidate.setAmountPoints(scored.getAmountPoints());
            candidate.setProductPoints(scored.getProductPoints());
            candidate.setDatePoints(scored.getDatePoints());
            candidate.setPurchaseOrderPoints(scored.getPurchaseOrderPoints());
            candidate.setInvoiceReference(event.getInvoiceReference());
            candidate.setInvoiceDate(event.getInvoiceDate());
            candidate.setInvoiceDueDate(event.getDueDate());
            candidate.setInvoiceTotalAmount(invoiceTotal);
            candidate.setInvoiceLines(VendorBillInvoiceMatcher.toJson(invoiceLines));
            matchCandidateRepository.save(candidate);
        }
        log.info("Persisted {} match candidates | invoiceEventId={}", candidates.size(), event.getEventId());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<VendorBillMatchCandidateResponse> listMatchCandidates(@NonNull UUID invoiceEventId) {
        List<VendorBillMatchCandidate> candidates =
                matchCandidateRepository.findByInvoiceEventIdAndResolvedFalseOrderByMatchScoreDesc(invoiceEventId);
        return candidates.stream().map(this::toCandidateResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Page<VendorBillListRow> listByDueDateWindow(
            @NonNull LocalDate dueFrom,
            @NonNull LocalDate dueTo,
            @Nullable VendorBillStatus status,
            @NonNull Pageable pageable) {

        if (dueTo.isBefore(dueFrom)) {
            throw new InvalidDateRangeException("dueTo cannot be before dueFrom");
        }
        long windowDays = ChronoUnit.DAYS.between(dueFrom, dueTo);
        if (windowDays > MAX_DUE_DATE_WINDOW_DAYS) {
            throw new InvalidDateRangeException("Due-date window cannot exceed " + MAX_DUE_DATE_WINDOW_DAYS + " days");
        }

        int pageSize = pageable.getPageSize() > 0 && pageable.getPageSize() <= MAX_LIST_PAGE_SIZE
                ? pageable.getPageSize()
                : MAX_LIST_PAGE_SIZE;
        // Sort order is server-controlled (dueDate ascending), mirroring
        // APPaymentService#listEligibleBills — any sort supplied by the caller is ignored.
        Pageable effectivePageable =
                PageRequest.of(pageable.getPageNumber(), pageSize, Sort.by(Sort.Direction.ASC, "dueDate"));

        LocalDateTime dueFromStart = dueFrom.atStartOfDay();
        LocalDateTime dueToEnd = dueTo.atTime(LocalTime.MAX);

        Page<VendorBill> bills = status != null
                ? billRepository.findByDueDateBetweenAndStatus(dueFromStart, dueToEnd, status, effectivePageable)
                : billRepository.findByDueDateBetween(dueFromStart, dueToEnd, effectivePageable);

        return bills.map(this::toListRow);
    }

    /**
     * Map VendorBill entity to the due-date-window list row (Wave 2 E9, issue #1597).
     *
     * <p>Carries {@code billNumber} and {@code vendorName} alongside the identifiers (issue #1892)
     * so the Payables list has something human-readable to render; both come straight off the bill,
     * exactly as the sibling {@code GET /v1/accounting/ap/bills} projection reads them.
     */
    private @NonNull VendorBillListRow toListRow(@NonNull VendorBill bill) {
        return VendorBillListRow.builder()
                .billId(bill.getVendorBillId())
                .vendorId(bill.getVendorId())
                .vendorName(bill.getVendorName())
                .billNumber(bill.getBillNumber())
                .dueDate(bill.getDueDate())
                .amount(bill.getTotalAmount())
                .status(bill.getStatus())
                .build();
    }

    /**
     * Map VendorBillMatchCandidate entity to response DTO.
     */
    private @NonNull VendorBillMatchCandidateResponse toCandidateResponse(@NonNull VendorBillMatchCandidate candidate) {
        return VendorBillMatchCandidateResponse.builder()
                .candidateId(candidate.getCandidateId())
                .invoiceEventId(candidate.getInvoiceEventId())
                .vendorBillId(candidate.getVendorBillId())
                .vendorId(candidate.getVendorId())
                .billNumber(candidate.getBillNumber())
                .billTotalAmount(candidate.getBillTotalAmount())
                .matchScore(candidate.getMatchScore())
                .scoreBreakdown(candidate.getScoreBreakdown())
                .resolved(candidate.isResolved())
                .selected(candidate.isSelected())
                .createdAt(candidate.getCreatedAt())
                .build();
    }
}

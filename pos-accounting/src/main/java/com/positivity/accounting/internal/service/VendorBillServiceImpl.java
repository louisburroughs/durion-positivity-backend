package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.BillMatchResult;
import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillListRow;
import com.positivity.accounting.internal.dto.VendorBillMatchCandidateResponse;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
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
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.security.common.SecurityContextHelper;
import java.io.Serial;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implementation of Vendor Bill lifecycle management (Issue #130), up to the point a person decides (#2509).
 *
 * <p>
 * Receipt Accrual Workflow:
 * <ol>
 * <li>GoodsReceivedEvent → creates a bill in PENDING_RECEIPT_MATCH; nothing posts (AW37)</li>
 * <li>VendorInvoiceReceivedEvent → three-way match → AWAITING_APPROVAL for a HIGH match (submitted by SYSTEM; the
 * automatic limit is 0 until S13), MATCH_EXCEPTION for a MEDIUM match or a discrepancy, the candidates kept for an
 * ambiguous one. No match writes an approval field (G12); every match keeps what the vendor billed and its evidence
 * (AW39)</li>
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

    /** Prefix of the per-tenant {@code accounting_sequence} scope that numbers goods-receipt bills. */
    static final String BILL_NUMBER_SCOPE_PREFIX = "BILL-";

    private final VendorBillRepository billRepository;
    private final VendorBillLineRepository billLineRepository;
    private final VendorBillMatchCandidateRepository matchCandidateRepository;
    private final VendorDirectoryService vendorDirectoryService;
    private final VendorBillDuplicateGuard duplicateGuard;
    private final AccountingSequenceLocker sequenceLocker;
    private final VendorBillInvoiceMatcher matcher;
    private final VendorBillReader reader;
    private final AccountingAuditLogRepository auditLogs;
    private final VendorBillLocks locks;

    private final AccountingCalendarZoneResolver zoneResolver;

    /**
     * The goods-receipt create, in a transaction this class can see the end of (#2501): the original
     * of a bill that lost a race under {@code uq_vendor_bill_duplicate_rule} is read after this
     * template has returned. By then the failed transaction has rolled back when this class began it,
     * and is marked rollback-only when it joined a caller's.
     */
    private final TransactionTemplate goodsReceiptTransaction;

    public VendorBillServiceImpl(
            Clock clock,
            VendorBillRepository billRepository,
            VendorBillLineRepository billLineRepository,
            VendorBillMatchCandidateRepository matchCandidateRepository,
            VendorDirectoryService vendorDirectoryService,
            VendorBillDuplicateGuard duplicateGuard,
            AccountingSequenceLocker sequenceLocker,
            PlatformTransactionManager transactionManager,
            AccountingCalendarZoneResolver zoneResolver,
            VendorBillInvoiceMatcher matcher,
            VendorBillReader reader,
            AccountingAuditLogRepository auditLogs,
            VendorBillLocks locks) {
        this.zoneResolver = zoneResolver;
        this.clock = clock;
        this.billRepository = billRepository;
        this.billLineRepository = billLineRepository;
        this.matchCandidateRepository = matchCandidateRepository;
        this.vendorDirectoryService = vendorDirectoryService;
        this.duplicateGuard = duplicateGuard;
        this.sequenceLocker = sequenceLocker;
        this.matcher = matcher;
        this.reader = reader;
        this.auditLogs = auditLogs;
        this.locks = locks;
        this.goodsReceiptTransaction = new TransactionTemplate(transactionManager);
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
     * <p>Not {@code @Transactional}: the work runs in {@link #goodsReceiptTransaction}, which joins a
     * caller's transaction when there is one. A bill that would duplicate a live one (#2501; same
     * vendor, normalised number and bill date) is refused with {@link VendorBillDuplicateException}
     * before anything is saved. When a concurrent writer commits the same key between that check and
     * the insert, the unique index refuses the insert instead and Postgres aborts the transaction. If
     * this method began it, it has rolled back by the time the original is read; if it joined a
     * caller's, it is only marked rollback-only and the caller rolls it back. In both cases the
     * original is read by {@link VendorBillDuplicateGuard#findOriginalAfterCollision} in a {@code
     * REQUIRES_NEW} transaction of its own, which is what makes the read possible, so both paths
     * give the same answer.
     *
     * <p><strong>Connections.</strong> The bill's number is drawn under the tenant's counter row lock
     * ({@link #generateBillNumber}), which is held until the transaction ends, so concurrent creates in
     * a tenant queue on it, each holding a pooled connection. While that lock is held this method
     * therefore asks for no second connection: on a small pool the holder would wait for one behind
     * the very writers waiting for its lock, until the pool's timeout, and for that long no tenant
     * would get a connection at all (the defect #2342 removed from the counter's own bootstrap). The
     * vendor-directory write, which used to run in a {@code REQUIRES_NEW} transaction of its own, is
     * made on the bill's connection with a conflict-tolerant insert
     * ({@link VendorDirectoryService#recordVendorInCurrentTransaction}), so it commits with the bill
     * and a refused or rolled-back create writes no directory row. Nothing else between the number
     * and the commit leaves the bill's connection: the duplicate check, the bill and its lines all join this
     * transaction. Nothing is posted (AW37).
     *
     * <p>One exception remains, on the collision path only. When this method runs inside a caller's
     * transaction and the unique index refuses the insert, that transaction is aborted but still
     * holds the counter lock until the caller rolls it back, and reading the original takes one more
     * connection meanwhile. The cost is bounded: one extra connection per refused create, for one
     * indexed read, and only for a collision inside a caller's transaction. If the pool has none to
     * give, the read fails after the pool's connection timeout and that error is thrown instead of
     * the refusal; no bill is created either way. When this method began the transaction itself, it
     * has rolled back, lock and connection released, before the original is read.
     */
    @Override
    public @NonNull VendorBillResponse handleGoodsReceivedEvent(@NonNull GoodsReceivedEvent event) {
        try {
            return Objects.requireNonNull(goodsReceiptTransaction.execute(_ -> createFromGoodsReceipt(event)));
        } catch (LostDuplicateRace lost) {
            VendorBill original = duplicateGuard
                    .findOriginalAfterCollision(event.getVendorId(), lost.billNumber, lost.billDate)
                    .orElseThrow(lost::getCause);
            throw duplicateGuard.refusal(
                    VendorBillDuplicateGuard.Channel.GOODS_RECEIPT,
                    event.getVendorId(),
                    lost.billNumber,
                    lost.billDate,
                    original);
        }
    }

    /**
     * An insert refused by {@code uq_vendor_bill_duplicate_rule}: carries what is needed to find the
     * original out of the failed transaction.
     */
    private static final class LostDuplicateRace extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String billNumber;
        private final LocalDateTime billDate;

        LostDuplicateRace(String billNumber, LocalDateTime billDate, DataIntegrityViolationException cause) {
            super(cause);
            this.billNumber = billNumber;
            this.billDate = billDate;
        }

        @Override
        public synchronized @NonNull DataIntegrityViolationException getCause() {
            return (DataIntegrityViolationException) super.getCause();
        }
    }

    private @NonNull VendorBillResponse createFromGoodsReceipt(@NonNull GoodsReceivedEvent event) {
        log.info(
                "Processing GoodsReceivedEvent | eventId={} | vendorId={} | poId={}",
                event.getEventId(),
                event.getVendorId(),
                event.getPurchaseOrderId());

        String currentUser = getCurrentUser();

        // Step 1: Idempotency check
        Optional<VendorBill> existingBill = billRepository.findByOriginEventId(event.getEventId());
        if (existingBill.isPresent()) {
            log.warn("Duplicate GoodsReceivedEvent ignored | eventId={}", event.getEventId());
            return reader.read(existingBill.get());
        }

        // Step 2: Create vendor bill
        VendorBill bill = new VendorBill();
        bill.setVendorId(event.getVendorId());
        bill.setVendorName(event.getVendorName());
        bill.setBillDate(event.getReceivedDate());
        bill.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
        bill.setOriginEventId(event.getEventId());
        bill.setOriginEventType("GOODS_RECEIVED");
        bill.setPurchaseOrderId(event.getPurchaseOrderId()); // Store PO reference
        bill.setCreatedBy(currentUser);
        bill.setModifiedBy(currentUser);

        // Step 3: Calculate total amount from line items
        BigDecimal totalAmount = event.getLineItems().stream()
                .map(line -> line.getQuantity().multiply(line.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        bill.setTotalAmount(totalAmount);

        // The number, as late as it can be drawn: from here to the end of this transaction the
        // tenant's counter row is locked, and nothing below asks for a second connection.
        bill.setBillNumber(generateBillNumber(event.getVendorId()));

        // Step 4: The duplicate rule (#2501), then the insert. Flushed here so the unique index
        // answers before the lines or the GL posting event see the bill.
        duplicateGuard.refuseIfDuplicate(
                VendorBillDuplicateGuard.Channel.GOODS_RECEIPT,
                bill.getVendorId(),
                bill.getBillNumber(),
                bill.getBillDate(),
                null);
        VendorBill savedBill;
        try {
            savedBill = billRepository.saveAndFlush(bill);
        } catch (DataIntegrityViolationException e) {
            if (VendorBillDuplicateGuard.isDuplicateRuleViolation(e)) {
                throw new LostDuplicateRace(bill.getBillNumber(), bill.getBillDate(), e);
            }
            throw e;
        }

        // Keep the AP vendor directory (name typeahead) in sync (Issue #816), on this connection:
        // the counter row lock is held, so no second connection may be requested here
        // (handleGoodsReceivedEvent). The insert tolerates an existing row, so it cannot fail the
        // bill for the reason the former REQUIRES_NEW write was isolated against.
        vendorDirectoryService.recordVendorInCurrentTransaction(event.getVendorId(), event.getVendorName());

        // Step 5: Save line items for three-way matching
        int lineNumber = 1;
        for (GoodsReceivedEvent.ReceivedLineItem eventLine : event.getLineItems()) {
            VendorBillLine billLine = new VendorBillLine();
            billLine.setVendorBill(savedBill);
            billLine.setLineNumber(lineNumber++);
            billLine.setProductId(eventLine.getProductId());
            billLine.setDescription(eventLine.getDescription());
            billLine.setQuantity(eventLine.getQuantity());
            billLine.setUnitPrice(eventLine.getUnitPrice());
            billLine.setLineTotal(eventLine.getQuantity().multiply(eventLine.getUnitPrice()));
            billLine.setInventoryItem(eventLine.isInventoryItem());
            billLineRepository.save(billLine);
        }

        log.info(
                "Vendor bill created | billId={} | eventId={} | poId={} | totalAmount={} | lineCount={} | status={}",
                savedBill.getVendorBillId(),
                event.getEventId(),
                event.getPurchaseOrderId(),
                totalAmount,
                event.getLineItems().size(),
                savedBill.getStatus());

        // Nothing posts at creation (AW37): the receipt's own accrual is S41's, and the bill posts once, at
        // approval (VendorBillPostingService).
        return reader.read(savedBill);
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
            auditRouted(bill, evidence, "AMBIGUOUS");
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
            // A HIGH match goes to approval, never approves (G12): the automatic limit is 0 until S13.
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
        // AW46: the vendor's number and date; the receipt date stays in the evidence.
        bill.setBillNumber(event.getInvoiceReference());
        bill.setBillDate(event.getInvoiceDate());
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
        auditRouted(bill, evidence, outcome);

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
    private void auditRouted(VendorBill bill, VendorBillMatchEvidence evidence, String outcome) {
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType("VENDOR_BILL");
        row.setEntityId(bill.getVendorBillId());
        row.setOperation("VENDOR_BILL_MATCH_ROUTED");
        row.setUserId(SYSTEM_USER);
        row.setNewValue("billNumber=" + bill.getBillNumber() + ";outcome=" + outcome + ";status=" + bill.getStatus()
                + ";totalAmount=" + bill.getTotalAmount().toPlainString() + ";currencyCode="
                + evidence.getCurrencyCode() + ";matchScore=" + evidence.getScore() + ";evidenceId="
                + evidence.getMatchEvidenceId() + ";requestedBy=" + getCurrentUser());
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
     * - HIGH_CONFIDENCE: Single candidate with 70 points or more (sent for approval, #2509; never approves)
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

    /**
     * Generates a goods-receipt bill number: {@code BILL_<VendorPrefix>_<YYYYMMDD>_<Sequence>}, for
     * example {@code BILL_A1B2C3D4_20250211_0001234}. The date is the day the bill is recorded.
     *
     * <p>The sequence is the bound tenant's own (ADR-0062 section 9; #2501): the per-tenant {@code
     * accounting_sequence} counter under scope {@code BILL-{YYYYMM}}, the month of that same date,
     * through the {@link AccountingSequenceLocker} machinery that numbers journal entries
     * ({@code JournalEntryServiceImpl.assignEntryNumber}, #942) and credit memos
     * ({@code CreditMemoServiceImpl.assignCreditMemoReference}). No database sequence is involved: a
     * shared one would hand every tenant numbers out of one series.
     *
     * <p>The counter row is read under {@code FOR UPDATE} and incremented inside the bill's own
     * transaction, so concurrent creates in a tenant take distinct numbers one after another (and
     * the caller must request no second pooled connection until that transaction ends; see {@link
     * #handleGoodsReceivedEvent}), and a create that rolls back (a refused duplicate included) rolls the increment back with it: the
     * number is not consumed, and the next create takes it. A tenant's row for a month is created on
     * first use by the locker; nothing provisions it.
     */
    private @NonNull String generateBillNumber(@NonNull UUID vendorId) {
        String vendorPrefix = vendorId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        // The bill's month in the tenant's accounting calendar (#2558).
        LocalDate recorded = zoneResolver.today();
        AccountingSequence sequence = sequenceLocker.lockOrProvision(
                String.format("%s%04d%02d", BILL_NUMBER_SCOPE_PREFIX, recorded.getYear(), recorded.getMonthValue()));
        long assigned = sequence.getNextValue();
        sequence.setNextValue(assigned + 1);
        return String.format(
                "BILL_%s_%s_%07d", vendorPrefix, recorded.format(DateTimeFormatter.BASIC_ISO_DATE), assigned);
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

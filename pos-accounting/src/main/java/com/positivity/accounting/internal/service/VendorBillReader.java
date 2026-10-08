package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillMatchCandidate;
import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import com.positivity.accounting.internal.enums.MatchConfidence;
import com.positivity.accounting.internal.enums.VendorBillAction;
import com.positivity.accounting.internal.enums.VendorBillCheckOutcome;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillStage;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.VendorBillGlPostingRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchEvidenceRepository;
import com.positivity.accounting.internal.repository.VendorBillReissueRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vendor-bill reads of the Bills to pay review (CAP:550 S12, #2509; SPEC-accounting-workspace §5.2, P5, P8): the
 * bill with its approval, rejection, match evidence, lines, checks, the caller's available actions and its posting,
 * and the four stages with their counts and lists. Reads only; every command answers with {@link #read}.
 */
@Component
@RequiredArgsConstructor
public class VendorBillReader {

    /** Statuses of the CHECK stage. */
    static final Set<VendorBillStatus> CHECK_STATUSES = EnumSet.of(
            VendorBillStatus.PENDING_RECEIPT_MATCH, VendorBillStatus.MATCH_EXCEPTION, VendorBillStatus.CURRENCY_HOLD);

    /** Origin event types, and the channel each names. */
    static final String ORIGIN_GOODS_RECEIVED = "GOODS_RECEIVED";

    static final String ORIGIN_SUPPLIER_INVOICE = "SUPPLIER_INVOICE_RECEIVED";

    static final String CHECK_MATCHED_TO_DELIVERY = "MATCHED_TO_DELIVERY";
    static final String CHECK_WITHIN_PRICE_TOLERANCE = "WITHIN_PRICE_TOLERANCE";
    static final String CHECK_TOTALS_ADD_UP = "TOTALS_ADD_UP";
    static final String CHECK_OPEN_DELIVERIES_FROM_VENDOR = "OPEN_DELIVERIES_FROM_VENDOR";

    /** {@code MATCHED_TO_DELIVERY} FAIL reasons. */
    static final String REASON_PICK_A_MATCH = "PICK_A_MATCH";

    static final String REASON_INVOICE_NOT_MATCHED = "INVOICE_NOT_MATCHED";
    static final String REASON_NO_DELIVERY_RECORDED = "NO_DELIVERY_RECORDED";

    /** Statuses in which a goods-receipt bill is still open for {@code OPEN_DELIVERIES_FROM_VENDOR} (AW44). */
    static final Set<VendorBillStatus> OPEN_DELIVERY_STATUSES = EnumSet.of(
            VendorBillStatus.PENDING_RECEIPT_MATCH,
            VendorBillStatus.MATCH_EXCEPTION,
            VendorBillStatus.AWAITING_APPROVAL);

    /** At most this many bill numbers in the {@code OPEN_DELIVERIES_FROM_VENDOR} args; the count is always whole. */
    static final int OPEN_DELIVERIES_LISTED = 10;

    /** Page size cap of the stage lists (the vendor-bill list precedent). */
    static final int MAX_PAGE_SIZE = 100;

    private final Clock clock;
    private final VendorBillRepository bills;
    private final VendorBillLineRepository billLines;
    private final VendorBillMatchEvidenceRepository evidence;
    private final VendorBillMatchCandidateRepository candidates;
    private final VendorBillGlPostingRepository postings;
    private final VendorBillReissueRepository reissues;
    private final APPaymentAllocationRepository allocations;
    private final JournalEntryRepository journalEntries;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final LedgerCurrency ledgerCurrency;

    /** The full read of one bill, for the caller in the security context. */
    @Transactional(readOnly = true)
    public @NonNull VendorBillResponse read(@NonNull VendorBill bill) {
        UUID billId = bill.getVendorBillId();
        String currencyCode = currencyOf(bill);
        BigDecimal allocated = allocations.sumAllocatedAmountByVendorBillId(billId);
        BigDecimal openAmount = nz(bill.getTotalAmount()).subtract(nz(allocated));
        Optional<VendorBillMatchEvidence> latest =
                evidence.findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(billId);
        List<VendorBillLine> stored = billLines.findByVendorBill_VendorBillIdOrderByLineNumber(billId);
        boolean matched = invoiceMatched(latest.orElse(null), stored);
        List<VendorBillMatchCandidate> openCandidates = openCandidates(billId);
        Optional<VendorBillGlPosting> posting = postings.findByVendorBillId(billId);
        boolean approvedOnce = posting.isPresent()
                && (bill.getStatus() == VendorBillStatus.APPROVED || bill.getStatus() == VendorBillStatus.VOIDED);
        VendorBillReview.Channel channel = channelOf(bill);
        Optional<VendorBillTotals> totals = VendorBillTotals.of(bill);

        return VendorBillResponse.builder()
                .vendorBillId(billId)
                .vendorId(bill.getVendorId())
                .vendorName(bill.getVendorName())
                .billNumber(bill.getBillNumber())
                .billDate(bill.getBillDate())
                .dueDate(bill.getDueDate())
                .totalAmount(bill.getTotalAmount())
                .netAmount(bill.getNetAmount())
                .taxAmount(bill.getTaxAmount())
                .currency(bill.getCurrency())
                .status(bill.getStatus())
                .originEventId(bill.getOriginEventId())
                .originEventType(bill.getOriginEventType())
                .journalEntryId(bill.getJournalEntryId())
                .paymentTransactionId(bill.getPaymentTransactionId())
                .createdAt(bill.getCreatedAt())
                .createdBy(bill.getCreatedBy())
                .channel(channel)
                .approval(approval(bill, approvedOnce))
                .rejection(rejection(bill))
                .statusExplanation(statusExplanation(bill))
                .openAmount(openAmount)
                .match(latest.map(VendorBillReader::match).orElse(null))
                .openCandidates(openCandidates.stream()
                        .map(c -> candidate(c, currencyCode))
                        .toList())
                .reissues(reissues(billId))
                .lines(lines(stored, currencyCode))
                .checks(checks(
                        channel,
                        matched ? latest.orElse(null) : null,
                        !openCandidates.isEmpty(),
                        totals.orElse(null),
                        openDeliveries(bill, channel, posting.orElse(null))))
                .availableActions(availableActions(
                        bill.getStatus(),
                        channel,
                        !openCandidates.isEmpty(),
                        awaitsInvoice(channel, matched),
                        nz(allocated).signum() != 0,
                        posting.isPresent()))
                .posting(posting.map(this::posting).orElse(null))
                .build();
    }

    /**
     * Whether a vendor invoice was matched to the bill (AW44): its latest evidence is a match or a selection, not the
     * scoring of an ambiguous match, and its lines carry what the invoice billed (a {@code CORRECT} clears them).
     */
    static boolean invoiceMatched(@Nullable VendorBillMatchEvidence latest, @NonNull List<VendorBillLine> stored) {
        return latest != null && !ambiguousScoring(latest) && VendorBillInvoiceMatcher.billed(stored);
    }

    /** {@link #invoiceMatched} for a stored bill. */
    @Transactional(readOnly = true)
    public boolean invoiceMatched(@NonNull VendorBill bill) {
        return invoiceMatched(
                evidence.findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(bill.getVendorBillId())
                        .orElse(null),
                billLines.findByVendorBill_VendorBillIdOrderByLineNumber(bill.getVendorBillId()));
    }

    /** Whether a goods-receipt bill still waits for its vendor invoice (AW44); never for an EDI bill. */
    static boolean awaitsInvoice(VendorBillReview.@Nullable Channel channel, boolean matched) {
        return channel == VendorBillReview.Channel.GOODS_RECEIPT && !matched;
    }

    /** Whether an ambiguous match left candidates naming the bill that nobody has picked yet. */
    @Transactional(readOnly = true)
    public boolean hasOpenCandidates(@NonNull UUID billId) {
        return !candidates.findByVendorBill_VendorBillIdAndResolvedFalse(billId).isEmpty();
    }

    /** The evidence of an ambiguous match's scoring: the top bill was named, nothing billed was kept. */
    static boolean ambiguousScoring(@NonNull VendorBillMatchEvidence row) {
        return row.getSource() == VendorBillMatchEvidence.Source.MATCH
                && row.getConfidence() == MatchConfidence.AMBIGUOUS;
    }

    // ---- stages ---------------------------------------------------------------------------------------------

    /** How many bills are in each stage now (§5.2); no due-date window, so bills without a due date count. */
    @Transactional(readOnly = true)
    public VendorBillReview.@NonNull StageCounts stageCounts() {
        return new VendorBillReview.StageCounts(
                bills.countByStatusIn(CHECK_STATUSES),
                bills.countByStatusIn(EnumSet.of(VendorBillStatus.AWAITING_APPROVAL)),
                bills.countApprovedWithOpenAmount(),
                done().size(),
                Instant.now(clock));
    }

    /**
     * One page of a stage, in the server's order: CHECK and APPROVE oldest first, PAY by due date with bills without
     * one last, DONE newest paid first. Page size is capped at {@value #MAX_PAGE_SIZE}.
     */
    @Transactional(readOnly = true)
    public @NonNull Page<VendorBillReview.StageRow> byStage(@NonNull VendorBillStage stage, int page, int size) {
        int pageSize = size > 0 && size <= MAX_PAGE_SIZE ? size : MAX_PAGE_SIZE;
        int pageNumber = Math.max(page, 0);
        Page<VendorBill> rows =
                switch (stage) {
                    case CHECK ->
                        bills.findByStatusIn(
                                CHECK_STATUSES,
                                PageRequest.of(
                                        pageNumber,
                                        pageSize,
                                        Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("vendorBillId"))));
                    case APPROVE ->
                        bills.findByStatusIn(
                                EnumSet.of(VendorBillStatus.AWAITING_APPROVAL),
                                PageRequest.of(
                                        pageNumber,
                                        pageSize,
                                        Sort.by(
                                                Sort.Order.asc("submittedAt"),
                                                Sort.Order.asc("createdAt"),
                                                Sort.Order.asc("vendorBillId"))));
                    case PAY ->
                        bills.findByStatusAndOpenAmountGreaterThan(
                                VendorBillStatus.APPROVED, BigDecimal.ZERO, PageRequest.of(pageNumber, pageSize));
                    case DONE -> donePage(pageNumber, pageSize);
                };
        Map<UUID, BigDecimal> allocated = allocatedBy(
                rows.getContent().stream().map(VendorBill::getVendorBillId).toList());
        return rows.map(bill -> stageRow(bill, nz(allocated.get(bill.getVendorBillId()))));
    }

    private Page<VendorBill> donePage(int page, int size) {
        List<VendorBill> done = done();
        int from = Math.min(page * size, done.size());
        int to = Math.min(from + size, done.size());
        return new PageImpl<>(done.subList(from, to), PageRequest.of(page, size), done.size());
    }

    /** The DONE stage: paid in full, the last payment dated in the current month of the tenant's calendar. */
    private List<VendorBill> done() {
        YearMonth month = zoneResolver.currentMonth();
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        List<VendorBill> paid = bills.findApprovedPaidInFullWithLastPaymentBetween(from, to);
        if (paid.isEmpty()) {
            return paid;
        }
        Map<UUID, LocalDateTime> lastPayment =
                allocations
                        .findLastPaymentDateByVendorBillIdIn(
                                paid.stream().map(VendorBill::getVendorBillId).toList())
                        .stream()
                        .collect(Collectors.toMap(
                                APPaymentAllocationRepository.VendorBillLastPayment::getVendorBillId,
                                APPaymentAllocationRepository.VendorBillLastPayment::getLastPaymentDate));
        return paid.stream()
                .sorted(Comparator.comparing(
                                (VendorBill b) -> lastPayment.getOrDefault(b.getVendorBillId(), LocalDateTime.MIN))
                        .reversed()
                        .thenComparing(VendorBill::getVendorBillId))
                .toList();
    }

    private Map<UUID, BigDecimal> allocatedBy(List<UUID> billIds) {
        if (billIds.isEmpty()) {
            return Map.of();
        }
        return allocations.sumAllocatedAmountByVendorBillIdIn(billIds).stream()
                .collect(Collectors.toMap(
                        APPaymentAllocationRepository.VendorBillAllocationSum::getVendorBillId,
                        APPaymentAllocationRepository.VendorBillAllocationSum::getAllocated));
    }

    private VendorBillReview.StageRow stageRow(VendorBill bill, BigDecimal allocated) {
        return new VendorBillReview.StageRow(
                bill.getVendorBillId(),
                bill.getBillNumber(),
                bill.getVendorName(),
                bill.getTotalAmount(),
                currencyOf(bill),
                bill.getBillDate(),
                bill.getDueDate(),
                bill.getStatus(),
                channelOf(bill),
                bill.getSubmittedAt(),
                nz(bill.getTotalAmount()).subtract(allocated));
    }

    // ---- the bill's blocks ----------------------------------------------------------------------------------

    private static VendorBillReview.@Nullable Approval approval(VendorBill bill, boolean approvedOnce) {
        if (bill.getSubmittedAt() == null && !approvedOnce) {
            return null;
        }
        VendorBillReview.Classification proposed =
                bill.getProposedDebitClass() == null && bill.getProposedExpenseMappingKey() == null
                        ? null
                        : new VendorBillReview.Classification(
                                bill.getProposedDebitClass(), bill.getProposedExpenseMappingKey());
        VendorBillReview.Difference difference = bill.getDifferenceClass() == null
                ? null
                : new VendorBillReview.Difference(
                        bill.getDifferenceClass(),
                        bill.getDifferenceExpenseMappingKey(),
                        bill.getDifferenceJustification());
        return new VendorBillReview.Approval(
                bill.getSubmittedAt(),
                bill.getSubmittedBy(),
                bill.getSubmissionJustification(),
                VendorBillReview.RequiredTier.OVER_LIMIT,
                proposed,
                difference,
                approvedOnce ? bill.getApprovedAt() : null,
                approvedOnce ? bill.getApprovedBy() : null,
                approvedOnce ? bill.getApprovalJustification() : null);
    }

    private static VendorBillReview.@Nullable Rejection rejection(VendorBill bill) {
        boolean decided = bill.getStatus() == VendorBillStatus.REJECTED || bill.getStatus() == VendorBillStatus.VOIDED;
        if (!decided || bill.getRejectedAt() == null) {
            return null;
        }
        return new VendorBillReview.Rejection(bill.getRejectedAt(), bill.getRejectedBy(), bill.getRejectionReason());
    }

    private static @Nullable String statusExplanation(VendorBill bill) {
        return bill.getStatus() == VendorBillStatus.MATCH_EXCEPTION
                        || bill.getStatus() == VendorBillStatus.CURRENCY_HOLD
                ? bill.getRejectionReason()
                : null;
    }

    private static VendorBillReview.Match match(VendorBillMatchEvidence row) {
        return new VendorBillReview.Match(
                row.getMatchEvidenceId(),
                row.getSource().name(),
                row.getConfidence(),
                row.getScore(),
                new VendorBillReview.Points(
                        row.getAmountPoints(),
                        row.getProductPoints(),
                        row.getDatePoints(),
                        row.getPurchaseOrderPoints()),
                row.getInvoiceReference(),
                row.getInvoiceDate(),
                row.getReceivedDate(),
                row.getReceivedTotal(),
                row.getBilledTotal(),
                row.getCurrencyCode(),
                row.isWithinTolerance(),
                row.getRecordedAt());
    }

    /** The unresolved candidates of every ambiguous match naming this bill. */
    private List<VendorBillMatchCandidate> openCandidates(UUID billId) {
        Set<UUID> events = new LinkedHashSet<>();
        candidates
                .findByVendorBill_VendorBillIdAndResolvedFalse(billId)
                .forEach(c -> events.add(c.getInvoiceEventId()));
        List<VendorBillMatchCandidate> open = new ArrayList<>();
        events.forEach(
                event -> open.addAll(candidates.findByInvoiceEventIdAndResolvedFalseOrderByMatchScoreDesc(event)));
        return open;
    }

    private static VendorBillReview.Candidate candidate(VendorBillMatchCandidate c, String currencyCode) {
        VendorBillReview.Points points = c.getAmountPoints() == null
                ? null
                : new VendorBillReview.Points(
                        c.getAmountPoints(),
                        nzi(c.getProductPoints()),
                        nzi(c.getDatePoints()),
                        nzi(c.getPurchaseOrderPoints()));
        return new VendorBillReview.Candidate(
                c.getCandidateId(),
                c.getInvoiceEventId(),
                c.getVendorBillId(),
                c.getBillNumber(),
                c.getBillTotalAmount(),
                currencyCode,
                c.getMatchScore(),
                points);
    }

    private List<VendorBillReview.Reissue> reissues(UUID billId) {
        return reissues.findByVendorBillIdOrderByCreatedAtAsc(billId).stream()
                .map(r -> new VendorBillReview.Reissue(
                        r.getVendorBillReissueId(),
                        r.getIncomingBillNumber(),
                        r.getIncomingBillDate(),
                        r.getIncomingAmount(),
                        r.getIncomingCurrencyCode(),
                        r.getHeldAmount(),
                        r.getHeldCurrencyCode(),
                        r.getCreatedAt()))
                .toList();
    }

    private static List<VendorBillReview.Line> lines(List<VendorBillLine> stored, String currencyCode) {
        List<VendorBillReview.Line> lines = new ArrayList<>();
        for (VendorBillLine line : stored) {
            lines.add(new VendorBillReview.Line(
                    line.getLineNumber(),
                    line.getProductId(),
                    line.getDescription(),
                    line.isInventoryItem(),
                    line.getQuantity(),
                    line.getUnitPrice(),
                    line.getBilledQuantity(),
                    line.getBilledUnitPrice(),
                    currencyCode));
        }
        return lines;
    }

    /** The goods-receipt bills of an EDI GOODS bill's vendor still open (AW44); null when the check does not apply. */
    private @Nullable List<VendorBill> openDeliveries(
            VendorBill bill, VendorBillReview.@Nullable Channel channel, @Nullable VendorBillGlPosting posting) {
        VendorBillDebitClass debitClass = posting != null ? posting.getDebitClass() : bill.getProposedDebitClass();
        if (channel != VendorBillReview.Channel.SUPPLIER_CONNECTION
                || debitClass != VendorBillDebitClass.GOODS
                || bill.getVendorId() == null) {
            return null;
        }
        return bills.findByVendorIdAndOriginEventTypeAndStatusInOrderByCreatedAtAscVendorBillIdAsc(
                bill.getVendorId(), ORIGIN_GOODS_RECEIVED, OPEN_DELIVERY_STATUSES);
    }

    /**
     * The checks of the review (§5.2; AW44, AW46):
     *
     * <ul>
     *   <li>{@code MATCHED_TO_DELIVERY}: PASS once an invoice is matched (a HIGH or MEDIUM match, or a selection;
     *       MEDIUM passes, its lower confidence is in {@code args.confidence}); FAIL with {@code reason} {@code
     *       PICK_A_MATCH} while an ambiguous match's candidates are open, {@code INVOICE_NOT_MATCHED} for a
     *       goods-receipt bill and {@code NO_DELIVERY_RECORDED} for an EDI bill.
     *   <li>{@code WITHIN_PRICE_TOLERANCE}: the matched invoice against the receipt; NOT_APPLICABLE before a match.
     *   <li>{@code TOTALS_ADD_UP}: only on a bill with the vendor's header totals; FAIL with {@code difference} beyond
     *       the rounding tolerance.
     *   <li>{@code OPEN_DELIVERIES_FROM_VENDOR}: only on an EDI bill classified GOODS; FAIL with {@code count} and
     *       {@code billNumbers} while the vendor has goods-receipt bills open. Informational: it blocks nothing.
     * </ul>
     *
     * @param matched the latest evidence when an invoice is matched to the bill, else null
     * @param openDeliveries the vendor's open goods-receipt bills, or null when the check does not apply
     */
    static @NonNull List<VendorBillReview.Check> checks(
            VendorBillReview.@Nullable Channel channel,
            @Nullable VendorBillMatchEvidence matched,
            boolean hasOpenCandidates,
            @Nullable VendorBillTotals totals,
            @Nullable List<VendorBill> openDeliveries) {
        List<VendorBillReview.Check> checks = new ArrayList<>();
        if (matched != null && !hasOpenCandidates) {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("invoiceReference", matched.getInvoiceReference());
            args.put("score", String.valueOf(matched.getScore()));
            args.put("confidence", matched.getConfidence().name());
            checks.add(new VendorBillReview.Check(CHECK_MATCHED_TO_DELIVERY, VendorBillCheckOutcome.PASS, args));
            Map<String, String> amounts = new LinkedHashMap<>();
            amounts.put("receivedTotal", matched.getReceivedTotal().toPlainString());
            amounts.put("billedTotal", matched.getBilledTotal().toPlainString());
            amounts.put("currencyCode", matched.getCurrencyCode());
            checks.add(new VendorBillReview.Check(
                    CHECK_WITHIN_PRICE_TOLERANCE,
                    matched.isWithinTolerance() ? VendorBillCheckOutcome.PASS : VendorBillCheckOutcome.FAIL,
                    amounts));
        } else {
            String reason;
            if (hasOpenCandidates) {
                reason = REASON_PICK_A_MATCH;
            } else if (channel == VendorBillReview.Channel.GOODS_RECEIPT) {
                reason = REASON_INVOICE_NOT_MATCHED;
            } else {
                reason = REASON_NO_DELIVERY_RECORDED;
            }
            checks.add(new VendorBillReview.Check(
                    CHECK_MATCHED_TO_DELIVERY, VendorBillCheckOutcome.FAIL, Map.of("reason", reason)));
            checks.add(new VendorBillReview.Check(
                    CHECK_WITHIN_PRICE_TOLERANCE, VendorBillCheckOutcome.NOT_APPLICABLE, Map.of()));
        }
        if (totals != null) {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("difference", totals.difference().toPlainString());
            args.put("netAmount", totals.net().toPlainString());
            args.put("taxAmount", totals.tax().toPlainString());
            args.put("totalAmount", totals.gross().toPlainString());
            args.put("tolerance", totals.tolerance().toPlainString());
            checks.add(new VendorBillReview.Check(
                    CHECK_TOTALS_ADD_UP,
                    totals.reconciled() ? VendorBillCheckOutcome.PASS : VendorBillCheckOutcome.FAIL,
                    args));
        }
        if (openDeliveries != null) {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("count", String.valueOf(openDeliveries.size()));
            args.put(
                    "billNumbers",
                    openDeliveries.stream()
                            .limit(OPEN_DELIVERIES_LISTED)
                            .map(VendorBill::getBillNumber)
                            .collect(Collectors.joining(", ")));
            checks.add(new VendorBillReview.Check(
                    CHECK_OPEN_DELIVERIES_FROM_VENDOR,
                    openDeliveries.isEmpty() ? VendorBillCheckOutcome.PASS : VendorBillCheckOutcome.FAIL,
                    args));
        }
        return checks;
    }

    /**
     * The decisions valid for the bill now whose permission the caller holds (P5). {@code blockedReason} is S13's.
     *
     * <ul>
     *   <li>While an ambiguous match's candidates are open, the bill is picked first: no send or accept (#2509
     *       review).
     *   <li>A goods-receipt bill no invoice is matched to is never sent, approved or accepted (AW44); in {@code
     *       PENDING_RECEIPT_MATCH} it can be voided ({@code VOID_UNMATCHED}).
     *   <li>An approved bill is voidable only with its posting and no allocation.
     * </ul>
     */
    static @NonNull List<VendorBillReview.AvailableAction> availableActions(
            @NonNull VendorBillStatus status,
            VendorBillReview.@Nullable Channel channel,
            boolean hasOpenCandidates,
            boolean awaitingInvoice,
            boolean allocated,
            boolean posted) {
        List<VendorBillReview.AvailableAction> actions = new ArrayList<>();
        boolean sendable = !hasOpenCandidates && !awaitingInvoice;
        switch (status) {
            case PENDING_RECEIPT_MATCH -> {
                if (sendable) {
                    offer(actions, VendorBillAction.SUBMIT_FOR_APPROVAL);
                }
                if (hasOpenCandidates) {
                    offer(actions, VendorBillAction.SELECT_CANDIDATE);
                }
                if (channel == VendorBillReview.Channel.GOODS_RECEIPT) {
                    offer(actions, VendorBillAction.VOID_UNMATCHED);
                }
            }
            case MATCH_EXCEPTION -> {
                if (sendable) {
                    offer(actions, VendorBillAction.SUBMIT_FOR_APPROVAL);
                    offer(actions, VendorBillAction.ACCEPT_EXCEPTION);
                }
                offer(actions, VendorBillAction.CORRECT_EXCEPTION);
                offer(actions, VendorBillAction.VOID_EXCEPTION);
                if (hasOpenCandidates) {
                    offer(actions, VendorBillAction.SELECT_CANDIDATE);
                }
            }
            case AWAITING_APPROVAL -> {
                if (!awaitingInvoice) {
                    offer(actions, VendorBillAction.APPROVE);
                }
                offer(actions, VendorBillAction.REJECT);
            }
            case APPROVED -> {
                if (posted && !allocated) {
                    offer(actions, VendorBillAction.VOID_APPROVED);
                }
            }
            default -> {
                // CURRENCY_HOLD (never submitted or approved, ADR-0067), REJECTED, VOIDED, PAID: nothing.
            }
        }
        return actions;
    }

    private static void offer(List<VendorBillReview.AvailableAction> actions, VendorBillAction action) {
        if (VendorBillDecisions.mayTake(action)) {
            actions.add(new VendorBillReview.AvailableAction(
                    action, true, null, VendorBillDecisions.justificationRequired(action)));
        }
    }

    private VendorBillReview.Posting posting(VendorBillGlPosting posting) {
        return new VendorBillReview.Posting(
                posting.getJournalEntryId(),
                entryNumber(posting.getJournalEntryId()),
                posting.getPostingDate(),
                posting.getPostingDateRule(),
                posting.getGrossAmount(),
                posting.getCurrencyCode(),
                posting.getRoundingAdjustment() == null ? BigDecimal.ZERO.setScale(2) : posting.getRoundingAdjustment(),
                posting.getDifferenceClass(),
                posting.getDifferenceAmount(),
                posting.getReversalJournalEntryId() == null ? null : entryNumber(posting.getReversalJournalEntryId()),
                posting.getReversalDate());
    }

    private @Nullable String entryNumber(UUID journalEntryId) {
        return journalEntries
                .findById(journalEntryId)
                .map(JournalEntry::getEntryNumber)
                .orElse(null);
    }

    static VendorBillReview.@Nullable Channel channelOf(VendorBill bill) {
        if (ORIGIN_GOODS_RECEIVED.equals(bill.getOriginEventType())) {
            return VendorBillReview.Channel.GOODS_RECEIPT;
        }
        if (ORIGIN_SUPPLIER_INVOICE.equals(bill.getOriginEventType())) {
            return VendorBillReview.Channel.SUPPLIER_CONNECTION;
        }
        return null;
    }

    private String currencyOf(VendorBill bill) {
        return bill.getCurrency() == null || bill.getCurrency().isBlank()
                ? ledgerCurrency.code()
                : bill.getCurrency().trim();
    }

    private static BigDecimal nz(@Nullable BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static int nzi(@Nullable Integer value) {
        return value == null ? 0 : value;
    }
}

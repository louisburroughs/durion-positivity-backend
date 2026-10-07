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
import com.positivity.accounting.internal.enums.VendorBillAction;
import com.positivity.accounting.internal.enums.VendorBillCheckOutcome;
import com.positivity.accounting.internal.enums.VendorBillStage;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.VendorBillGlPostingRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchEvidenceRepository;
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

    /** Page size cap of the stage lists (the vendor-bill list precedent). */
    static final int MAX_PAGE_SIZE = 100;

    private final Clock clock;
    private final VendorBillRepository bills;
    private final VendorBillLineRepository billLines;
    private final VendorBillMatchEvidenceRepository evidence;
    private final VendorBillMatchCandidateRepository candidates;
    private final VendorBillGlPostingRepository postings;
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
        List<VendorBillMatchCandidate> openCandidates = openCandidates(billId);
        Optional<VendorBillGlPosting> posting = postings.findByVendorBillId(billId);
        boolean approvedOnce = posting.isPresent()
                && (bill.getStatus() == VendorBillStatus.APPROVED || bill.getStatus() == VendorBillStatus.VOIDED);
        VendorBillReview.Channel channel = channelOf(bill);

        return VendorBillResponse.builder()
                .vendorBillId(billId)
                .vendorId(bill.getVendorId())
                .vendorName(bill.getVendorName())
                .billNumber(bill.getBillNumber())
                .billDate(bill.getBillDate())
                .dueDate(bill.getDueDate())
                .totalAmount(bill.getTotalAmount())
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
                .match(latest.map(e -> match(e, openCandidates, currencyCode)).orElse(null))
                .lines(lines(billId, currencyCode))
                .checks(checks(channel, latest.orElse(null)))
                .availableActions(availableActions(
                        bill.getStatus(),
                        !openCandidates.isEmpty(),
                        nz(allocated).signum() != 0))
                .posting(posting.map(p -> posting(p)).orElse(null))
                .build();
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
        return new VendorBillReview.Approval(
                bill.getSubmittedAt(),
                bill.getSubmittedBy(),
                bill.getSubmissionJustification(),
                VendorBillReview.RequiredTier.OVER_LIMIT,
                proposed,
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

    private VendorBillReview.Match match(
            VendorBillMatchEvidence row, List<VendorBillMatchCandidate> open, String currencyCode) {
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
                row.getReceivedTotal(),
                row.getBilledTotal(),
                row.getCurrencyCode(),
                row.isWithinTolerance(),
                row.getRecordedAt(),
                open.stream().map(c -> candidate(c, currencyCode)).toList());
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
                c.getVendorBillId(),
                c.getBillNumber(),
                c.getBillTotalAmount(),
                currencyCode,
                c.getMatchScore(),
                points);
    }

    private List<VendorBillReview.Line> lines(UUID billId, String currencyCode) {
        List<VendorBillReview.Line> lines = new ArrayList<>();
        for (VendorBillLine line : billLines.findByVendorBill_VendorBillIdOrderByLineNumber(billId)) {
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

    /** {@code MATCHED_TO_DELIVERY} and {@code WITHIN_PRICE_TOLERANCE} (§5.2); later stories add codes. */
    static @NonNull List<VendorBillReview.Check> checks(
            VendorBillReview.@Nullable Channel channel, @Nullable VendorBillMatchEvidence latest) {
        List<VendorBillReview.Check> checks = new ArrayList<>();
        if (latest != null) {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("invoiceReference", latest.getInvoiceReference());
            args.put("score", String.valueOf(latest.getScore()));
            args.put("confidence", latest.getConfidence().name());
            checks.add(new VendorBillReview.Check(CHECK_MATCHED_TO_DELIVERY, VendorBillCheckOutcome.PASS, args));
            Map<String, String> totals = new LinkedHashMap<>();
            totals.put("receivedTotal", latest.getReceivedTotal().toPlainString());
            totals.put("billedTotal", latest.getBilledTotal().toPlainString());
            totals.put("currencyCode", latest.getCurrencyCode());
            checks.add(new VendorBillReview.Check(
                    CHECK_WITHIN_PRICE_TOLERANCE,
                    latest.isWithinTolerance() ? VendorBillCheckOutcome.PASS : VendorBillCheckOutcome.FAIL,
                    totals));
            return checks;
        }
        Map<String, String> args = new LinkedHashMap<>();
        args.put(
                "reason",
                channel == VendorBillReview.Channel.GOODS_RECEIPT ? "INVOICE_NOT_MATCHED" : "NO_DELIVERY_RECORDED");
        checks.add(new VendorBillReview.Check(CHECK_MATCHED_TO_DELIVERY, VendorBillCheckOutcome.FAIL, args));
        checks.add(new VendorBillReview.Check(
                CHECK_WITHIN_PRICE_TOLERANCE, VendorBillCheckOutcome.NOT_APPLICABLE, Map.of()));
        return checks;
    }

    /**
     * The decisions valid for {@code status} whose permission the caller holds (P5). {@code blockedReason} is S13's;
     * an approved bill with an allocation is not voidable, so its void is not listed.
     */
    static @NonNull List<VendorBillReview.AvailableAction> availableActions(
            @NonNull VendorBillStatus status, boolean hasOpenCandidates, boolean allocated) {
        List<VendorBillReview.AvailableAction> actions = new ArrayList<>();
        switch (status) {
            case PENDING_RECEIPT_MATCH -> {
                offer(actions, VendorBillAction.SUBMIT_FOR_APPROVAL);
                if (hasOpenCandidates) {
                    offer(actions, VendorBillAction.SELECT_CANDIDATE);
                }
            }
            case MATCH_EXCEPTION -> {
                offer(actions, VendorBillAction.SUBMIT_FOR_APPROVAL);
                offer(actions, VendorBillAction.ACCEPT_EXCEPTION);
                offer(actions, VendorBillAction.CORRECT_EXCEPTION);
                offer(actions, VendorBillAction.VOID_EXCEPTION);
                if (hasOpenCandidates) {
                    offer(actions, VendorBillAction.SELECT_CANDIDATE);
                }
            }
            case AWAITING_APPROVAL -> {
                offer(actions, VendorBillAction.APPROVE);
                offer(actions, VendorBillAction.REJECT);
            }
            case APPROVED -> {
                if (!allocated) {
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

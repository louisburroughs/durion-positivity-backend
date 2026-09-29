package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.AdjustmentReverseRequest;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationAdjustmentResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.AdjustmentStatus;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchOrigin;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.Justification;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.AdjustmentRules.Links;
import com.positivity.accounting.internal.bankrec.service.MatchWriter.Header;
import com.positivity.accounting.internal.bankrec.service.MatchWriter.LedgerMember;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.exception.AdjustmentSignInvalidException;
import com.positivity.accounting.internal.exception.GLAccountNotActiveException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.AccountingPeriodGate;
import com.positivity.accounting.internal.service.GLMappingResolver;
import com.positivity.accounting.internal.service.IdempotencyService;
import com.positivity.accounting.internal.service.JournalEntryService;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciliation adjustments (SPEC-manual-bank-reconciliation §3.5, §4.2, §4.6, §4.7, §4.9; story S4, #2303).
 *
 * <p>An adjustment posts a balanced journal entry: positive → Dr reconciled cash / Cr counter; negative → Dr
 * counter / Cr reconciled cash. The counter is the type's {@code BANK_RECONCILIATION} mapping at the posting
 * date, except for a {@code TRANSFER}, whose counter is {@code counterGlAccountId} itself (D9). No new mapping
 * exists: residual settlements and gap bridges are {@code OTHER} → 2360.
 *
 * <p>Idempotent (G9): the adjustment id is minted before posting, the entry's {@code sourceEventId} is
 * {@code nameUUIDFromBytes("BANK_RECONCILIATION_ADJUSTMENT:" + adjustmentId)}, and an
 * {@code IdempotencyService} key of the same text is registered with the entry; a replayed {@code requestId}
 * returns the original. Every posting goes through {@link AccountingPeriodGate} (§5.9).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ReconciliationAdjustmentServiceImpl implements ReconciliationAdjustmentService {

    static final String POSTING_CATEGORY = "BANK_RECONCILIATION";
    static final String IDEMPOTENCY_PREFIX = "BANK_RECONCILIATION_ADJUSTMENT:";
    static final String RESIDUAL_SETTLED = "RESIDUAL_SETTLED";
    private static final String JUSTIFICATION = "justification";

    private final ReconciliationSupport support;
    private final ReconciliationEligibility eligibility;
    private final ReconciliationCalculator calculator;
    private final ReconciliationLedger ledger;
    private final MatchWriter writer;
    private final BankReconciliationAdjustmentRepository adjustments;
    private final BankReconciliationMatchRepository matches;
    private final BankReconciliationGlMatchRepository glMatches;
    private final BankReconciliationBankMatchRepository bankMatches;
    private final BankTransactionRepository transactions;
    private final BankStatementRepository statements;
    private final GLAccountRepository glAccounts;
    private final GLMappingResolver glMappingResolver;
    private final JournalEntryService journalEntryService;
    private final IdempotencyService idempotencyService;
    private final AccountingPeriodGate periodGate;
    private final BankRecPolicy policy;
    private final BankRecAuditRecorder audit;
    private final FunctionalCurrency currency;

    /** The deterministic source event of an adjustment's journal entry (§3.5, G9). */
    public static @NonNull UUID sourceEventId(@NonNull UUID adjustmentId) {
        return UUID.nameUUIDFromBytes((IDEMPOTENCY_PREFIX + adjustmentId).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public @NonNull BankReconciliationAdjustmentResponse addAdjustment(
            @NonNull UUID reconciliationId, @NonNull ReconciliationAdjustmentRequest request) {
        BankReconciliation recon = support.require(reconciliationId);
        BankReconciliationAdjustment replay =
                adjustments.findByRequestId(request.getRequestId()).orElse(null);
        if (replay != null) {
            return replay(replay, reconciliationId, request);
        }
        ReconciliationSupport.requireStatus(recon, ReconciliationStatus.IN_PROGRESS);
        BankAdjustmentType type = request.getType();
        Links links = new Links(
                request.getBankTransactionId(),
                request.getSettlesMatchId(),
                request.getBridgesStatementId(),
                request.getCounterGlAccountId());
        AdjustmentRules.requireLinks(type, links);
        String justification = type == BankAdjustmentType.OTHER
                ? Justification.requiredByRule(request.getJustification(), JUSTIFICATION)
                : Justification.optional(request.getJustification(), JUSTIFICATION);

        // The explaining evidence, and the server-computed amount of a residual or a bridge (§3.5).
        BankReconciliationMatch settled = null;
        List<BankTransaction> settledBank = List.of();
        BigDecimal amount;
        LocalDate explainingDate = recon.getStatementEndDate();
        if (links.settlesMatchId() != null) {
            settled = matches.findByMatchIdAndReconciliationId(links.settlesMatchId(), reconciliationId)
                    .filter(m -> m.getState() == MatchState.ACCEPTED)
                    .filter(m ->
                            m.getToleranceUsed() != null && m.getToleranceUsed().signum() != 0)
                    .orElseThrow(() -> notEligible(
                            "settlesMatchId must name an ACCEPTED match of this reconciliation with a residual",
                            "settlesMatchId"));
            amount = settled.getBankTotal().subtract(settled.getLedgerTotal());
            requireSentAmountEquals(request.getAmount(), amount, "the match residual");
            settledBank = transactions.findAllById(bankMatches.findByMatchIdAndActiveTrue(settled.getMatchId()).stream()
                    .map(BankReconciliationBankMatch::getBankTransactionId)
                    .toList());
            explainingDate = settledBank.stream()
                    .map(BankTransaction::getTransactionDate)
                    .max(Comparator.naturalOrder())
                    .orElse(explainingDate);
        } else if (links.bridgesStatementId() != null) {
            amount = bridgeAmount(recon, links.bridgesStatementId(), request.getAmount());
            explainingDate = recon.getStatementStartDate().minusDays(1);
        } else {
            if (request.getAmount() == null) {
                throw BankRecException.field(
                        BankRecErrorCode.VALIDATION_ERROR, "amount is required", "amount", "is required");
            }
            amount = request.getAmount();
        }
        if (type == BankAdjustmentType.TRANSFER) {
            requireCounterAccount(recon, links.counterGlAccountId());
        }
        if (!type.permits(amount.signum())) {
            throw new AdjustmentSignInvalidException(type);
        }
        BankTransaction bankRow = null;
        if (links.bankTransactionId() != null) {
            bankRow = eligibility
                    .lockBankForMatch(recon, List.of(links.bankTransactionId()), null)
                    .get(0);
            // M5 (§3.4): the ADJUSTMENT match is exact (toleranceUsed = 0), so no difference is accepted — not even
            // one minor unit. Compared by value, scale-insensitive.
            if (bankRow.getSignedAmount().compareTo(amount) != 0) {
                throw notEligible(
                        "The adjustment of " + amount + " must equal bank transaction " + bankRow.getBankTransactionId()
                                + " of " + bankRow.getSignedAmount() + " exactly",
                        "amount");
            }
            explainingDate = bankRow.getTransactionDate();
        }
        if (type == BankAdjustmentType.OTHER
                && AdjustmentRules.otherNeedsApproval(amount, policy.otherApprovalThreshold(), settled != null)
                && !support.hasAuthority(AccountingPermissions.RECONCILIATION_APPROVE)) {
            throw new BankRecException(
                    BankRecErrorCode.RECONCILIATION_ADJUSTMENT_APPROVAL_REQUIRED,
                    "An OTHER adjustment of " + amount.abs() + " needs " + AccountingPermissions.RECONCILIATION_APPROVE
                            + " under the tenant's " + BankRecPolicy.OTHER_APPROVAL_THRESHOLD);
        }
        LocalDate date =
                AdjustmentRules.postingDate(explainingDate, request.getTransactionDate(), periodGate::isPostingBlocked);

        // Post.
        UUID adjustmentId = UUIDv7Generator.generate();
        JournalEntryResponse posted = post(recon, type, amount, date, adjustmentId, links, request);
        String actor = support.currentUser();
        BankReconciliationAdjustment adjustment = new BankReconciliationAdjustment();
        adjustment.setAdjustmentId(adjustmentId);
        adjustment.setReconciliation(recon);
        adjustment.setAdjustmentType(type);
        adjustment.setAmount(amount);
        adjustment.setDescription(request.getDescription());
        adjustment.setJournalEntryId(posted.getJournalEntryId());
        adjustment.setStatus(AdjustmentStatus.POSTED);
        adjustment.setRequestId(request.getRequestId());
        adjustment.setRequestHash(requestHash(reconciliationId, request));
        adjustment.setTransactionDate(date);
        adjustment.setPostedPeriodCode(YearMonth.from(date).toString());
        adjustment.setBankTransactionId(links.bankTransactionId());
        adjustment.setSettlesMatchId(links.settlesMatchId());
        adjustment.setBridgesStatementId(links.bridgesStatementId());
        adjustment.setCounterGlAccountId(links.counterGlAccountId());
        adjustment.setJustification(justification);
        adjustment.setOverrideJustification(blankToNull(request.getOverrideJustification()));
        BankReconciliationAdjustment saved;
        try {
            // The request unique and the one-POSTED-bridge-per-statement partial unique are the backstops for
            // two requests racing past the checks above.
            saved = adjustments.saveAndFlush(adjustment);
        } catch (DataIntegrityViolationException e) {
            String constraint = String.valueOf(e.getMostSpecificCause().getMessage());
            if (constraint.contains("bridge")) {
                throw new BankRecException(
                        BankRecErrorCode.ADJUSTMENT_BRIDGE_ALREADY_POSTED,
                        "A gap bridge for statement " + links.bridgesStatementId() + " was concurrently posted");
            }
            throw new BankRecException(
                    BankRecErrorCode.IDEMPOTENCY_CONFLICT,
                    "requestId " + request.getRequestId() + " was concurrently used by another command");
        }

        // Link the cash line (§4.6, §4.7): an ADJUSTMENT match to the bank transaction, or a residual's replacement.
        LedgerMember cashLine = cashLine(recon, posted.getJournalEntryId());
        UUID matchId = null;
        String oldValue = null;
        if (bankRow != null) {
            matchId = writer.create(
                            recon,
                            new Header(
                                    MatchKind.ADJUSTMENT,
                                    MatchState.ACCEPTED,
                                    MatchOrigin.USER,
                                    null,
                                    null,
                                    justification,
                                    null,
                                    null),
                            List.of(bankRow),
                            List.of(cashLine),
                            actor)
                    .getMatchId();
        } else if (settled != null) {
            matchId = replaceSettledMatch(recon, settled, settledBank, cashLine, justification, actor);
            oldValue = "matchId=" + settled.getMatchId();
        }

        audit.record(
                BankRecAuditRecorder.BANK_RECONCILIATION,
                reconciliationId,
                BankRecAuditRecorder.RECONCILIATION_ADJUSTMENT,
                actor,
                justification,
                oldValue,
                "adjustmentId=" + adjustmentId + ";type=" + type + ";amount=" + amount.toPlainString()
                        + ";journalEntryId=" + posted.getJournalEntryId() + ";transactionDate=" + date
                        + ";override=" + (adjustment.getOverrideJustification() != null) + ";link=" + linkText(links)
                        + (matchId != null ? ";matchId=" + matchId : ""));
        support.refresh(recon);
        log.info(
                "Posted {} adjustment {} of {} on reconciliation {} (JE {}, dated {})",
                type,
                adjustmentId,
                amount,
                reconciliationId,
                posted.getJournalEntryId(),
                date);
        BankReconciliationAdjustmentResponse response = BankReconciliationAdjustmentResponse.from(saved);
        response.setEntryNumber(posted.getEntryNumber());
        response.setMatchId(matchId);
        return response;
    }

    @Override
    public @NonNull BankReconciliationAdjustmentResponse reverse(
            @NonNull UUID reconciliationId, @NonNull UUID adjustmentId, @NonNull AdjustmentReverseRequest request) {
        BankReconciliation recon = support.require(reconciliationId);
        // A correction path (§4.9 path 2): the approver may reverse while the reconciliation is prepared, awaits
        // approval, or is FINALIZED — then the ledger-change hook invalidates it (§5.5; S5, #2304).
        ReconciliationSupport.requireStatus(
                recon,
                ReconciliationStatus.IN_PROGRESS,
                ReconciliationStatus.SUBMITTED,
                ReconciliationStatus.FINALIZED);
        String reason = Justification.required(request.getReason(), "reason");
        BankReconciliationAdjustment adjustment = adjustments
                .findByAdjustmentIdAndReconciliation_ReconciliationId(adjustmentId, reconciliationId)
                .orElseThrow(() -> new ReconciliationNotFoundException(
                        "Adjustment " + adjustmentId + " not found in reconciliation " + reconciliationId));
        if (adjustment.getStatus() == AdjustmentStatus.REVERSED) {
            throw new BankRecException(
                    BankRecErrorCode.ADJUSTMENT_ALREADY_REVERSED,
                    "Adjustment " + adjustmentId + " is already reversed");
        }

        // The ADJUSTMENT match is unmatched and its bank transaction returns to UNMATCHED (§3.8, §4.9 path 2),
        // before the reversal posts, so the ledger-change hook finds only what it must break: a residual's
        // replacement match. A FINALIZED reconciliation's matches are sealed (M7), so there the hook breaks the
        // ADJUSTMENT match too and invalidates the reconciliation (§5.5).
        String actor = support.currentUser();
        LedgerMember cashLine = cashLine(recon, adjustment.getJournalEntryId());
        if (recon.getStatus() != ReconciliationStatus.FINALIZED) {
            for (BankReconciliationGlMatch member :
                    glMatches.findByGlLineIdInAndActiveTrue(List.of(cashLine.glLineId()))) {
                matches.findById(member.getMatchId())
                        .filter(m -> m.getMatchKind() == MatchKind.ADJUSTMENT && m.getState() == MatchState.ACCEPTED)
                        .ifPresent(m -> writer.end(m, MatchState.UNMATCHED, "Adjustment reversed: " + reason, actor));
            }
        }
        JournalEntryResponse reversal = journalEntryService.reverseJournalEntry(
                adjustment.getJournalEntryId(),
                reason,
                request.getReversalDate(),
                blankToNull(request.getOverrideJustification()));

        adjustment.setStatus(AdjustmentStatus.REVERSED);
        adjustment.setReversalJournalEntryId(reversal.getJournalEntryId());
        adjustment.setReversedAt(support.now());
        adjustment.setReversedBy(actor);
        adjustment.setReversalReason(reason);
        adjustments.save(adjustment);
        audit.record(
                BankRecAuditRecorder.BANK_RECONCILIATION,
                reconciliationId,
                BankRecAuditRecorder.RECONCILIATION_ADJUSTMENT_REVERSE,
                actor,
                reason,
                "adjustmentId=" + adjustmentId + ";status=" + AdjustmentStatus.POSTED,
                "status=" + AdjustmentStatus.REVERSED + ";reversalJournalEntryId=" + reversal.getJournalEntryId());
        if (recon.getStatus() == ReconciliationStatus.IN_PROGRESS
                || recon.getStatus() == ReconciliationStatus.SUBMITTED) {
            // An approved (now INVALIDATED) reconciliation keeps the terms it was approved on.
            support.refresh(recon);
        }
        BankReconciliationAdjustmentResponse response = BankReconciliationAdjustmentResponse.from(adjustment);
        response.setEntryNumber(null);
        return response;
    }

    // ---- posting ---------------------------------------------------------------------------------

    private JournalEntryResponse post(
            BankReconciliation recon,
            BankAdjustmentType type,
            BigDecimal amount,
            LocalDate date,
            UUID adjustmentId,
            Links links,
            ReconciliationAdjustmentRequest request) {
        UUID cash = recon.getGlAccountId();
        UUID counter = type == BankAdjustmentType.TRANSFER
                ? links.counterGlAccountId()
                : glMappingResolver.resolveGLAccount(POSTING_CATEGORY, type.name(), date.atStartOfDay());
        String description = "Bank reconciliation adjustment (" + type + ")"
                + (request.getDescription() != null && !request.getDescription().isBlank()
                        ? ": " + request.getDescription()
                        : "");
        BigDecimal abs = amount.abs();
        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();
        // Positive increases the reconciled cash (Dr cash / Cr counter); negative decreases it (Dr counter / Cr cash).
        if (amount.signum() > 0) {
            lines.add(line(cash, abs, BigDecimal.ZERO, description));
            lines.add(line(counter, BigDecimal.ZERO, abs, description));
        } else {
            lines.add(line(counter, abs, BigDecimal.ZERO, description));
            lines.add(line(cash, BigDecimal.ZERO, abs, description));
        }
        JournalEntryResponse created = journalEntryService.createJournalEntry(JournalEntryCreateRequest.builder()
                .transactionDate(date.atStartOfDay())
                .sourceEventId(sourceEventId(adjustmentId))
                .sourceEventType(POSTING_CATEGORY)
                .description(description)
                .lines(lines)
                .build());
        JournalEntryResponse posted = journalEntryService.postJournalEntry(
                created.getJournalEntryId(), blankToNull(request.getOverrideJustification()));
        idempotencyService.registerKey(IDEMPOTENCY_PREFIX + adjustmentId, posted.getJournalEntryId());
        return posted;
    }

    private static JournalEntryCreateRequest.JournalEntryLineRequest line(
            UUID account, BigDecimal debit, BigDecimal credit, String description) {
        return JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                .glAccountId(account)
                .debitAmount(debit)
                .creditAmount(credit)
                .description(description)
                .build();
    }

    /** The adjustment entry's line on the reconciled account. */
    private LedgerMember cashLine(BankReconciliation recon, UUID journalEntryId) {
        return ledger
                .linesOfEntries(recon.getGlAccountId(), List.of(journalEntryId))
                .getOrDefault(journalEntryId, List.of())
                .stream()
                .findFirst()
                .map(LedgerMember::of)
                .orElseThrow(() -> new IllegalStateException(
                        "Adjustment entry " + journalEntryId + " has no line on the reconciled account"));
    }

    /**
     * §4.6: in the posting transaction the settled match becomes UNMATCHED with {@code RESIDUAL_SETTLED} and an
     * ACCEPTED replacement takes the same members plus the adjustment's cash line, the same kind, a zero
     * tolerance and {@code replacesMatchId}. No member is unexplained between the two steps.
     */
    private UUID replaceSettledMatch(
            BankReconciliation recon,
            BankReconciliationMatch settled,
            List<BankTransaction> bankRows,
            LedgerMember cashLine,
            String justification,
            String actor) {
        List<LedgerMember> ledgerMembers =
                new ArrayList<>(glMatches.findByMatchIdAndActiveTrue(settled.getMatchId()).stream()
                        .map(m -> new LedgerMember(m.getGlLineId(), m.getSignedAmount()))
                        .toList());
        writer.end(settled, MatchState.UNMATCHED, RESIDUAL_SETTLED, actor);
        ledgerMembers.add(cashLine);
        return writer.create(
                        recon,
                        new Header(
                                settled.getMatchKind(),
                                MatchState.ACCEPTED,
                                MatchOrigin.USER,
                                null,
                                null,
                                justification,
                                null,
                                settled.getMatchId()),
                        bankRows,
                        ledgerMembers,
                        actor)
                .getMatchId();
    }

    // ---- links -----------------------------------------------------------------------------------

    /** §4.2 step 3: the bridge amount is the live opening difference; one POSTED bridge per statement. */
    private BigDecimal bridgeAmount(BankReconciliation recon, UUID statementId, @Nullable BigDecimal sent) {
        if (!statementId.equals(recon.getStatementId())) {
            throw notEligible("bridgesStatementId must name this reconciliation's statement", "bridgesStatementId");
        }
        BankStatement statement = statements
                .findById(statementId)
                .orElseThrow(() -> notEligible("Statement " + statementId + " not found", "bridgesStatementId"));
        if (statement.getGapAcknowledgement() == null) {
            throw notEligible("Statement " + statementId + " carries no gap acknowledgement", "bridgesStatementId");
        }
        if (adjustments.existsByBridgesStatementIdAndStatus(statementId, AdjustmentStatus.POSTED)) {
            throw new BankRecException(
                    BankRecErrorCode.ADJUSTMENT_BRIDGE_ALREADY_POSTED,
                    "Statement " + statementId + " already has a POSTED gap bridge; reverse it to post another");
        }
        BigDecimal openingDifference = calculator.compute(recon).terms().openingDifference();
        if (openingDifference == null
                || ReconciliationEquation.withinTolerance(openingDifference, currency.tolerance())) {
            throw notEligible(
                    "The opening difference " + openingDifference + " is within tolerance; there is no gap to bridge",
                    "bridgesStatementId");
        }
        requireSentAmountEquals(sent, openingDifference, "the opening difference");
        return openingDifference;
    }

    /** D9: the counter of a TRANSFER is a known, active, reconcilable BANK_CASH account other than the reconciled one. */
    private void requireCounterAccount(BankReconciliation recon, UUID counterGlAccountId) {
        GLAccount counter = glAccounts
                .findById(counterGlAccountId)
                .filter(a -> !a.getGlAccountId().equals(recon.getGlAccountId()))
                .orElseThrow(() -> notEligible(
                        "counterGlAccountId must name another GL account of the tenant", "counterGlAccountId"));
        if (!"ACTIVE".equals(counter.getDerivedStatus())) {
            throw new GLAccountNotActiveException("Counter account " + counter.getAccountCode() + " is not active");
        }
        if (!counter.isReconcilable() || counter.getAccountSubtype() != AccountSubtype.BANK_CASH) {
            throw new BankRecException(
                    BankRecErrorCode.ACCOUNT_NOT_RECONCILABLE,
                    "Counter account " + counter.getAccountCode() + " is not a reconcilable BANK_CASH account");
        }
    }

    private static void requireSentAmountEquals(@Nullable BigDecimal sent, BigDecimal computed, String what) {
        if (sent != null && sent.compareTo(computed) != 0) {
            throw notEligible(
                    "The amount is " + what + ", " + computed.toPlainString() + "; omit it or send exactly that",
                    "amount");
        }
    }

    // ---- replay ----------------------------------------------------------------------------------

    /**
     * The canonical hash of the whole adjustment command (§6.3): every field that steers the posting — amount,
     * description, links, justification, requested date, override — so a reused {@code requestId} with any changed
     * instruction is a conflict, not a replay. Justifications are compared as the service stores them (trimmed).
     */
    static @NonNull String requestHash(
            @NonNull UUID reconciliationId, @NonNull ReconciliationAdjustmentRequest request) {
        return new CanonicalRequestHash()
                .field(reconciliationId)
                .field(request.getType())
                .field(request.getAmount())
                .field(request.getDescription())
                .field(request.getBankTransactionId())
                .field(request.getSettlesMatchId())
                .field(request.getBridgesStatementId())
                .field(request.getCounterGlAccountId())
                .field(
                        request.getJustification() == null
                                ? null
                                : request.getJustification().trim())
                .field(request.getTransactionDate())
                .field(blankToNull(request.getOverrideJustification()))
                .digest();
    }

    private BankReconciliationAdjustmentResponse replay(
            BankReconciliationAdjustment original, UUID reconciliationId, ReconciliationAdjustmentRequest request) {
        boolean same = original.getRequestHash() != null
                ? original.getRequestHash().equals(requestHash(reconciliationId, request))
                : sameLinksAndAmount(original, reconciliationId, request);
        if (!same) {
            throw new BankRecException(
                    BankRecErrorCode.IDEMPOTENCY_CONFLICT,
                    "requestId " + request.getRequestId() + " was already used with a different payload");
        }
        BankReconciliationAdjustmentResponse response = BankReconciliationAdjustmentResponse.from(original);
        response.setEntryNumber(journalEntryService
                .getJournalEntry(original.getJournalEntryId())
                .getEntryNumber());
        response.setReplayed(true);
        return response;
    }

    /** The comparison left for a row written before its request hash was kept. */
    private static boolean sameLinksAndAmount(
            BankReconciliationAdjustment original, UUID reconciliationId, ReconciliationAdjustmentRequest request) {
        return reconciliationId.equals(original.getReconciliationId())
                && original.getAdjustmentType() == request.getType()
                && (request.getAmount() == null || request.getAmount().compareTo(original.getAmount()) == 0)
                && Objects.equals(original.getBankTransactionId(), request.getBankTransactionId())
                && Objects.equals(original.getSettlesMatchId(), request.getSettlesMatchId())
                && Objects.equals(original.getBridgesStatementId(), request.getBridgesStatementId())
                && Objects.equals(original.getCounterGlAccountId(), request.getCounterGlAccountId());
    }

    private static String linkText(Links links) {
        if (links.bankTransactionId() != null) {
            return "bankTransactionId=" + links.bankTransactionId();
        }
        if (links.settlesMatchId() != null) {
            return "settlesMatchId=" + links.settlesMatchId();
        }
        if (links.bridgesStatementId() != null) {
            return "bridgesStatementId=" + links.bridgesStatementId();
        }
        return links.counterGlAccountId() != null ? "counterGlAccountId=" + links.counterGlAccountId() : "none";
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BankRecException notEligible(String message, String field) {
        return BankRecException.field(BankRecErrorCode.ADJUSTMENT_LINK_NOT_ELIGIBLE, message, field, "not eligible");
    }
}

package com.positivity.accounting.internal.bankrec.intake;

import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.FeedChange;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts;
import com.positivity.accounting.internal.bankrec.service.BankRecAuditRecorder;
import com.positivity.accounting.internal.bankrec.service.BankRecCalendar;
import com.positivity.accounting.internal.bankrec.service.BankStatementFacts;
import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.bankrec.service.StatementSupersession;
import com.positivity.domainevents.accounting.BankStatementCommittedV1;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.BankTransactionObserved;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.Change;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.StatementHeader;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The intake port's implementation (SPEC-manual-bank-reconciliation §2.1, §3.1, §3.2, §4.2–§4.5;
 * story S2, #2301). One {@link #accept} call is one transaction: a refusal persists nothing.
 *
 * <p>Checks run in the order of the story: account (D5), currency (D18), then — when the batch
 * carries a statement header — header dates, the acknowledgement's shape (§4.2 step 1), U1, U2,
 * contiguity E2 with the acknowledgement (§4.2 steps 2–3), the header window (§4.3) and E1. Only
 * then is anything written.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BankTransactionIntakeImpl implements BankTransactionIntake {

    /** Rows that never raise a fingerprint collision at intake (R1, §4.5). */
    private static final Set<BankTransactionStatus> NOT_COLLIDING =
            EnumSet.of(BankTransactionStatus.EXCLUDED, BankTransactionStatus.REMOVED_BY_SOURCE);

    private static final String OVERLAP_CONSTRAINT = "bank_statement_no_overlap_ex";
    private static final String WINDOW_CONSTRAINT = "bank_statement_committed_window_uk";
    private static final String REQUEST_CONSTRAINT = "bank_statement_request_uk";

    private final BankCashAccounts bankCashAccounts;
    private final FunctionalCurrency functionalCurrency;
    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;
    private final BankAccountProfileRepository profiles;
    private final BankReconciliationRepository reconciliations;
    private final BankRecAuditRecorder audit;
    private final BankStatementFacts facts;
    private final StatementSupersession supersession;
    private final Clock clock;
    private final BankRecCalendar calendar;

    @Override
    public @NonNull IntakeResult accept(@NonNull BankTransactionsObservedV1 batch, @NonNull IntakeContext ctx) {
        UUID glAccountId = ctx.glAccountId();
        SourceKind sourceKind = SourceKind.valueOf(batch.sourceKind().name());

        // (a) account, (b) currency.
        bankCashAccounts.requireForIntake(glAccountId);
        Optional<BankAccountProfile> existingProfile = profiles.findById(glAccountId);
        String accountCurrency =
                existingProfile.map(BankAccountProfile::getCurrency).orElseGet(functionalCurrency::code);
        requireCurrency(batch, accountCurrency);
        requireMinorUnit(batch, accountCurrency);

        // (c) statement header, (e) E1 — all before anything is written. A corrected statement names the one it
        // supersedes and is checked with that one left out (§4.9 path 3; S5, #2304).
        StatementHeader header = batch.statement();
        StatementSupersession.@Nullable Request supersede = null;
        if (ctx.supersedesStatementId() != null || ctx.supersessionJustification() != null) {
            if (header == null) {
                throw BankRecException.field(
                        BankRecErrorCode.VALIDATION_ERROR,
                        "A supersession needs a statement header",
                        "supersedesStatementId",
                        "only a statement supersedes a statement");
            }
            supersede = supersession.lockEligible(
                    glAccountId, ctx.supersedesStatementId(), ctx.supersessionJustification());
        }
        UUID superseded = supersede == null ? null : supersede.superseded().getStatementId();
        @Nullable String acknowledgement = null;
        BigDecimal activityTotal = activityTotal(batch);
        if (header != null) {
            acknowledgement = checkHeader(batch, header, ctx, glAccountId, sourceKind, superseded);
            requireActivityTies(header, activityTotal);
        }

        Instant now = Instant.now(clock);
        if (supersede != null) {
            // Before the corrected statement is written: U1, U2 and R1 must no longer see the old one.
            supersession.retire(supersede, ctx.actor());
        }
        BankStatement statement = null;
        if (header != null) {
            statement = commitStatement(batch, header, ctx, sourceKind, activityTotal, acknowledgement, now);
        }
        if (supersede != null && statement != null) {
            supersession.link(supersede, statement.getStatementId(), ctx.actor());
        }

        // (d) transactions.
        Tally tally = storeTransactions(batch, ctx, sourceKind, statement);

        // (g) profile and baseline.
        BankAccountProfile profile = existingProfile.orElseGet(() -> createProfile(ctx, accountCurrency));
        boolean baselineChanged = false;
        if (supersede != null) {
            // §3.1: recomputed from the COMMITTED statements, audited only when it changes.
            baselineChanged =
                    setBaseline(profile, latestAcknowledgedStart(glAccountId), ctx.actor(), supersede.justification());
        } else if (acknowledgement != null) {
            baselineChanged = applyBaseline(profile, ctx.actor(), acknowledgement);
        }

        if (statement != null) {
            facts.committed(
                    new BankStatementCommittedV1(
                            statement.getStatementId(),
                            glAccountId,
                            statement.getStartDate(),
                            statement.getEndDate(),
                            statement.getOpeningBalance(),
                            statement.getClosingBalance(),
                            statement.getCurrency(),
                            tally.linkedToStatement,
                            batch.sourceKind()),
                    ctx.actor());
        }

        log.info(
                "Bank intake account={} source={} statement={} rows={} duplicates={} modified={}",
                glAccountId,
                sourceKind,
                statement == null ? null : statement.getStatementId(),
                tally.ids.size(),
                tally.possibleDuplicates,
                tally.modified);
        return new IntakeResult(
                statement == null ? null : statement.getStatementId(),
                tally.ids,
                tally.ids.size(),
                tally.possibleDuplicates,
                tally.modified,
                profile.getReconciliationBaselineDate(),
                baselineChanged);
    }

    @Override
    public @NonNull Optional<LocalDate> recomputeBaseline(
            @NonNull UUID glAccountId, @NonNull String actor, @Nullable String justification) {
        Optional<BankAccountProfile> profile = profiles.findById(glAccountId);
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        LocalDate recomputed = latestAcknowledgedStart(glAccountId);
        setBaseline(profile.get(), recomputed, actor, justification);
        return Optional.ofNullable(profile.get().getReconciliationBaselineDate());
    }

    // ---- checks ----------------------------------------------------------------------------

    private static void requireCurrency(BankTransactionsObservedV1 batch, String accountCurrency) {
        if (!accountCurrency.equals(batch.currency())) {
            throw BankRecException.field(
                    BankRecErrorCode.CURRENCY_NOT_SUPPORTED,
                    "Currency " + batch.currency() + " is not the account's currency " + accountCurrency,
                    "currency",
                    "expected " + accountCurrency);
        }
        List<BankTransactionObserved> rows = batch.transactions();
        for (int i = 0; i < rows.size(); i++) {
            String rowCurrency = rows.get(i).currency();
            if (rowCurrency != null && !accountCurrency.equals(rowCurrency)) {
                throw BankRecException.field(
                        BankRecErrorCode.CURRENCY_NOT_SUPPORTED,
                        "Currency " + rowCurrency + " is not the account's currency " + accountCurrency,
                        "transactions[" + i + "].currency",
                        "expected " + accountCurrency);
            }
        }
    }

    /**
     * ADR-0067 PC-6: every balance and amount fits the currency's minor unit; the rest is refused, never
     * rounded, naming each offending field (422 {@code AMOUNT_PRECISION_EXCEEDS_CURRENCY}).
     */
    private static void requireMinorUnit(BankTransactionsObservedV1 batch, String currency) {
        Map<String, String> tooPrecise = new LinkedHashMap<>();
        String detail = MinorUnit.detail(currency);
        StatementHeader header = batch.statement();
        if (header != null) {
            if (header.openingBalance() != null && !MinorUnit.fits(header.openingBalance(), currency)) {
                tooPrecise.put("openingBalance", detail);
            }
            if (header.closingBalance() != null && !MinorUnit.fits(header.closingBalance(), currency)) {
                tooPrecise.put("closingBalance", detail);
            }
        }
        List<BankTransactionObserved> rows = batch.transactions();
        for (int i = 0; i < rows.size(); i++) {
            BigDecimal amount = rows.get(i).signedAmount();
            if (amount != null && !MinorUnit.fits(amount, currency)) {
                tooPrecise.put("transactions[" + i + "].signedAmount", detail);
            }
        }
        if (!tooPrecise.isEmpty()) {
            throw MinorUnit.exceeded(tooPrecise);
        }
    }

    /** Returns the acknowledgement to store (null when none); throws the first refusal. */
    private @Nullable String checkHeader(
            BankTransactionsObservedV1 batch,
            StatementHeader header,
            IntakeContext ctx,
            UUID glAccountId,
            SourceKind sourceKind,
            @Nullable UUID superseded) {
        String acknowledgement = StatementHeaderChecks.check(
                        statements,
                        functionalCurrency,
                        calendar,
                        glAccountId,
                        header,
                        ctx.gapAcknowledgement(),
                        superseded)
                .gapAcknowledgement();

        // §4.3: every row of a file or manual statement lies inside its own window. Feed batches carry
        // no header in phase 1; a later format that does (CAMT.053) is checked the same way.
        if (sourceKind != SourceKind.BANK_FEED) {
            requireRowsInWindow(batch, header);
        }
        return acknowledgement;
    }

    private static void requireRowsInWindow(BankTransactionsObservedV1 batch, StatementHeader header) {
        Map<String, String> outside = new LinkedHashMap<>();
        List<BankTransactionObserved> rows = batch.transactions();
        for (int i = 0; i < rows.size(); i++) {
            LocalDate date = rows.get(i).transactionDate();
            if (date != null && (date.isBefore(header.startDate()) || date.isAfter(header.endDate()))) {
                outside.put(
                        "transactions[" + i + "]",
                        date + " is outside " + header.startDate() + ".." + header.endDate());
            }
        }
        if (!outside.isEmpty()) {
            throw new BankRecException(
                    BankRecErrorCode.STATEMENT_TRANSACTION_OUT_OF_WINDOW,
                    outside.size() + " transaction(s) dated outside the statement window",
                    outside);
        }
    }

    private void requireActivityTies(StatementHeader header, BigDecimal activityTotal) {
        BigDecimal expectedClosing = header.openingBalance().add(activityTotal);
        BigDecimal gap = expectedClosing.subtract(header.closingBalance()).abs();
        if (gap.compareTo(functionalCurrency.tolerance()) > 0) {
            throw BankRecException.field(
                    BankRecErrorCode.STATEMENT_ACTIVITY_MISMATCH,
                    "opening + activity does not equal closing",
                    "activityTotal",
                    "opening + activity = " + functionalCurrency.display(expectedClosing) + ", closing = "
                            + functionalCurrency.display(header.closingBalance()));
        }
    }

    private static BigDecimal activityTotal(BankTransactionsObservedV1 batch) {
        BigDecimal total = BigDecimal.ZERO;
        for (BankTransactionObserved row : batch.transactions()) {
            if (row.change() != Change.REMOVED && row.signedAmount() != null) {
                total = total.add(row.signedAmount());
            }
        }
        return total;
    }

    // ---- writes ----------------------------------------------------------------------------

    private BankStatement commitStatement(
            BankTransactionsObservedV1 batch,
            StatementHeader header,
            IntakeContext ctx,
            SourceKind sourceKind,
            BigDecimal activityTotal,
            @Nullable String acknowledgement,
            Instant now) {
        BankStatement statement = new BankStatement();
        statement.setGlAccountId(ctx.glAccountId());
        statement.setSourceKind(sourceKind);
        statement.setSourceRef(ctx.sourceRef());
        statement.setConnectorCode(batch.connectorCode());
        statement.setStatementRef(header.statementRef());
        statement.setStartDate(header.startDate());
        statement.setEndDate(header.endDate());
        statement.setOpeningBalance(header.openingBalance());
        statement.setClosingBalance(header.closingBalance());
        statement.setActivityTotal(activityTotal);
        statement.setCurrency(batch.currency());
        statement.setStatus(BankStatementStatus.COMMITTED);
        statement.setRequestId(ctx.requestId());
        statement.setRequestHash(ctx.requestHash());
        statement.setCreatedBy(ctx.actor());
        if (acknowledgement != null) {
            statement.setGapAcknowledgement(acknowledgement);
            statement.setGapAcknowledgedBy(ctx.actor());
            statement.setGapAcknowledgedAt(now);
        }
        try {
            // Flushed here so a racing commit that slipped past the service checks is refused by the
            // U1 unique index or the U2 exclusion constraint and answered with the same code.
            return statements.saveAndFlush(statement);
        } catch (DataIntegrityViolationException refused) {
            throw translate(refused, header);
        }
    }

    private static RuntimeException translate(DataIntegrityViolationException refused, StatementHeader header) {
        String detail = constraintDetail(refused);
        if (detail.contains(WINDOW_CONSTRAINT)) {
            return new ConcurrentCommitException(
                    BankRecErrorCode.STATEMENT_ALREADY_IMPORTED,
                    "A statement for " + header.startDate() + ".." + header.endDate()
                            + " is already committed on this account");
        }
        if (detail.contains(OVERLAP_CONSTRAINT)) {
            return new ConcurrentCommitException(
                    BankRecErrorCode.STATEMENT_PERIOD_OVERLAP,
                    "The window " + header.startDate() + ".." + header.endDate()
                            + " overlaps a committed statement on this account",
                    Map.of("statementId", "a concurrently committed statement"));
        }
        if (detail.contains(REQUEST_CONSTRAINT)) {
            return new ConcurrentCommitException(
                    BankRecErrorCode.IDEMPOTENCY_CONFLICT, "The requestId was used by a concurrent request");
        }
        return refused;
    }

    private static String constraintDetail(Throwable refused) {
        StringBuilder detail = new StringBuilder();
        for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
            detail.append(cause.getMessage()).append('\n');
            if (cause.getCause() == cause) {
                break;
            }
        }
        return detail.toString();
    }

    /** Running totals of {@link #storeTransactions}. */
    private static final class Tally {
        private final List<UUID> ids = new ArrayList<>();
        private int possibleDuplicates;
        private int modified;
        private int linkedToStatement;
    }

    /** A batch row with its normalized values, computed before anything is written. */
    private record Prepared(
            int index,
            BankTransactionObserved element,
            BigDecimal amount,
            String normalizedDescription,
            String fingerprint) {}

    private Tally storeTransactions(
            BankTransactionsObservedV1 batch,
            IntakeContext ctx,
            SourceKind sourceKind,
            @Nullable BankStatement statement) {
        UUID glAccountId = ctx.glAccountId();
        Instant observedAt = batch.observedAt();
        List<BankTransactionObserved> rows = batch.transactions();

        // Pass 1 — normalize every row and count in-batch fingerprints (R1 across rows of one batch).
        List<Prepared> prepared = new ArrayList<>();
        Map<String, List<Prepared>> byFingerprint = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            BankTransactionObserved row = rows.get(i);
            if (row.change() == Change.REMOVED) {
                prepared.add(new Prepared(i, row, BigDecimal.ZERO, "", ""));
                continue;
            }
            BigDecimal amount = scaled(Objects.requireNonNull(row.signedAmount()), i);
            String normalized = TransactionNormalizer.normalizeDescription(row.description());
            String fingerprint = TransactionNormalizer.fingerprint(
                    glAccountId,
                    Objects.requireNonNull(row.transactionDate()),
                    amount,
                    normalized,
                    row.reference(),
                    row.checkNumber());
            Prepared p = new Prepared(i, row, amount, normalized, fingerprint);
            prepared.add(p);
            byFingerprint.computeIfAbsent(fingerprint, k -> new ArrayList<>()).add(p);
        }

        // Pass 2 — write.
        Tally tally = new Tally();
        Set<UUID> writtenByThisBatch = new HashSet<>();
        Map<String, UUID> firstOfFingerprint = new HashMap<>();
        for (Prepared p : prepared) {
            BankTransactionObserved row = p.element();
            Optional<BankTransaction> sameSource = row.sourceTransactionId() == null
                    ? Optional.empty()
                    : transactions.findFirstByGlAccountIdAndSourceKindAndSourceRefAndSourceTransactionId(
                            glAccountId, sourceKind, ctx.sourceRef(), row.sourceTransactionId());

            if (row.change() == Change.REMOVED) {
                sameSource.ifPresent(existing -> markRemoved(existing, observedAt));
                continue;
            }
            if (sameSource.isPresent()) {
                // U3: same source, same id → update the retained fields, never a second row.
                BankTransaction existing = sameSource.get();
                applyObserved(existing, p, glAccountId, sourceKind, ctx);
                if (existing.getStatus() == BankTransactionStatus.UNMATCHED
                        || existing.getStatus() == BankTransactionStatus.POSSIBLE_DUPLICATE) {
                    // The update may change the fingerprint: R1 again, never against the row itself.
                    Set<UUID> skip = new HashSet<>(writtenByThisBatch);
                    skip.add(existing.getBankTransactionId());
                    UUID original =
                            duplicateOf(p, glAccountId, sourceKind, ctx, skip, byFingerprint, firstOfFingerprint);
                    flag(
                            existing,
                            original,
                            collidesInBatch(byFingerprint.get(p.fingerprint())),
                            confirmedDistinct(row, ctx),
                            tally);
                }
                existing.setFeedChange(FeedChange.MODIFIED);
                existing.setLastObservedAt(observedAt);
                if (existing.getStatementId() == null && statement != null) {
                    existing.setStatementId(statement.getStatementId());
                }
                transactions.save(existing);
                tally.ids.add(existing.getBankTransactionId());
                writtenByThisBatch.add(existing.getBankTransactionId());
                firstOfFingerprint.putIfAbsent(p.fingerprint(), existing.getBankTransactionId());
                tally.modified++;
                if (statement != null && statement.getStatementId().equals(existing.getStatementId())) {
                    tally.linkedToStatement++;
                }
                continue;
            }

            BankTransaction created = new BankTransaction();
            created.setGlAccountId(glAccountId);
            created.setStatementId(statement == null ? null : statement.getStatementId());
            created.setSourceKind(sourceKind);
            created.setSourceRef(ctx.sourceRef());
            created.setConnectorCode(batch.connectorCode());
            created.setSourceTransactionId(row.sourceTransactionId());
            created.setSourceRowNumber(row.sourceRowNumber());
            created.setCurrency(batch.currency());
            created.setOriginalDescription(row.originalDescription());
            applyObserved(created, p, glAccountId, sourceKind, ctx);
            created.setFeedChange(FeedChange.valueOf(row.change().name()));
            created.setFirstObservedAt(observedAt);
            created.setLastObservedAt(observedAt);
            created.setCreatedBy(ctx.actor());

            // R1: a fingerprint collision enters as POSSIBLE_DUPLICATE, never a silent drop.
            UUID original =
                    duplicateOf(p, glAccountId, sourceKind, ctx, writtenByThisBatch, byFingerprint, firstOfFingerprint);
            flag(
                    created,
                    original,
                    collidesInBatch(byFingerprint.get(p.fingerprint())),
                    confirmedDistinct(row, ctx),
                    tally);
            // D10: a feed row inside a FINALIZED window is flagged, never refused (phase 2 only — a
            // phase-1 row lies inside its own statement window, and windows never overlap).
            if (sourceKind == SourceKind.BANK_FEED
                    && reconciliations
                            .existsByGlAccount_GlAccountIdAndStatusAndStatementStartDateLessThanEqualAndStatementEndDateGreaterThanEqual(
                                    glAccountId,
                                    ReconciliationStatus.FINALIZED,
                                    created.getTransactionDate(),
                                    created.getTransactionDate())) {
                created.setArrivedAfterApproval(true);
            }
            BankTransaction saved = transactions.save(created);
            tally.ids.add(saved.getBankTransactionId());
            writtenByThisBatch.add(saved.getBankTransactionId());
            firstOfFingerprint.putIfAbsent(p.fingerprint(), saved.getBankTransactionId());
            if (statement != null) {
                tally.linkedToStatement++;
            }
        }
        return tally;
    }

    private static BigDecimal scaled(BigDecimal amount, int index) {
        try {
            return TransactionNormalizer.scaleAmount(amount);
        } catch (ArithmeticException tooPrecise) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "Amount " + amount.toPlainString() + " has more than " + TransactionNormalizer.AMOUNT_SCALE
                            + " decimal places",
                    "transactions[" + index + "].signedAmount",
                    "at most " + TransactionNormalizer.AMOUNT_SCALE + " decimal places");
        }
    }

    /** Copies what the source said onto a row (R3: amounts exactly as delivered, 4 dp). */
    private void applyObserved(
            BankTransaction target, Prepared p, UUID glAccountId, SourceKind sourceKind, IntakeContext ctx) {
        BankTransactionObserved row = p.element();
        if (target.getOriginalDescription() == null && target.getDescription() != null) {
            // An upsert that changes the description keeps the first one the source delivered.
            target.setOriginalDescription(target.getDescription());
        }
        target.setSettlementState(SettlementState.valueOf(
                Objects.requireNonNull(row.settlementState()).name()));
        target.setTransactionDate(row.transactionDate());
        target.setAuthorizedDate(row.authorizedDate());
        target.setSignedAmount(p.amount());
        target.setDescription(row.description());
        target.setNormalizedDescription(p.normalizedDescription());
        target.setReference(row.reference());
        target.setCheckNumber(row.checkNumber());
        target.setCounterpartyName(row.counterpartyName());
        target.setCategoryHint(row.categoryHint());
        target.setFingerprint(p.fingerprint());
        if (row.supersedesSourceTransactionId() != null) {
            transactions
                    .findFirstByGlAccountIdAndSourceKindAndSourceRefAndSourceTransactionId(
                            glAccountId, sourceKind, ctx.sourceRef(), row.supersedesSourceTransactionId())
                    .ifPresent(pending -> target.setSupersedesBankTransactionId(pending.getBankTransactionId()));
        }
    }

    /** The row a colliding row points at: the earliest existing one, else the first of its batch. */
    private @Nullable UUID duplicateOf(
            Prepared p,
            UUID glAccountId,
            SourceKind sourceKind,
            IntakeContext ctx,
            Set<UUID> skip,
            Map<String, List<Prepared>> byFingerprint,
            Map<String, UUID> firstOfFingerprint) {
        UUID original = collidingOriginal(p, glAccountId, sourceKind, ctx, skip);
        if (original == null && collidesInBatch(byFingerprint.get(p.fingerprint()))) {
            original = firstOfFingerprint.get(p.fingerprint());
        }
        return original;
    }

    /** R1: a collision leaves the row POSSIBLE_DUPLICATE, never a silent drop; otherwise UNMATCHED. */
    /**
     * Whether a human confirmed this row distinct before the commit (the file adapter's duplicate
     * decisions, §4.4; story S3, #2302): R1 has asked and been answered, so a collision does not flag it.
     */
    private static boolean confirmedDistinct(BankTransactionObserved row, IntakeContext ctx) {
        return row.sourceRowNumber() != null && ctx.confirmedDistinctRows().contains(row.sourceRowNumber());
    }

    private static void flag(
            BankTransaction row,
            @Nullable UUID original,
            boolean inBatchCollision,
            boolean confirmedDistinct,
            Tally tally) {
        if (!confirmedDistinct && (original != null || inBatchCollision)) {
            row.setStatus(BankTransactionStatus.POSSIBLE_DUPLICATE);
            row.setDuplicateOfBankTransactionId(original);
            tally.possibleDuplicates++;
        } else {
            row.setStatus(BankTransactionStatus.UNMATCHED);
            row.setDuplicateOfBankTransactionId(null);
        }
    }

    /**
     * The earliest existing row on the account the new row collides with (R1), or null. {@code
     * EXCLUDED} and {@code REMOVED_BY_SOURCE} rows never collide; nor does a row the same source
     * reported under a different id (both carry ids, so the source itself says they differ).
     */
    private @Nullable UUID collidingOriginal(
            Prepared p, UUID glAccountId, SourceKind sourceKind, IntakeContext ctx, Set<UUID> skip) {
        for (BankTransaction candidate :
                transactions.findByGlAccountIdAndFingerprintAndStatusNotInOrderByFirstObservedAtAscBankTransactionIdAsc(
                        glAccountId, p.fingerprint(), NOT_COLLIDING)) {
            if (skip.contains(candidate.getBankTransactionId())) {
                continue;
            }
            boolean sameSource = candidate.getSourceKind() == sourceKind
                    && Objects.equals(candidate.getSourceRef(), ctx.sourceRef());
            if (sameSource
                    && candidate.getSourceTransactionId() != null
                    && p.element().sourceTransactionId() != null) {
                continue;
            }
            return candidate.getBankTransactionId();
        }
        return null;
    }

    /** Rows of one batch collide unless every one of them carries a source id (then the source distinguishes them). */
    private static boolean collidesInBatch(@Nullable List<Prepared> sameFingerprint) {
        if (sameFingerprint == null || sameFingerprint.size() < 2) {
            return false;
        }
        return sameFingerprint.stream().anyMatch(p -> p.element().sourceTransactionId() == null);
    }

    private void markRemoved(BankTransaction existing, Instant observedAt) {
        existing.setStatus(BankTransactionStatus.REMOVED_BY_SOURCE);
        existing.setFeedChange(FeedChange.REMOVED);
        existing.setRemovedAt(observedAt);
        existing.setLastObservedAt(observedAt);
        transactions.save(existing);
    }

    private BankAccountProfile createProfile(IntakeContext ctx, String currency) {
        // §3.1 values: no bank name or mask (the preparer fills them), the ledger currency (D18 as
        // amended by ADR-0067), the import's mapping only when it asked to save it, nothing else.
        BankAccountProfile profile = new BankAccountProfile(ctx.glAccountId());
        profile.setCurrency(currency);
        profile.setDefaultColumnMapping(ctx.defaultColumnMapping());
        profile.setCreatedBy(ctx.actor());
        return profiles.save(profile);
    }

    private boolean applyBaseline(BankAccountProfile profile, String actor, String acknowledgement) {
        return setBaseline(profile, latestAcknowledgedStart(profile.getGlAccountId()), actor, acknowledgement);
    }

    private @Nullable LocalDate latestAcknowledgedStart(UUID glAccountId) {
        return statements
                .findFirstByGlAccountIdAndStatusAndGapAcknowledgementIsNotNullOrderByStartDateDesc(
                        glAccountId, BankStatementStatus.COMMITTED)
                .map(BankStatement::getStartDate)
                .orElse(null);
    }

    /** Writes and audits the baseline only when it changes (§3.1). */
    private boolean setBaseline(
            BankAccountProfile profile, @Nullable LocalDate baseline, String actor, @Nullable String justification) {
        LocalDate previous = profile.getReconciliationBaselineDate();
        if (Objects.equals(previous, baseline)) {
            return false;
        }
        profile.setReconciliationBaselineDate(baseline);
        profiles.save(profile);
        audit.record(
                BankRecAuditRecorder.BANK_ACCOUNT_PROFILE,
                profile.getGlAccountId(),
                BankRecAuditRecorder.BANK_ACCOUNT_BASELINE_SET,
                actor,
                justification,
                previous == null ? null : previous.toString(),
                baseline == null ? null : baseline.toString());
        return true;
    }
}

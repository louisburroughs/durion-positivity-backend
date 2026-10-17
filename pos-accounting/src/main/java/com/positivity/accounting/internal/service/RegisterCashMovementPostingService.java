package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.RegisterCashMovementTaxRecovery;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.RegisterSessionContractViolationException;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.RegisterCashMovementTaxRecoveryRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.order.RegisterSessionClosedV1.Movement;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Posts the drawer cash movements of a closed register session at close (CAP:550 S17, #2513; spec §4.6 "Movement
 * reasons", §7.1 "Drawer movement posting"; AW15, AW18, AW30), from the close fact's schema-2 {@code movements}
 * (S16, #2512). It follows {@link RegisterOverShortPostingService}: business-time dating, accounts resolved through a
 * posting category, an idempotency key registered with each entry, a foreign currency held, a closed period
 * propagating for retry.
 *
 * <p><b>What posts, per reason</b> (accounts always through the {@code REGISTER_CASH_MOVEMENT} posting category, never
 * by number):
 *
 * <ul>
 *   <li>{@code PETTY_EXPENSE}: {@code Dr PETTY_EXPENSE_<categoryCode> / Cr CASH_CLEARING} (1095) for the movement's
 *       amount, the receipt's gross: sales tax paid is part of the expense and 2200 is never debited (§4.6 "Tax
 *       (US)"). When a stated regime's tax is recovered (S32d item 9, {@link PettyExpenseRecoveryDecider}), {@code Dr
 *       INPUT_TAX_<regime>} takes the recovered part and the expense the rest; each stated regime's outcome is kept in
 *       {@code register_cash_movement_tax_recovery} with the supplier's number as the claim's evidence. A category deactivated after the movement was recorded still resolves: its mapping key stays.
 *   <li>{@code VENDOR_COD} (#2513 COD half, AW41): {@code Dr ACCOUNTS_PAYABLE / Cr CASH_CLEARING} (1095) for the
 *       movement's amount, and in the same transaction an AP payment with method {@code CASH} (gross = unapplied =
 *       the amount, {@code GL_POSTED}, linked to the entry), to which the vendor's approved bill is allocated later
 *       ({@code POST /v1/accounting/ap/payments/{id}/allocations}, which posts nothing). {@code SKIP_VENDOR_COD} is
 *       kept for the record of what the petty-expense half did before this landed; nothing selects it any more.
 *   <li>{@code BANK_DROP}, {@code FLOAT_INCREASE}, {@code FLOAT_DECREASE}: nothing at close (the deposit, S18,
 *       {@link UndepositedSessionProjection}; Change float, S15).
 *   <li>No reason (recorded before S16) or a reason this module does not know: nothing, logged and counted as {@code
 *       UNCLASSIFIED}.
 * </ul>
 *
 * <p><b>Entry shape.</b> One two-line entry per movement, source type {@link
 * JournalEntrySourceTypes#REGISTER_CASH_MOVEMENT}, source event {@code nameUUIDFromBytes("REGISTER_CASH_MOVEMENT:" +
 * movementId)}, dated at the session's {@code closedAt}; both lines carry the dimensions {@code registerId} (the
 * terminal), {@code sessionId} and the session's {@code locationId}. The location is the session's, never the
 * register float's (AW36): a register moves only between sessions.
 *
 * <p><b>Idempotency</b> is per movement: {@code REGISTER_CASH_MOVEMENT_GL_POSTING:<movementId>} is registered in the
 * same transaction as the entry, so a fact redelivered under a fresh envelope id posts nothing twice. The key expires
 * after 24 hours ({@code IdempotencyService}); past that, the entry's deterministic {@code sourceEventId} is the
 * durable backstop, as in {@link InventoryRevaluationPostingService} (non-expiring posting keys: #2595). The checks run
 * in the over/short's order: idempotency, then currency, then the fact's contract.
 *
 * <p><b>Currency (ADR-0067 PC-9).</b> A session whose fact, or any movement to post, is in a currency other than the
 * ledger's posts nothing: it is held once per session as the {@code SUSPENDED / CURRENCY_NOT_SUPPORTED} record the
 * over/short hold writes, and the listener then skips the over/short, so the session posts all or nothing.
 *
 * <p>A closed period, a missing mapping or a petty expense that breaks the close fact's contract (no movement id, a
 * movement id named twice, not {@code OUT}, no category, no positive amount; {@link
 * RegisterSessionContractViolationException}) propagates, and the listener's handler transaction rolls back the whole
 * session. A closed period then goes to retry and the DLQ; a missing mapping is recorded {@code SUSPENDED /
 * GL_MAPPING_NOT_CONFIGURED} and a broken contract {@code FAILED / VALIDATION_ERROR}, both released through the audited
 * reprocess ({@link RegisterSessionCloseReprocessor}, #2594).
 */
@Slf4j
@Component
public class RegisterCashMovementPostingService {

    static final String POSTING_CATEGORY_NAME = "REGISTER_CASH_MOVEMENT";
    static final String PETTY_EXPENSE_KEY_PREFIX = "PETTY_EXPENSE_";
    static final String CASH_CLEARING_KEY = "CASH_CLEARING";
    static final String ACCOUNTS_PAYABLE_KEY = "ACCOUNTS_PAYABLE";
    /** The {@code paymentRef} of a cash-on-delivery payment: the movement names it, so a replay cannot mint two. */
    static final String COD_PAYMENT_REF_PREFIX = "COD-";
    static final String IDEMPOTENCY_KEY_PREFIX = "REGISTER_CASH_MOVEMENT_GL_POSTING:";
    static final String SOURCE_EVENT_NAMESPACE = "REGISTER_CASH_MOVEMENT:";

    static final String PETTY_EXPENSE = "PETTY_EXPENSE";
    static final String VENDOR_COD = "VENDOR_COD";
    static final String BANK_DROP = "BANK_DROP";
    static final String FLOAT_INCREASE = "FLOAT_INCREASE";
    static final String FLOAT_DECREASE = "FLOAT_DECREASE";
    static final String UNCLASSIFIED = "UNCLASSIFIED";

    /** Movements posted, by reason (#2513 "Audit and observability"). */
    static final String POSTED_METRIC = "accounting.cash_movement.posted";

    /**
     * Movements left unposted that cash did leave the drawer for, by reason: {@code VENDOR_COD}, {@code UNCLASSIFIED}.
     * Any increment must alert: 1095 then misses cash that left the drawer.
     */
    static final String UNPOSTED_METRIC = "accounting.cash_movement.unposted";

    static final int DESCRIPTION_MAX = 500;

    /** What closing a session does with one movement. */
    enum Disposition {
        /** Posts at close: Dr {@code PETTY_EXPENSE_<categoryCode>} / Cr {@code CASH_CLEARING}. */
        POST_PETTY_EXPENSE,
        /** Posts at close: Dr {@code ACCOUNTS_PAYABLE} / Cr {@code CASH_CLEARING}, and creates the {@code CASH} AP payment. */
        POST_VENDOR_COD,
        /** The vendor cash on delivery half of #2513 is not built: skipped at ERROR, backfilled by that half. */
        SKIP_VENDOR_COD,
        /** Posts nothing at close by design: a bank drop or a float change. */
        NOTHING_AT_CLOSE,
        /** No reason (recorded before S16) or one this module does not know: not posted. */
        UNCLASSIFIED
    }

    private final AccountingCalendarZoneResolver zoneResolver;
    private final IdempotencyService idempotencyService;
    private final GLMappingResolver glMappingResolver;
    private final GLPostingService glPostingService;
    private final LedgerCurrency ledgerCurrency;
    private final KafkaFactIngestionRecorder ingestionRecorder;
    private final JournalEntryRepository journalEntryRepository;
    private final PettyExpenseRecoveryDecider recoveryDecider;
    private final RegisterCashMovementTaxRecoveryRepository taxRecoveries;
    private final APPaymentRepository apPaymentRepository;
    private final SupplierVendorCopies vendorCopies;
    private final Clock clock;
    private final @Nullable MeterRegistry meterRegistry;

    public RegisterCashMovementPostingService(
            AccountingCalendarZoneResolver zoneResolver,
            IdempotencyService idempotencyService,
            GLMappingResolver glMappingResolver,
            GLPostingService glPostingService,
            LedgerCurrency ledgerCurrency,
            KafkaFactIngestionRecorder ingestionRecorder,
            JournalEntryRepository journalEntryRepository,
            PettyExpenseRecoveryDecider recoveryDecider,
            RegisterCashMovementTaxRecoveryRepository taxRecoveries,
            APPaymentRepository apPaymentRepository,
            SupplierVendorCopies vendorCopies,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.zoneResolver = zoneResolver;
        this.idempotencyService = idempotencyService;
        this.glMappingResolver = glMappingResolver;
        this.glPostingService = glPostingService;
        this.ledgerCurrency = ledgerCurrency;
        this.ingestionRecorder = ingestionRecorder;
        this.journalEntryRepository = journalEntryRepository;
        this.recoveryDecider = recoveryDecider;
        this.taxRecoveries = taxRecoveries;
        this.apPaymentRepository = apPaymentRepository;
        this.vendorCopies = vendorCopies;
        this.clock = clock;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /** What closing a session does with a movement of {@code reason}. */
    static @NonNull Disposition dispositionOf(@Nullable String reason) {
        if (reason == null) {
            return Disposition.UNCLASSIFIED;
        }
        return switch (reason) {
            case PETTY_EXPENSE -> Disposition.POST_PETTY_EXPENSE;
            case VENDOR_COD -> Disposition.POST_VENDOR_COD;
            case BANK_DROP, FLOAT_INCREASE, FLOAT_DECREASE -> Disposition.NOTHING_AT_CLOSE;
            default -> Disposition.UNCLASSIFIED;
        };
    }

    /**
     * What closing the session posts to {@code CASH_CLEARING} (1095) for {@code movement}, signed debit positive
     * (CAP:550 S18, #2514): a petty expense and a vendor cash-on-delivery payout each credit their amount (#2513, both
     * halves), every other reason posts nothing to it at close. The undeposited-sessions read model adds the
     * over/short to the sum of these for a session's clearing net, the part of 1095 its deposit clears.
     */
    static @NonNull BigDecimal clearingEffect(@NonNull Movement movement) {
        Disposition disposition = dispositionOf(movement.reason());
        boolean credits = disposition == Disposition.POST_PETTY_EXPENSE || disposition == Disposition.POST_VENDOR_COD;
        if (!credits || movement.amount() == null) {
            return BigDecimal.ZERO;
        }
        return movement.amount().negate();
    }

    /**
     * Post the closed session's drawer movements, each exactly once per movement id.
     *
     * @param fact the consumed session-closed fact; a schema-1 fact has no movements and posts nothing here
     * @param envelopeEventId the consumed envelope's event id, kept on a currency-held record
     * @return what the movements did, for the listener's ingestion record: {@code Posted} with the first new entry,
     *     {@code AlreadyPosted} when every movement to post was posted before, {@code CurrencyHeld}, or {@code
     *     NothingToPost}
     */
    @Transactional
    public @NonNull FactPostingOutcome postMovements(
            @NonNull RegisterSessionClosedV1 fact, @NonNull String envelopeEventId) {
        List<Movement> movements = fact.movements();
        if (movements == null || movements.isEmpty()) {
            return FactPostingOutcome.nothingToPost();
        }

        List<Movement> toPost = new ArrayList<>();
        for (Movement movement : movements) {
            switch (dispositionOf(movement.reason())) {
                case POST_PETTY_EXPENSE, POST_VENDOR_COD -> toPost.add(movement);
                case SKIP_VENDOR_COD -> skipVendorCashOnDelivery(fact, movement);
                case NOTHING_AT_CLOSE ->
                    log.debug(
                            "Drawer movement posts nothing at close | sessionId={} | movementId={} | reason={}",
                            fact.sessionId(),
                            movement.movementId(),
                            movement.reason());
                case UNCLASSIFIED -> leaveUnclassified(fact, movement);
            }
        }
        if (toPost.isEmpty()) {
            return FactPostingOutcome.nothingToPost();
        }

        // The over/short's order: idempotency, then currency, then the fact's contract. A redelivery of a session
        // already posted never writes a hold, and a foreign session is held even when a movement is malformed.
        List<Movement> pending = new ArrayList<>();
        UUID firstEarlier = null;
        for (Movement movement : toPost) {
            if (movement.movementId() != null && alreadyPosted(movement.movementId())) {
                log.info(
                        "Drawer movement GL posting already processed, skipping | sessionId={} | movementId={}",
                        fact.sessionId(),
                        movement.movementId());
                firstEarlier = firstEarlier == null ? toSourceEventId(movement.movementId()) : firstEarlier;
            } else {
                pending.add(movement);
            }
        }
        if (pending.isEmpty()) {
            return new FactPostingOutcome.AlreadyPosted(null, firstEarlier);
        }

        // Business time, not processing time: redeliveries land in the same period.
        LocalDateTime transactionDate = zoneResolver.postingDateTime(fact.closedAt());

        // Never at par (ADR-0067 PC-9): the whole session is held once, and nothing posts.
        if (inForeignCurrency(fact, pending)) {
            holdForeignCurrency(fact, pending, envelopeEventId, transactionDate);
            return new FactPostingOutcome.CurrencyHeld();
        }

        pending.forEach(movement -> requirePostable(fact, movement));
        requireDistinctIds(fact, pending);
        UUID firstPosted = null;
        for (Movement movement : pending) {
            UUID posted = dispositionOf(movement.reason()) == Disposition.POST_VENDOR_COD
                    ? postVendorCashOnDelivery(fact, movement, transactionDate)
                    : postPettyExpense(fact, movement, transactionDate);
            idempotencyService.registerKey(IDEMPOTENCY_KEY_PREFIX + movement.movementId(), posted);
            countAfterCommit(POSTED_METRIC, movement.reason());
            firstPosted = firstPosted == null ? posted : firstPosted;
        }
        return FactPostingOutcome.posted(firstPosted);
    }

    /**
     * Whether the movement's entry was posted before: its posting key, or, once that key has expired ({@code
     * IdempotencyService}, 24 hours), the entry's deterministic source event, the durable backstop {@link
     * InventoryRevaluationPostingService} uses too.
     */
    private boolean alreadyPosted(UUID movementId) {
        return idempotencyService.isKeyProcessed(IDEMPOTENCY_KEY_PREFIX + movementId)
                || !journalEntryRepository
                        .findBySourceEvent(toSourceEventId(movementId))
                        .isEmpty();
    }

    private UUID postPettyExpense(RegisterSessionClosedV1 fact, Movement movement, LocalDateTime transactionDate) {
        // S32d item 9: the recovered part of each stated regime's tax leaves the expense for INPUT_TAX_<regime>.
        List<PettyExpenseRecoveryDecider.RegimeRecovery> recoveries = recoveryDecider.decide(movement);
        BigDecimal recovered = recoveries.stream()
                .map(PettyExpenseRecoveryDecider.RegimeRecovery::recovered)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Dr the category's expense (gross, tax included, less any recovered tax) / Cr Register Cash Clearing.
        UUID expenseAccountId = resolve(PETTY_EXPENSE_KEY_PREFIX + movement.categoryCode(), transactionDate);
        UUID clearingAccountId = resolve(CASH_CLEARING_KEY, transactionDate);

        StringBuilder description = new StringBuilder("Drawer petty expense ")
                .append(movement.categoryCode())
                .append(' ')
                .append(movement.amount().toPlainString())
                .append(' ')
                .append(movement.currencyCode());
        if (movement.receiptReference() != null && !movement.receiptReference().isBlank()) {
            description.append(", receipt ").append(movement.receiptReference());
        }
        description
                .append(", register ")
                .append(fact.terminalId())
                .append(", session closed ")
                .append(fact.closedAt());

        String lineLabel =
                abbreviate("Drawer petty expense " + movement.categoryCode() + ", register " + fact.terminalId());
        UUID posted;
        if (recovered.signum() == 0) {
            posted = glPostingService.postRegisterCashMovement(
                    toSourceEventId(movement.movementId()),
                    expenseAccountId,
                    clearingAccountId,
                    movement.amount(),
                    transactionDate,
                    abbreviate(description.toString()),
                    lineLabel,
                    dimensions(fact));
        } else {
            List<GLPostingService.DebitLine> debits = new ArrayList<>();
            debits.add(new GLPostingService.DebitLine(
                    expenseAccountId, movement.amount().subtract(recovered), lineLabel));
            for (PettyExpenseRecoveryDecider.RegimeRecovery recovery : recoveries) {
                if (recovery.recovered().signum() > 0) {
                    UUID inputTaxAccountId =
                            resolve(InputTaxRecoveryService.inputTaxKey(recovery.regime()), transactionDate);
                    debits.add(new GLPostingService.DebitLine(
                            inputTaxAccountId,
                            recovery.recovered(),
                            abbreviate("Input tax " + recovery.regime() + " recovered, " + lineLabel)));
                }
            }
            posted = glPostingService.postRegisterCashMovementLines(
                    toSourceEventId(movement.movementId()),
                    debits,
                    clearingAccountId,
                    transactionDate,
                    abbreviate(description.toString()),
                    dimensions(fact));
        }
        recordRecoveries(fact, movement, recoveries, posted);

        log.info(
                "Drawer movement GL posting completed | sessionId={} | terminalId={} | movementId={} | reason={} "
                        + "| category={} | amount={} | journalEntryId={}",
                fact.sessionId(),
                fact.terminalId(),
                movement.movementId(),
                movement.reason(),
                movement.categoryCode(),
                movement.amount(),
                posted);
        return posted;
    }

    /**
     * The {@code REGISTER_CASH_MOVEMENT} account for {@code key}; a missing mapping is refused naming the category and
     * the key (#2601), which the listener records on the held session (#2594).
     */
    private UUID resolve(String key, LocalDateTime transactionDate) {
        try {
            return glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, key, transactionDate);
        } catch (GLMappingNotConfiguredException missing) {
            throw missing.naming(
                    POSTING_CATEGORY_NAME,
                    key,
                    "Map " + POSTING_CATEGORY_NAME + "/" + key + " to an active account effective on the session's"
                            + " close date, then reprocess the held session");
        }
    }

    /**
     * Keeps what each stated regime recovered, or why it did not, with the supplier's number as the claim's evidence
     * (S32d item 9). Never logged: the number is INTERNAL (ADR-0072 Decision 1).
     */
    private void recordRecoveries(
            RegisterSessionClosedV1 fact,
            Movement movement,
            List<PettyExpenseRecoveryDecider.RegimeRecovery> recoveries,
            UUID journalEntryId) {
        for (PettyExpenseRecoveryDecider.RegimeRecovery recovery : recoveries) {
            RegisterCashMovementTaxRecovery row = new RegisterCashMovementTaxRecovery();
            row.setMovementId(movement.movementId());
            row.setSessionId(fact.sessionId());
            row.setJournalEntryId(journalEntryId);
            row.setRegime(recovery.regime());
            row.setStatedAmount(recovery.stated());
            row.setRecoverablePercent(recovery.percent());
            row.setRecoveredAmount(recovery.recovered());
            row.setRecoveryWithheldReason(recovery.withheldReason());
            row.setSupplierRegistrationNumber(movement.supplierRegistrationNumber());
            row.setCurrencyCode(movement.currencyCode());
            row.setCreatedAt(Instant.now(clock));
            taxRecoveries.save(row);
            log.info(
                    "Drawer petty expense tax recovery | sessionId={} | movementId={} | regime={} | stated={}"
                            + " | recovered={} | withheldReason={}",
                    fact.sessionId(),
                    movement.movementId(),
                    recovery.regime(),
                    recovery.stated(),
                    recovery.recovered(),
                    recovery.withheldReason());
        }
    }

    /**
     * A petty expense the close fact's contract allows to post; anything else fails the fact as a contract violation
     * ({@link RegisterSessionContractViolationException}, recorded {@code FAILED / VALIDATION_ERROR}, #2594).
     */
    private static Movement requirePostable(RegisterSessionClosedV1 fact, Movement movement) {
        boolean cod = dispositionOf(movement.reason()) == Disposition.POST_VENDOR_COD;
        String problem = null;
        if (movement.movementId() == null) {
            // Its posting key and source event would be shared with every other movement without one.
            problem = "no movementId";
        } else if (!Movement.OUT.equals(movement.direction())) {
            problem = "direction " + movement.direction() + " (" + (cod ? "a vendor payout" : "a petty expense")
                    + " is OUT)";
        } else if (!cod && (movement.categoryCode() == null || movement.categoryCode().isBlank())) {
            problem = "no categoryCode";
        } else if (cod && movement.vendorId() == null) {
            problem = "no vendorId";
        } else if (movement.amount() == null || movement.amount().signum() <= 0) {
            problem = "amount " + movement.amount() + " (must be positive)";
        }
        if (problem != null) {
            throw new RegisterSessionContractViolationException((cod ? "Vendor cash-on-delivery movement "
                            : "Petty-expense movement ")
                    + movement.movementId() + " of session " + fact.sessionId() + " cannot post: " + problem);
        }
        return movement;
    }

    /**
     * The vendor cash-on-delivery half of #2513 (Accounting ruling AW41): Dr {@code ACCOUNTS_PAYABLE} (2000) / Cr
     * {@code CASH_CLEARING} (1095) for the movement's amount, and in the same transaction an AP payment with method
     * {@code CASH}, gross = unapplied = the amount, fee 0, the ledger currency, dated the session's close, status
     * {@code GL_POSTED} and linked to that entry. The close entry is the payment's only posting: {@code
     * APPaymentPostingService} skips {@code CASH}. The payment is created even when the vendor copy does not hold the
     * vendor yet (fact ordering): pos-order validated the vendor (S16), and the name is filled from the copy when
     * present. One movement makes one payment ({@code uq_ap_payment_cash_movement}).
     */
    private UUID postVendorCashOnDelivery(
            RegisterSessionClosedV1 fact, Movement movement, LocalDateTime transactionDate) {
        UUID payableAccountId = resolve(ACCOUNTS_PAYABLE_KEY, transactionDate);
        UUID clearingAccountId = resolve(CASH_CLEARING_KEY, transactionDate);
        String vendorName = vendorCopies
                .find(movement.vendorId())
                .map(ExtSupplierVendor::getDisplayName)
                .orElse(null);
        String description = "Drawer cash on delivery to vendor " + (vendorName != null ? vendorName : movement.vendorId())
                + " " + movement.amount().toPlainString() + " " + movement.currencyCode() + ", register "
                + fact.terminalId() + ", session closed " + fact.closedAt();
        String lineLabel = abbreviate("Drawer cash on delivery, register " + fact.terminalId());
        UUID posted = glPostingService.postRegisterCashMovement(
                toSourceEventId(movement.movementId()),
                payableAccountId,
                clearingAccountId,
                movement.amount(),
                transactionDate,
                abbreviate(description),
                lineLabel,
                dimensions(fact));

        APPayment payment = new APPayment();
        payment.setPaymentRef(COD_PAYMENT_REF_PREFIX + movement.movementId());
        payment.setVendorId(movement.vendorId());
        payment.setVendorName(vendorName);
        payment.setGrossAmount(movement.amount());
        payment.setFeeAmount(BigDecimal.ZERO);
        payment.setUnappliedAmount(movement.amount());
        payment.setCurrency(ledgerCurrency.code());
        payment.setPaymentMethod(PaymentMethod.CASH);
        payment.setPaymentDate(transactionDate.toLocalDate());
        payment.setStatus(APPaymentStatus.GL_POSTED);
        payment.setGlJournalEntry(journalEntryRepository.getReferenceById(posted));
        payment.setGlPostedAt(Instant.now(clock));
        payment.setRegisterSessionId(fact.sessionId());
        payment.setCashMovementId(movement.movementId());
        payment.setMemo(abbreviate("Cash on delivery paid from register " + fact.terminalId() + " by "
                + movement.clerkId()));
        payment.setCreatedBy(movement.clerkId());
        payment.setCreatedAt(Instant.now(clock));
        apPaymentRepository.save(payment);
        log.info(
                "Vendor cash on delivery posted | sessionId={} | movementId={} | vendorId={} | journalEntryId={}",
                fact.sessionId(),
                movement.movementId(),
                movement.vendorId(),
                posted);
        return posted;
    }

    /**
     * One fact names each movement once. A repeated {@code movementId} would post twice and then collide on its
     * posting key, so it fails the fact as a contract violation with that reason instead.
     */
    private static void requireDistinctIds(RegisterSessionClosedV1 fact, List<Movement> pending) {
        Set<UUID> seen = new HashSet<>();
        for (Movement movement : pending) {
            if (!seen.add(movement.movementId())) {
                throw new RegisterSessionContractViolationException("Petty-expense movement " + movement.movementId()
                        + " of session " + fact.sessionId() + " cannot post: the fact names it more than once");
            }
        }
    }

    private boolean inForeignCurrency(RegisterSessionClosedV1 fact, List<Movement> toPost) {
        return ledgerCurrency.isForeign(fact.currencyCode())
                || toPost.stream().anyMatch(movement -> ledgerCurrency.isForeign(movement.currencyCode()));
    }

    private void holdForeignCurrency(
            RegisterSessionClosedV1 fact,
            List<Movement> toPost,
            String envelopeEventId,
            LocalDateTime transactionDate) {
        // Held before the contract check, so a malformed movement may lack an amount or a currency.
        BigDecimal total =
                toPost.stream().map(Movement::amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        String currencies = Stream.concat(
                        Stream.of(fact.currencyCode()), toPost.stream().map(Movement::currencyCode))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("/"));
        boolean variance = fact.overShort() != null && fact.overShort().signum() != 0;
        String detail = "Register session closed in " + currencies + " not posted: " + toPost.size()
                + " drawer movement(s) of " + total.toPlainString()
                + (variance ? " and an over/short of " + fact.overShort().toPlainString() : "")
                + "; the ledger books " + ledgerCurrency.code()
                + " only and another currency is never booked at par (ADR-0067 PC-9)";
        boolean recorded = ingestionRecorder.recordCurrencyHeld(
                RegisterOverShortPostingService.SOURCE_SYSTEM,
                RegisterSessionClosedV1.EVENT_TYPE,
                envelopeEventId,
                fact.sessionId(),
                transactionDate,
                fact,
                detail);
        log.warn(
                "Register session held for its currency, drawer movements not posted | sessionId={} | currency={} "
                        + "| ledgerCurrency={} | movements={} | newRecord={}",
                fact.sessionId(),
                currencies,
                ledgerCurrency.code(),
                toPost.size(),
                recorded);
    }

    /**
     * The COD half of #2513 is not built: the movement is skipped, at ERROR, and counted on {@value #UNPOSTED_METRIC}
     * {@code {reason=VENDOR_COD}}, which must alert. Cash left the drawer and 1095 does not show it until the COD half
     * backfills the movement from the stored close fact (see the class doc).
     */
    private void skipVendorCashOnDelivery(RegisterSessionClosedV1 fact, Movement movement) {
        log.error(
                "Vendor cash on delivery NOT POSTED: its AP payment posting is not built yet (#2513 COD half; pos-order"
                        + " keeps the reason off until then, #2576). The COD half backfills it from this fact's"
                        + " accounting_event payload | sessionId={} | terminalId={} | movementId={} | vendorId={}"
                        + " | amount={} {}",
                fact.sessionId(),
                fact.terminalId(),
                movement.movementId(),
                movement.vendorId(),
                movement.amount(),
                movement.currencyCode());
        countAfterCommit(UNPOSTED_METRIC, VENDOR_COD);
    }

    private void leaveUnclassified(RegisterSessionClosedV1 fact, Movement movement) {
        log.warn(
                "Drawer movement without a posting reason not posted | sessionId={} | terminalId={} | movementId={}"
                        + " | reason={} | direction={} | amount={} {}",
                fact.sessionId(),
                fact.terminalId(),
                movement.movementId(),
                movement.reason(),
                movement.direction(),
                movement.amount(),
                movement.currencyCode());
        countAfterCommit(UNPOSTED_METRIC, UNCLASSIFIED);
    }

    /** The lines' dimensions: the register (terminal), the session and the session's location when it has one. */
    static @NonNull Map<String, String> dimensions(@NonNull RegisterSessionClosedV1 fact) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("registerId", fact.terminalId());
        dimensions.put("sessionId", fact.sessionId().toString());
        if (fact.locationId() != null) {
            dimensions.put("locationId", fact.locationId().toString());
        }
        return dimensions;
    }

    /** A counter, incremented once the posting commits, so a rolled-back session is never counted. */
    private void countAfterCommit(String metric, String reason) {
        if (meterRegistry == null) {
            return;
        }
        Counter counter = Counter.builder(metric)
                .description("Drawer cash movements of closed register sessions, by reason (#2513)")
                .tag("reason", reason)
                .register(meterRegistry);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    counter.increment();
                }
            });
        } else {
            counter.increment();
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= DESCRIPTION_MAX ? text : text.substring(0, DESCRIPTION_MAX - 1) + "…";
    }

    /**
     * Derive the journal entry {@code sourceEventId} deterministically from the movement id, namespaced so it never
     * collides with another entry deriving from the same id.
     */
    static @NonNull UUID toSourceEventId(@NonNull UUID movementId) {
        return UUID.nameUUIDFromBytes((SOURCE_EVENT_NAMESPACE + movementId).getBytes(StandardCharsets.UTF_8));
    }
}

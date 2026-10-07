package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.order.RegisterSessionClosedV1.Movement;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *       (US)"). A category deactivated after the movement was recorded still resolves: its mapping key stays.
 *   <li>{@code VENDOR_COD}: <b>not posted yet</b>. Its posting (Dr {@code ACCOUNTS_PAYABLE} / Cr {@code CASH_CLEARING}
 *       plus an AP payment with method {@code CASH}) is the vendor cash on delivery half of #2513, which waits on the
 *       vendor copy (S24, #2517) and the pay guard (S13, #2510); pos-order refuses the reason until then (#2576). A
 *       COD movement that arrives anyway is held: logged, counted on {@value #UNPOSTED_METRIC}{@code
 *       {reason=VENDOR_COD}}, and its idempotency key is left unregistered, so a replay of the close fact once the
 *       COD posting exists posts it and nothing else.
 *   <li>{@code BANK_DROP}, {@code FLOAT_INCREASE}, {@code FLOAT_DECREASE}: nothing at close (the deposit, S18; Change
 *       float, S15).
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
 * same transaction as the entry, so a fact redelivered under a fresh envelope id posts nothing twice.
 *
 * <p><b>Currency (ADR-0067 PC-9).</b> A session whose fact, or any movement to post, is in a currency other than the
 * ledger's posts nothing: it is held once per session as the {@code SUSPENDED / CURRENCY_NOT_SUPPORTED} record the
 * over/short hold writes, and the listener then skips the over/short, so the session posts all or nothing.
 *
 * <p>A closed period, a missing mapping or a petty expense that breaks the close fact's contract (not {@code OUT}, no
 * category, no positive amount) propagates: the listener's handler transaction rolls back the whole session and the
 * fact goes to retry and the DLQ.
 */
@Slf4j
@Component
public class RegisterCashMovementPostingService {

    static final String POSTING_CATEGORY_NAME = "REGISTER_CASH_MOVEMENT";
    static final String PETTY_EXPENSE_KEY_PREFIX = "PETTY_EXPENSE_";
    static final String CASH_CLEARING_KEY = "CASH_CLEARING";
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

    /** Movements left unposted that cash did leave the drawer for, by reason: {@code VENDOR_COD}, {@code UNCLASSIFIED}. */
    static final String UNPOSTED_METRIC = "accounting.cash_movement.unposted";

    static final int DESCRIPTION_MAX = 500;

    /** What closing a session does with one movement. */
    enum Disposition {
        /** Posts at close: Dr {@code PETTY_EXPENSE_<categoryCode>} / Cr {@code CASH_CLEARING}. */
        POST_PETTY_EXPENSE,
        /** The vendor cash on delivery half of #2513 is not built: held, not posted, key left unregistered. */
        HOLD_VENDOR_COD,
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
    private final @Nullable MeterRegistry meterRegistry;

    public RegisterCashMovementPostingService(
            AccountingCalendarZoneResolver zoneResolver,
            IdempotencyService idempotencyService,
            GLMappingResolver glMappingResolver,
            GLPostingService glPostingService,
            LedgerCurrency ledgerCurrency,
            KafkaFactIngestionRecorder ingestionRecorder,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.zoneResolver = zoneResolver;
        this.idempotencyService = idempotencyService;
        this.glMappingResolver = glMappingResolver;
        this.glPostingService = glPostingService;
        this.ledgerCurrency = ledgerCurrency;
        this.ingestionRecorder = ingestionRecorder;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /** What closing a session does with a movement of {@code reason}. */
    static @NonNull Disposition dispositionOf(@Nullable String reason) {
        if (reason == null) {
            return Disposition.UNCLASSIFIED;
        }
        return switch (reason) {
            case PETTY_EXPENSE -> Disposition.POST_PETTY_EXPENSE;
            case VENDOR_COD -> Disposition.HOLD_VENDOR_COD;
            case BANK_DROP, FLOAT_INCREASE, FLOAT_DECREASE -> Disposition.NOTHING_AT_CLOSE;
            default -> Disposition.UNCLASSIFIED;
        };
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
                case POST_PETTY_EXPENSE -> toPost.add(requirePostable(fact, movement));
                case HOLD_VENDOR_COD -> holdVendorCashOnDelivery(fact, movement);
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

        // Business time, not processing time: redeliveries land in the same period.
        LocalDateTime transactionDate = zoneResolver.postingDateTime(fact.closedAt());

        // Never at par (ADR-0067 PC-9): the whole session is held once, and nothing posts.
        if (inForeignCurrency(fact, toPost)) {
            holdForeignCurrency(fact, toPost, envelopeEventId, transactionDate);
            return new FactPostingOutcome.CurrencyHeld();
        }

        UUID firstPosted = null;
        UUID firstEarlier = null;
        for (Movement movement : toPost) {
            String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + movement.movementId();
            if (idempotencyService.isKeyProcessed(idempotencyKey)) {
                log.info(
                        "Drawer movement GL posting already processed, skipping | sessionId={} | movementId={}",
                        fact.sessionId(),
                        movement.movementId());
                firstEarlier = firstEarlier == null ? toSourceEventId(movement.movementId()) : firstEarlier;
                continue;
            }
            UUID posted = postPettyExpense(fact, movement, transactionDate);
            idempotencyService.registerKey(idempotencyKey, posted);
            countAfterCommit(POSTED_METRIC, movement.reason());
            firstPosted = firstPosted == null ? posted : firstPosted;
        }
        if (firstPosted != null) {
            return FactPostingOutcome.posted(firstPosted);
        }
        return new FactPostingOutcome.AlreadyPosted(null, firstEarlier);
    }

    private UUID postPettyExpense(RegisterSessionClosedV1 fact, Movement movement, LocalDateTime transactionDate) {
        // Dr the category's expense (gross, tax included) / Cr Register Cash Clearing.
        UUID expenseAccountId = glMappingResolver.resolveGLAccount(
                POSTING_CATEGORY_NAME, PETTY_EXPENSE_KEY_PREFIX + movement.categoryCode(), transactionDate);
        UUID clearingAccountId =
                glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, CASH_CLEARING_KEY, transactionDate);

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

        UUID posted = glPostingService.postRegisterCashMovement(
                toSourceEventId(movement.movementId()),
                expenseAccountId,
                clearingAccountId,
                movement.amount(),
                transactionDate,
                abbreviate(description.toString()),
                abbreviate("Drawer petty expense " + movement.categoryCode() + ", register " + fact.terminalId()),
                dimensions(fact));

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

    /** A petty expense the close fact's contract allows to post; anything else fails the fact for retry / DLQ. */
    private static Movement requirePostable(RegisterSessionClosedV1 fact, Movement movement) {
        String problem = null;
        if (!Movement.OUT.equals(movement.direction())) {
            problem = "direction " + movement.direction() + " (a petty expense is OUT)";
        } else if (movement.categoryCode() == null || movement.categoryCode().isBlank()) {
            problem = "no categoryCode";
        } else if (movement.amount() == null || movement.amount().signum() <= 0) {
            problem = "amount " + movement.amount() + " (must be positive)";
        }
        if (problem != null) {
            throw new IllegalArgumentException("Petty-expense movement " + movement.movementId() + " of session "
                    + fact.sessionId() + " cannot post: " + problem);
        }
        return movement;
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
        BigDecimal total = toPost.stream().map(Movement::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String currencies = Stream.concat(
                        Stream.of(fact.currencyCode()), toPost.stream().map(Movement::currencyCode))
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

    private void holdVendorCashOnDelivery(RegisterSessionClosedV1 fact, Movement movement) {
        log.warn(
                "Vendor cash on delivery not posted: its AP payment posting is not built yet (#2513 COD half, #2576);"
                        + " a replay of the close fact posts it once it is | sessionId={} | terminalId={} | movementId={}"
                        + " | vendorId={} | amount={} {}",
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

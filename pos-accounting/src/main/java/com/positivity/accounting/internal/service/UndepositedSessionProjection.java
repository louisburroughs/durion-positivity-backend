package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.UndepositedSession;
import com.positivity.accounting.internal.entity.UndepositedSessionDrop;
import com.positivity.accounting.internal.enums.UndepositedSessionStatus;
import com.positivity.accounting.internal.repository.UndepositedSessionDropRepository;
import com.positivity.accounting.internal.repository.UndepositedSessionRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.order.RegisterSessionClosedV1.Movement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the undeposited-sessions read model from the close fact (CAP:550 S18, #2514; SPEC-accounting-workspace §4.5,
 * §7.1 "Undeposited sessions"; AW10, AW15). No call to pos-order: the close fact carries everything (ADR-0044).
 *
 * <p>{@link OrderEventsListener} calls it in the handler transaction that posts the session's drawer movements and
 * over/short (S17), after they posted, so the row exists exactly when the 1095 lines it describes do. One row per
 * session, written once: a redelivered fact writes nothing.
 *
 * <p><b>What the row holds.</b> The session's bank drops (movement, bag, amount) and their total, the deposit amount;
 * its expected cash, the {@code CASH} tender total its sales put in 1090; and its <em>clearing net</em>, the signed sum
 * (debit positive) of the 1095 lines its over/short and drawer movement entries posted: the over/short as it stands,
 * plus each movement's {@link RegisterCashMovementPostingService#clearingEffect} (a petty expense's credit). A deposit
 * balances when the drops equal the expected cash plus the clearing net.
 *
 * <p><b>Nothing to deposit.</b> A session with no drops, no expected cash and a zero clearing net (card tenders only,
 * no over/short) is written {@code NOTHING_TO_DEPOSIT}, terminal: it is never listed, counted in the undeposited gauges
 * or taken by a deposit. A card-only session with an over/short or a petty expense keeps a clearing net and waits
 * {@code UNDEPOSITED}.
 *
 * <p><b>No row</b> for a schema-1 fact (no movements, so no drops), for a session closed in a currency other than the
 * ledger's, or with a bank drop in one (ADR-0067 PC-9: never deposited at par; the listener skips a session S17 held
 * for its currency before it gets here). A schema-2 fact without its {@code movements} list, or with a bank drop that
 * breaks the close fact's contract (no movement id, a movement id named twice, not {@code OUT}, no positive amount),
 * propagates, so the whole session rolls back for retry / DLQ, as a malformed petty expense does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UndepositedSessionProjection {

    static final String CASH_TENDER = "CASH";

    private final UndepositedSessionRepository sessions;
    private final UndepositedSessionDropRepository drops;
    private final LedgerCurrency ledgerCurrency;

    /**
     * Write the session's row, once.
     *
     * @param fact the consumed session-closed fact
     * @param schemaVersion the envelope's schema version; below 2 writes nothing
     * @return whether a row was written
     * @throws IllegalArgumentException for a schema-2 fact without its movements list or with a malformed bank drop
     */
    @Transactional
    public boolean record(@NonNull RegisterSessionClosedV1 fact, int schemaVersion) {
        if (schemaVersion < 2) {
            log.debug(
                    "Schema-{} close fact writes no undeposited session | sessionId={}",
                    schemaVersion,
                    fact.sessionId());
            return false;
        }
        if (fact.movements() == null) {
            // Version 2 always carries the list (empty when nothing moved); without it the drops are unknown.
            throw new IllegalArgumentException("Close fact of session " + fact.sessionId() + " is schema version "
                    + schemaVersion + " but carries no movements list; its bank drops cannot be read");
        }
        if (sessions.existsBySessionId(fact.sessionId())) {
            log.debug("Undeposited session already written, skipping | sessionId={}", fact.sessionId());
            return false;
        }
        List<Movement> bankDrops = fact.movements().stream()
                .filter(movement -> RegisterCashMovementPostingService.BANK_DROP.equals(movement.reason()))
                .toList();
        if (ledgerCurrency.isForeign(fact.currencyCode())
                || bankDrops.stream().anyMatch(drop -> ledgerCurrency.isForeign(drop.currencyCode()))) {
            log.warn(
                    "Register session in another currency is not deposited at par: no undeposited session written"
                            + " | sessionId={} | currency={} | ledgerCurrency={}",
                    fact.sessionId(),
                    fact.currencyCode(),
                    ledgerCurrency.code());
            return false;
        }
        bankDrops.forEach(drop -> requireDepositable(fact, drop));
        requireDistinctIds(fact, bankDrops);

        BigDecimal expectedCash = fact.tenderTotals().stream()
                .filter(total -> CASH_TENDER.equals(total.methodType()))
                .map(RegisterSessionClosedV1.TenderTotal::amount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal clearingNet = fact.movements().stream()
                .map(RegisterCashMovementPostingService::clearingEffect)
                .reduce(fact.overShort() == null ? BigDecimal.ZERO : fact.overShort(), BigDecimal::add);
        BigDecimal depositAmount = bankDrops.stream().map(Movement::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        UndepositedSession session = new UndepositedSession();
        session.setSessionId(fact.sessionId());
        session.setTerminalId(fact.terminalId());
        session.setLocationId(fact.locationId());
        session.setOpenedAt(fact.openedAt());
        session.setClosedAt(fact.closedAt());
        session.setOpeningFloat(fact.openingFloat());
        session.setCountedCash(fact.countedCash());
        session.setTheoreticalCash(fact.theoreticalCash());
        session.setOverShort(fact.overShort());
        session.setExpectedCash(expectedCash);
        session.setClearingNet(clearingNet);
        session.setDepositAmount(depositAmount);
        session.setCurrencyCode(fact.currencyCode());
        boolean nothingToDeposit =
                depositAmount.signum() == 0 && expectedCash.signum() == 0 && clearingNet.signum() == 0;
        session.setStatus(
                nothingToDeposit ? UndepositedSessionStatus.NOTHING_TO_DEPOSIT : UndepositedSessionStatus.UNDEPOSITED);
        UndepositedSession saved = sessions.saveAndFlush(session);

        List<UndepositedSessionDrop> rows = new ArrayList<>();
        for (Movement drop : bankDrops) {
            UndepositedSessionDrop row = new UndepositedSessionDrop();
            row.setUndepositedSessionId(saved.getUndepositedSessionId());
            row.setMovementId(drop.movementId());
            row.setBagNumber(blankToNull(drop.bagNumber()));
            row.setAmount(drop.amount());
            row.setOccurredAt(drop.occurredAt());
            rows.add(row);
        }
        drops.saveAll(rows);

        log.info(
                "Undeposited session written | sessionId={} | status={} | terminalId={} | drops={} | depositAmount={}"
                        + " | expectedCash={} | clearingNet={} | currency={}",
                fact.sessionId(),
                saved.getStatus(),
                fact.terminalId(),
                rows.size(),
                depositAmount,
                expectedCash,
                clearingNet,
                fact.currencyCode());
        return true;
    }

    /** A bank drop the close fact's contract allows; anything else fails the fact for retry / DLQ. */
    private static void requireDepositable(RegisterSessionClosedV1 fact, Movement drop) {
        String problem = null;
        if (drop.movementId() == null) {
            problem = "no movementId";
        } else if (!Movement.OUT.equals(drop.direction())) {
            problem = "direction " + drop.direction() + " (a bank drop is OUT)";
        } else if (drop.amount() == null || drop.amount().signum() <= 0) {
            problem = "amount " + drop.amount() + " (must be positive)";
        }
        if (problem != null) {
            throw new IllegalArgumentException("Bank drop " + drop.movementId() + " of session " + fact.sessionId()
                    + " cannot be deposited: " + problem);
        }
    }

    private static void requireDistinctIds(RegisterSessionClosedV1 fact, List<Movement> bankDrops) {
        Set<Object> seen = new HashSet<>();
        for (Movement drop : bankDrops) {
            if (!seen.add(drop.movementId())) {
                throw new IllegalArgumentException("Bank drop " + drop.movementId() + " of session " + fact.sessionId()
                        + " cannot be deposited: the fact names it more than once");
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.exception.ReconciliationNotBalancedException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The approval gate E4 (SPEC-manual-bank-reconciliation §3.7, D2; story S5, #2304), identical at submit and
 * approve and checked in its one order: {@code |difference| > 0.01} answers 422 {@code
 * RECONCILIATION_NOT_BALANCED} with {@code fieldErrors[difference]}; otherwise any unexplained bank transaction
 * or ledger line from the window's baseline to its end answers 422 {@code RECONCILIATION_HAS_UNEXPLAINED_ITEMS}
 * with both counts and the first {@value #IDS_PER_SIDE} ids per side. The counts are exact; {@code
 * openingDifference} is never a condition (§8.1).
 */
@Component
@RequiredArgsConstructor
public class ApprovalGate {

    /** How many ids per side the refusal lists (§3.7). */
    public static final int IDS_PER_SIDE = 50;

    static final String COUNT_BANK = "countUnexplainedBank";
    static final String COUNT_LEDGER = "countUnexplainedLedger";
    static final String BANK_IDS = "unexplainedBankTransactionIds";
    static final String LEDGER_IDS = "unexplainedGlLineIds";

    private final FunctionalCurrency currency;

    /** Throws the first E4 refusal of the live {@code snapshot}; returns when the gate holds. */
    public void require(@NonNull ReconciliationSnapshot snapshot) {
        BigDecimal difference = snapshot.terms().difference();
        if (!ReconciliationEquation.withinTolerance(difference, currency.tolerance())) {
            throw new ReconciliationNotBalancedException(
                    "The reconciliation does not balance; difference " + difference.toPlainString(), difference);
        }
        int bank = snapshot.countUnexplainedBank();
        int ledger = snapshot.countUnexplainedLedger();
        if (bank == 0 && ledger == 0) {
            return;
        }
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        fieldErrors.put(COUNT_BANK, Integer.toString(bank));
        fieldErrors.put(COUNT_LEDGER, Integer.toString(ledger));
        List<UUID> bankIds = snapshot.unexplainedBank().stream()
                .map(BankTransaction::getBankTransactionId)
                .limit(IDS_PER_SIDE)
                .toList();
        for (int i = 0; i < bankIds.size(); i++) {
            fieldErrors.put(BANK_IDS + "[" + i + "]", bankIds.get(i).toString());
        }
        List<UUID> ledgerIds = ledgerIds(snapshot);
        for (int i = 0; i < ledgerIds.size(); i++) {
            fieldErrors.put(LEDGER_IDS + "[" + i + "]", ledgerIds.get(i).toString());
        }
        throw new BankRecException(
                BankRecErrorCode.RECONCILIATION_HAS_UNEXPLAINED_ITEMS,
                bank + " unexplained bank transaction(s) and " + ledger + " unexplained ledger line(s) from the"
                        + " baseline to the window end; explain them before submitting or approving",
                fieldErrors);
    }

    /** The unexplained ledger lines, then the lines of aged timing items awaiting reaffirmation (§3.6). */
    private static List<UUID> ledgerIds(ReconciliationSnapshot snapshot) {
        List<UUID> ids = new ArrayList<>();
        Stream.concat(
                        snapshot.unexplainedLedger().stream().map(LedgerLine::lineId),
                        snapshot.agedItemsAwaitingReaffirmation().stream()
                                .map(BankReconciliationOutstandingItem::getGlLineId)
                                .filter(Objects::nonNull))
                .limit(IDS_PER_SIDE)
                .forEach(ids::add);
        return ids;
    }
}

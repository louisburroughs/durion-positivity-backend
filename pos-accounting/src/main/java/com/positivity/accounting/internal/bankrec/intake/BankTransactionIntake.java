package com.positivity.accounting.internal.bankrec.intake;

import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The reconciliation core's intake port (SPEC-manual-bank-reconciliation §2.1, §2.2; decision D1;
 * story S2, #2301). Every bank line enters accounting here, whatever its source — the phase-1 file
 * adapter, the manual-statement endpoint, a phase-2 feed listener — so the core never learns a file
 * format or a provider. Together with {@code ..bankrec.dto..} it is the only part of the core an
 * adapter may reach.
 */
public interface BankTransactionIntake {

    /**
     * Accepts one batch in one transaction: checks the account (D5) and the currency (D18); when the
     * batch carries a statement header, checks it (dates, U1, U2, E2 with the gap acknowledgement in
     * the order of §4.2, the manual window of §4.3 and E1) and commits it; normalizes, fingerprints
     * and stores each transaction — an upsert by source id (U3), a {@code POSSIBLE_DUPLICATE} on a
     * fingerprint collision (R1), never a silent drop; creates the account's profile when absent and
     * moves its reconciliation baseline when the statement is acknowledged (§3.1, one {@code
     * BANK_ACCOUNT_BASELINE_SET} audit row per change); and queues the {@code
     * accounting.bankstatement.committed} fact.
     *
     * @throws BankRecException with the §4.10 code of the first refusal; nothing is persisted
     */
    @NonNull
    IntakeResult accept(@NonNull BankTransactionsObservedV1 batch, @NonNull IntakeContext ctx);

    /**
     * Recomputes the account's reconciliation baseline from its remaining COMMITTED statements —
     * the {@code startDate} of the latest-starting one that carries a gap acknowledgement — after a
     * statement was superseded (§4.9 path 3; called by story S5). The value is written, and audited
     * as {@code BANK_ACCOUNT_BASELINE_SET}, only when it changes.
     *
     * @param glAccountId the account
     * @param actor who superseded the statement
     * @param justification the supersession's justification, stored on the audit row
     * @return the baseline after the recompute; empty when the account has none
     */
    @NonNull
    Optional<LocalDate> recomputeBaseline(
            @NonNull UUID glAccountId, @NonNull String actor, @Nullable String justification);
}

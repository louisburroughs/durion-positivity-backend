package com.positivity.domainevents.accounting;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code accounting.bankreconciliation.invalidated} v1 on {@code accounting.events.v1}
 * (SPEC-manual-bank-reconciliation §3.10, §5.5; story S5, #2304).
 *
 * <p>Published by pos-accounting through its transactional outbox in the transaction that invalidates
 * an approved reconciliation ({@code FINALIZED → INVALIDATED}), keyed by {@link #reconciliationId}: a
 * ledger line inside the approved window was reversed or posted, the feed removed a matched
 * transaction (phase 2), or the statement was superseded by a corrected one. It is the alerting hook of
 * the bank reconciliation: the period cannot close until a superseding reconciliation is approved.
 *
 * @param reconciliationId the invalidated reconciliation
 * @param glAccountId the reconciled ledger bank account
 * @param reason why the approval no longer holds
 * @param journalEntryId the journal entry whose posting or reversal invalidated it; null for a
 *     statement supersession or a feed removal
 */
public record BankReconciliationInvalidatedV1(
        @NonNull UUID reconciliationId,
        @NonNull UUID glAccountId,
        @NonNull Reason reason,
        @Nullable UUID journalEntryId) {

    public static final String EVENT_TYPE = "accounting.bankreconciliation.invalidated";
    public static final int SCHEMA_VERSION = 1;

    /** Why an approved reconciliation was invalidated (§3.10). */
    public enum Reason {
        LEDGER_LINE_REVERSED,
        LEDGER_LINE_POSTED,
        SOURCE_REMOVED,
        STATEMENT_SUPERSEDED
    }

    public BankReconciliationInvalidatedV1 {
        if (reconciliationId == null || glAccountId == null) {
            throw new IllegalArgumentException("reconciliationId and glAccountId must not be null");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
    }
}

package com.positivity.domainevents.accounting;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Payload for {@code accounting.bankreconciliation.superseded} v1 on {@code accounting.events.v1}
 * (SPEC-manual-bank-reconciliation §3.10, §4.9; story S5, #2304).
 *
 * <p>Published by pos-accounting through its transactional outbox in the transaction that approves a
 * superseding reconciliation, keyed by the superseded {@link #reconciliationId}: the predecessor ({@code
 * FINALIZED} or {@code INVALIDATED}) became {@code SUPERSEDED}. Both stay readable. No consumer is named.
 *
 * @param reconciliationId the superseded reconciliation
 * @param glAccountId the reconciled ledger bank account
 * @param supersededByReconciliationId the approved reconciliation that replaces it
 */
public record BankReconciliationSupersededV1(
        @NonNull UUID reconciliationId,
        @NonNull UUID glAccountId,
        @NonNull UUID supersededByReconciliationId) {

    public static final String EVENT_TYPE = "accounting.bankreconciliation.superseded";
    public static final int SCHEMA_VERSION = 1;

    public BankReconciliationSupersededV1 {
        if (reconciliationId == null || glAccountId == null) {
            throw new IllegalArgumentException("reconciliationId and glAccountId must not be null");
        }
        if (supersededByReconciliationId == null) {
            throw new IllegalArgumentException("supersededByReconciliationId must not be null");
        }
    }
}

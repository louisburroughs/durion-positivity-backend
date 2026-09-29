package com.positivity.domainevents.accounting;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Payload for {@code accounting.bankreconciliation.cancelled} v1 on {@code accounting.events.v1}
 * (SPEC-manual-bank-reconciliation §3.10, §4.9; story S5, #2304).
 *
 * <p>Published by pos-accounting through its transactional outbox in the transaction that cancels an
 * {@code IN_PROGRESS} or {@code SUBMITTED} reconciliation, keyed by {@link #reconciliationId}. Its
 * matches were unmatched and the outstanding items it registered released; posted adjustments stay
 * posted. No consumer is named.
 *
 * @param reconciliationId the cancelled reconciliation
 * @param glAccountId the reconciled ledger bank account
 * @param reason the approver's justification
 * @param cancelledBy the approver (ADR-0018)
 */
public record BankReconciliationCancelledV1(
        @NonNull UUID reconciliationId,
        @NonNull UUID glAccountId,
        @NonNull String reason,
        @NonNull String cancelledBy) {

    public static final String EVENT_TYPE = "accounting.bankreconciliation.cancelled";
    public static final int SCHEMA_VERSION = 1;

    public BankReconciliationCancelledV1 {
        if (reconciliationId == null || glAccountId == null) {
            throw new IllegalArgumentException("reconciliationId and glAccountId must not be null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (cancelledBy == null || cancelledBy.isBlank()) {
            throw new IllegalArgumentException("cancelledBy must not be blank");
        }
    }
}

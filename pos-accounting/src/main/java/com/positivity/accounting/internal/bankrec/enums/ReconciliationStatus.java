package com.positivity.accounting.internal.bankrec.enums;

/**
 * Status of a bank reconciliation (SPEC-manual-bank-reconciliation §3.7, §3.8; S1 #2300, S5 #2304).
 *
 * <p>{@code IN_PROGRESS → SUBMITTED} (submit, E4) · {@code SUBMITTED → IN_PROGRESS} (return) · {@code
 * SUBMITTED → FINALIZED} (approve, E4) · {@code IN_PROGRESS | SUBMITTED → CANCELLED} · {@code FINALIZED →
 * INVALIDATED} (a ledger change inside the window, or the statement superseded) · {@code FINALIZED |
 * INVALIDATED → SUPERSEDED} (a successor approved). {@code FINALIZED}, {@code SUPERSEDED} and {@code
 * CANCELLED} are terminal; {@code INVALIDATED} is terminal for editing and exists to be superseded.
 */
public enum ReconciliationStatus {
    IN_PROGRESS,
    SUBMITTED,
    FINALIZED,
    INVALIDATED,
    SUPERSEDED,
    CANCELLED
}

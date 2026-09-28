package com.positivity.accounting.internal.bankrec.enums;

/**
 * Status of a bank reconciliation (SPEC-manual-bank-reconciliation §3.7, §3.8).
 *
 * <p>Story S1 (#2300) adds the value set only. The one reachable transition is still
 * {@code IN_PROGRESS → FINALIZED}; {@code SUBMITTED}, {@code INVALIDATED}, {@code SUPERSEDED}
 * and {@code CANCELLED} gain their transitions in story S5. The API keeps serving the F2 value set
 * until then ({@code ReconciliationApiStatus}).
 */
public enum ReconciliationStatus {
    IN_PROGRESS,
    SUBMITTED,
    FINALIZED,
    INVALIDATED,
    SUPERSEDED,
    CANCELLED
}

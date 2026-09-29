package com.positivity.accounting.internal.bankrec.enums;

/**
 * The close-readiness checks (SPEC-manual-bank-reconciliation §5.3; story S6, #2305), each with its severity
 * under a {@code REQUIRED*} policy. A check appears in a readiness response only when it fires.
 */
public enum ReadinessCheckCode {
    /** DRAFT journal entries dated inside the period (EXISTING close rule; tenant-wide). */
    DRAFT_JOURNAL_ENTRIES(ReadinessSeverity.BLOCKING),
    /** No COMMITTED statement reaches {@code periodEndDate − lag}. */
    STATEMENT_COVERAGE(ReadinessSeverity.BLOCKING),
    /** The contiguous FINALIZED chain from the baseline does not reach {@code periodEndDate − lag}. */
    RECONCILIATION_APPROVED(ReadinessSeverity.BLOCKING),
    /** An {@code IN_PROGRESS} or {@code SUBMITTED} reconciliation ends on/before the period end. */
    RECONCILIATION_IN_FLIGHT(ReadinessSeverity.BLOCKING),
    /** An {@code INVALIDATED} reconciliation intersecting the period has no FINALIZED successor. */
    RECONCILIATION_INVALIDATED(ReadinessSeverity.BLOCKING),
    /** The covering reconciliation's {@code approvedGlEndingBalance} differs from the live as-of balance. */
    BALANCE_AGREEMENT(ReadinessSeverity.BLOCKING),
    /** Unexplained bank transactions from the baseline that applies at the period end to the period end. */
    UNEXPLAINED_BANK_TRANSACTIONS(ReadinessSeverity.BLOCKING),
    /** Unexplained ledger lines from the baseline that applies at the period end to the period end. */
    UNEXPLAINED_LEDGER_LINES(ReadinessSeverity.BLOCKING),
    /** Adjustment rows with no journal entry, or one that is not POSTED (defensive). */
    UNPOSTED_ADJUSTMENTS(ReadinessSeverity.BLOCKING),
    /** The reconciled frontier passed only because of the coverage lag. */
    COVERAGE_LAG_APPLIED(ReadinessSeverity.WARNING),
    /** {@code UPLOADED} / {@code VALIDATED} imports with rows dated on/before the period end. */
    INCOMPLETE_IMPORTS(ReadinessSeverity.WARNING),
    /** {@code OPEN} outstanding items older than the aging threshold. */
    OUTSTANDING_ITEMS_AGING(ReadinessSeverity.WARNING),
    /** A clearing account not cleared back to zero within the aging window (tenant-wide). */
    CLEARING_BALANCE_AGING(ReadinessSeverity.WARNING),
    /** Unresolved bank transactions that arrived after their window was approved (phase 2 only). */
    LATE_BANK_TRANSACTIONS(ReadinessSeverity.WARNING),
    /** The covering reconciliation was approved after the period was closed. */
    RECONCILED_AFTER_CLOSE(ReadinessSeverity.INFO);

    private final ReadinessSeverity severity;

    ReadinessCheckCode(ReadinessSeverity severity) {
        this.severity = severity;
    }

    public ReadinessSeverity severity() {
        return severity;
    }
}

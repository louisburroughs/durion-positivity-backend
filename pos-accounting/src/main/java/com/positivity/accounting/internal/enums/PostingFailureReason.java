package com.positivity.accounting.internal.enums;

import org.jspecify.annotations.Nullable;

/**
 * Reasons why posting rule evaluation can fail.
 * Used to provide actionable feedback when posting engine cannot produce a
 * journal entry.
 */
public enum PostingFailureReason {
    /**
     * No published posting rule version found for the organization and transaction
     * date.
     * Indicates missing or inactive rule configuration.
     */
    NO_RULE_VERSION,

    /**
     * Event type does not match any rule mappings (exact, fallback, or category
     * default).
     * Event will be marked SUSPENDED pending manual mapping configuration.
     */
    UNMAPPED_EVENT_TYPE,

    /**
     * Generated journal entry is not balanced (debits != credits).
     * Indicates a bug in mapping rules or calculation logic.
     */
    UNBALANCED_JOURNAL,

    /**
     * Multiple exact matches found for mapping key (ambiguous mapping).
     * Requires manual intervention to resolve mapping conflicts.
     */
    AMBIGUOUS_MAPPING,

    /**
     * Event payload failed validation (missing required fields, invalid data).
     * Event must be corrected at source or schema updated.
     */
    VALIDATION_ERROR,

    /**
     * Internal error occurred during rule evaluation.
     * Review logs and exception details for root cause.
     */
    INTERNAL_ERROR,

    /**
     * Event transaction date falls in a CLOSED (or hard-locked) accounting
     * period (story B2, issue #944). Event is marked SUSPENDED; the scheduled
     * auto-retry loop skips it (a closed period will not reopen on a retry
     * cadence) but manual reprocessing after the period is reopened works.
     */
    PERIOD_CLOSED,

    /**
     * A Kafka-consumed inventory posting fact (adjustment or scrap) carried no
     * positive {@code unitCost}, so no journal entry is posted (issue #2191).
     * Event is recorded SKIPPED — terminal, never retried: the fact carries the
     * cost at posting time and a later cost is a different fact.
     */
    UNCOSTED_FACT,

    /**
     * A default-GL-mapping event whose {@code payload.amount} is absent or unreadable, with
     * {@code pos.accounting.default-mappings.require-amount-field=false} (issue #2315). No journal
     * entry is posted — the ledger never holds an amount no source stated (ADR-0067 DF-7). Event
     * is recorded SKIPPED — terminal, never retried.
     */
    MISSING_AMOUNT,

    /**
     * A default-GL-mapping event whose {@code payload.amount} is stated as zero, with
     * {@code pos.accounting.default-mappings.require-amount-field=false} (issue #2315). Nothing to
     * post; event is recorded SKIPPED — terminal, never retried.
     */
    ZERO_AMOUNT,

    /**
     * A consumed fact states an amount in a currency other than the ledger currency (ADR-0067
     * PC-9, E-5; issues #2312, #2334). Never booked at par: the fact is parked visibly as {@code
     * SUSPENDED} with this reason until a later stage can release it (a booking rate, B1, or manual
     * handling). The scheduled auto-retry loop skips it, as it skips {@link #PERIOD_CLOSED}: the
     * retry cadence cannot change the ledger's currency. It is released through the audited
     * reprocess path, which re-suspends it with this reason while its currency is still not the
     * ledger's.
     */
    CURRENCY_NOT_SUPPORTED,

    /**
     * A Kafka-consumed fact its listener deliberately does not post (issue #2433): a stale fact the
     * replica guard skipped, a deposit-take invoice (a contract liability, not revenue), an invoice
     * fact with no {@code finalizedAt}, or a status that neither recognizes nor reverses revenue.
     * Event is recorded SKIPPED — terminal, never retried.
     */
    NOT_POSTABLE;

    /**
     * Whether an event failing for this reason ends in the terminal {@code SKIPPED} status: the
     * event deliberately posts nothing and a retry cannot change that, as opposed to a gap an
     * operator fixes and reprocesses ({@code SUSPENDED}).
     */
    public boolean isTerminalSkip() {
        return this == UNCOSTED_FACT || this == MISSING_AMOUNT || this == ZERO_AMOUNT || this == NOT_POSTABLE;
    }

    /**
     * Whether a {@code SUSPENDED} event with this reason is left out of the scheduled auto-retry
     * loop: its remedy is an operator action (reopening a period, a booking rate or manual handling
     * for a currency), never the passage of time, so retrying on a cadence only burns attempts. It
     * stays eligible for the audited manual reprocess.
     */
    public boolean isExcludedFromAutoRetry() {
        return this == PERIOD_CLOSED || this == CURRENCY_NOT_SUPPORTED;
    }

    /**
     * {@link #isExcludedFromAutoRetry()} for a stored {@code failureReasonCode}; an absent or
     * unknown code is not excluded.
     */
    public static boolean isExcludedFromAutoRetry(@Nullable String failureReasonCode) {
        if (failureReasonCode == null) {
            return false;
        }
        for (PostingFailureReason reason : values()) {
            if (reason.name().equals(failureReasonCode)) {
                return reason.isExcludedFromAutoRetry();
            }
        }
        return false;
    }
}

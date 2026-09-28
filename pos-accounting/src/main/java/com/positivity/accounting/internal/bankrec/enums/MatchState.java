package com.positivity.accounting.internal.bankrec.enums;

/** State of a reconciliation match header (SPEC §3.4). Matches are never deleted (M7). */
public enum MatchState {
    PROPOSED,
    ACCEPTED,
    REJECTED,
    UNMATCHED,
    BROKEN
}

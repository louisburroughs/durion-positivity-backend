package com.positivity.accounting.internal.bankrec.enums;

/**
 * Cardinality of a reconciliation match (SPEC §3.4): {@code ONE_TO_MANY} is one bank transaction to
 * several ledger lines, {@code MANY_TO_ONE} several bank transactions to one ledger line, and
 * {@code ADJUSTMENT} a bank transaction to the cash line of an adjustment journal entry.
 */
public enum MatchKind {
    ONE_TO_ONE,
    ONE_TO_MANY,
    MANY_TO_ONE,
    ADJUSTMENT
}

package com.positivity.accounting.internal.bankrec.enums;

/** Status of a bank statement (SPEC §3.1): immutable once committed; a corrected re-import supersedes it (S5). */
public enum BankStatementStatus {
    COMMITTED,
    SUPERSEDED
}

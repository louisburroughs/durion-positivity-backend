package com.positivity.accounting.internal.bankrec.enums;

/** Provenance of a bank statement or bank transaction (SPEC §3.1, §3.2). */
public enum SourceKind {
    FILE_IMPORT,
    MANUAL_ENTRY,
    BANK_FEED
}

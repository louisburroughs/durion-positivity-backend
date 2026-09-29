package com.positivity.accounting.internal.bankfeed.file.enums;

/** Lifecycle of a statement-file import session (SPEC §3.3, §3.8; story S3, #2302). */
public enum BankImportStatus {
    UPLOADED,
    VALIDATED,
    COMMITTED,
    DISCARDED
}

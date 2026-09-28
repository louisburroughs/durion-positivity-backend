package com.positivity.accounting.internal.bankfeed.file.enums;

/** Lifecycle of a statement-file import session (SPEC §3.3, §3.8); behaviour arrives with story S3. */
public enum BankImportStatus {
    UPLOADED,
    VALIDATED,
    COMMITTED,
    DISCARDED
}

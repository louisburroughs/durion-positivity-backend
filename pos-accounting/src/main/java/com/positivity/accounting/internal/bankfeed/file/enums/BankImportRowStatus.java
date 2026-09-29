package com.positivity.accounting.internal.bankfeed.file.enums;

/** Status of one parsed row of a statement-file import (SPEC §3.3, §3.8; story S3, #2302). */
public enum BankImportRowStatus {
    PARSED,
    REJECTED,
    CORRECTED,
    SKIPPED,
    POSSIBLE_DUPLICATE,
    OUT_OF_WINDOW,
    COMMITTED
}

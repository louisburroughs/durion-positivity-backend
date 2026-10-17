package com.positivity.accounting.internal.billintake.enums;

/** How the fields of a draft were filled. S26 adds the reading outcomes; before it, a person keys the bill. */
public enum BillExtractionOutcome {
    /** Nothing was read: every field is empty and marked CHECK_THIS for a person to key. */
    MANUAL,
    /** The document was read by the extraction worker (S26). */
    EXTRACTED
}

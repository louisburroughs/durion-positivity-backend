package com.positivity.accounting.internal.billintake.enums;

/** Where a bill draft is in its life (#2518). Only {@link #READY_FOR_REVIEW} is edited, confirmed or discarded. */
public enum BillIntakeStatus {
    /** Received and stored apart; not yet scanned and inspected (Part B). */
    QUARANTINED,
    /** The file was refused (type, malware, scan failure, inspection); the item keeps the reason. */
    FILE_REJECTED,
    /** Clean, being read. */
    EXTRACTING,
    /** Read, or not readable and left blank: waiting for a person to check it. */
    READY_FOR_REVIEW,
    /** A person confirmed it; {@code confirmedBillId} names the bill. */
    CONFIRMED,
    /** A person discarded it, with a reason. */
    DISCARDED
}

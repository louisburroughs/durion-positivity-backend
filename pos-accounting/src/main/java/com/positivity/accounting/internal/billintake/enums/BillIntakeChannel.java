package com.positivity.accounting.internal.billintake.enums;

/** How a bill draft reached accounting (#2518; SPEC-accounting-workspace 4.8). */
public enum BillIntakeChannel {
    /** A person uploaded a document (a PDF or an image). */
    UPLOAD,
    /** A person imported a spreadsheet; one draft per invoice in it. */
    IMPORT,
    /** A supplier invoice fact over EDI that names no vendor, until a person picks one. */
    EDI,
    /** A document received by email (S27); not written before it lands. */
    EMAIL_IN
}

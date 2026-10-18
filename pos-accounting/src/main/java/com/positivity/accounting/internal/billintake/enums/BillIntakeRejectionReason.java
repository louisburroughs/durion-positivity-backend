package com.positivity.accounting.internal.billintake.enums;

/**
 * Why a draft's file was refused ({@code FILE_REJECTED}). The inspection reasons are the extraction worker's reason
 * codes (S26); an unknown code from a worker is stored as {@link #UNREADABLE}, never as the worker's text.
 */
public enum BillIntakeRejectionReason {
    /** The bytes are not a PDF, JPEG, PNG, HEIC or TIFF (magic bytes); nothing was scanned. */
    TYPE_NOT_ALLOWED,
    /** The malware scanner found something. */
    MALWARE_FOUND,
    /** The scanner answered and refused this file (for example over its stream limit). */
    SCAN_FAILED,
    TYPE_MISMATCH,
    ENCRYPTED_PDF,
    PDF_ACTIVE_CONTENT,
    PAGE_LIMIT_EXCEEDED,
    MACRO_CONTENT,
    EXTERNAL_ENTITY,
    DECOMPRESSION_LIMIT_EXCEEDED,
    RESOURCE_LIMIT_EXCEEDED,
    UNREADABLE;

    /** The reason for a worker's code; {@link #UNREADABLE} for one this build does not know. */
    public static BillIntakeRejectionReason ofWorkerCode(String code) {
        if (code != null) {
            for (BillIntakeRejectionReason reason : values()) {
                if (reason.name().equals(code) && reason != TYPE_NOT_ALLOWED && reason != MALWARE_FOUND
                        && reason != SCAN_FAILED) {
                    return reason;
                }
            }
        }
        return UNREADABLE;
    }
}

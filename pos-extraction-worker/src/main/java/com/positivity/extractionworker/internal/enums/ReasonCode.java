package com.positivity.extractionworker.internal.enums;

/** Why an inspection rejected a file. The names are the wire codes. */
public enum ReasonCode {
    TYPE_MISMATCH,
    ENCRYPTED_PDF,
    PDF_ACTIVE_CONTENT,
    PAGE_LIMIT_EXCEEDED,
    MACRO_CONTENT,
    EXTERNAL_ENTITY,
    DECOMPRESSION_LIMIT_EXCEEDED,
    RESOURCE_LIMIT_EXCEEDED,
    UNREADABLE
}

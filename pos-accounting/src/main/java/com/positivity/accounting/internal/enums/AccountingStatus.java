package com.positivity.accounting.internal.enums;

/**
 * Status of accounting posting.
 */
public enum AccountingStatus {
    /**
     * Pending posting to GL.
     */
    PENDING_POSTING,

    /**
     * Posted to GL.
     */
    POSTED,

    /**
     * Failed to post.
     */
    FAILED
}

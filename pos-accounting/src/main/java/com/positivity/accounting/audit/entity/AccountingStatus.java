package com.positivity.accounting.audit.entity;

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

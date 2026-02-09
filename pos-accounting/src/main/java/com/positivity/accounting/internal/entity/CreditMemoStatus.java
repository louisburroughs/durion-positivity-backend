package com.positivity.accounting.internal.entity;

/**
 * Status values for Credit Memo lifecycle.
 * 
 * Lifecycle:
 * DRAFT → POSTED → APPLIED or VOIDED
 */
public enum CreditMemoStatus {
    /**
     * Credit Memo created but not yet posted to GL.
     */
    DRAFT,

    /**
     * Credit Memo posted to GL; AR reduced.
     */
    POSTED,

    /**
     * Credit Memo applied to customer account or future invoice.
     */
    APPLIED,

    /**
     * Credit Memo voided/reversed.
     */
    VOIDED
}

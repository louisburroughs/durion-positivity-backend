package com.positivity.accounting.audit.entity;

/**
 * Result of policy validation for price overrides.
 */
public enum PolicyValidationResult {
    /**
     * Override approved per policy.
     */
    APPROVED,
    
    /**
     * Override rejected due to forbidden category.
     */
    REJECTED_FORBIDDEN,
    
    /**
     * Override rejected due to threshold exceeded.
     */
    REJECTED_THRESHOLD_EXCEEDED
}

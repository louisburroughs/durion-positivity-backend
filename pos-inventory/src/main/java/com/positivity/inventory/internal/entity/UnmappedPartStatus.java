package com.positivity.inventory.internal.entity;

/**
 * Status lifecycle for manufacturer parts that cannot yet be mapped to
 * products.
 *
 * Issue: CAP-170 (#46)
 */
public enum UnmappedPartStatus {
    PENDING_REVIEW,
    RESOLVED,
    IGNORED
}

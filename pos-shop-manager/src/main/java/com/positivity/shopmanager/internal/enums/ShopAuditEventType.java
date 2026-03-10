package com.positivity.shopmanager.internal.enums;

/**
 * Classifies the type of audit event recorded in the shop audit trail.
 * Story #61 — Audit Trail for Schedule and Assignment Changes.
 */
public enum ShopAuditEventType {
    SCHEDULE_CREATED,
    SCHEDULE_UPDATED,
    SCHEDULE_CANCELLED,
    ASSIGNMENT_CREATED,
    ASSIGNMENT_REMOVED
}

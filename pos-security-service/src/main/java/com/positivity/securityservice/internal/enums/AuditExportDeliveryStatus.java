package com.positivity.securityservice.internal.enums;

/**
 * Outcome of delivering a {@code WEBHOOK} audit export to the tenant's registered endpoint (#2497).
 * Null on a {@code DOWNLOAD} job and on a {@code WEBHOOK} job that has not finished rendering.
 */
public enum AuditExportDeliveryStatus {
    /** An attempt answered 2xx; the job is COMPLETED. */
    DELIVERED,
    /** Every attempt failed, or delivery was refused before anything was sent; the job is FAILED. */
    FAILED
}

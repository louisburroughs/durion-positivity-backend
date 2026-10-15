package com.positivity.securityservice.internal.exception;

/**
 * The caller's tenant has no audit export webhook destination registered (#2497). One code, {@code
 * AUDIT_EXPORT_WEBHOOK_NOT_REGISTERED}, answered two ways by {@code GlobalExceptionHandler}: 404 when
 * the destination itself is read ({@link #forLookup()}), and 422 when a {@code WEBHOOK} export is
 * requested without one ({@link #forExport()}), where the request is well-formed but cannot be
 * carried out and no job is created.
 */
public class AuditExportWebhookNotRegisteredException extends RuntimeException {

    private final boolean exportRequest;

    private AuditExportWebhookNotRegisteredException(String message, boolean exportRequest) {
        super(message);
        this.exportRequest = exportRequest;
    }

    /** The destination was read and there is none: 404. */
    public static AuditExportWebhookNotRegisteredException forLookup() {
        return new AuditExportWebhookNotRegisteredException(
                "No audit export webhook destination is registered for this tenant", false);
    }

    /** A WEBHOOK export was requested and there is nowhere to deliver it: 422. */
    public static AuditExportWebhookNotRegisteredException forExport() {
        return new AuditExportWebhookNotRegisteredException(
                "WEBHOOK delivery needs a registered audit export webhook destination; register one with"
                        + " PUT /v1/audit/exports/webhook, or request the export with deliveryMode DOWNLOAD",
                true);
    }

    /** True when a WEBHOOK export request was refused (422), false for a lookup (404). */
    public boolean exportRequest() {
        return exportRequest;
    }
}

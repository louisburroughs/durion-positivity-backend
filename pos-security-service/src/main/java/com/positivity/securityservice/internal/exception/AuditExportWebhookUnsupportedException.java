package com.positivity.securityservice.internal.exception;

/**
 * An audit export asked for {@code WEBHOOK} delivery (#2408). No per-tenant webhook endpoint is
 * designed yet, so the request names no destination and nothing could deliver it. Answered as 400
 * {@code AUDIT_EXPORT_WEBHOOK_UNSUPPORTED}.
 */
public class AuditExportWebhookUnsupportedException extends RuntimeException {

    public AuditExportWebhookUnsupportedException() {
        super("WEBHOOK delivery is not supported yet; request the export with deliveryMode DOWNLOAD");
    }
}

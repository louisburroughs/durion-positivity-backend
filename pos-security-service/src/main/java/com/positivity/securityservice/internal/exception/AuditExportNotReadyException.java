package com.positivity.securityservice.internal.exception;

import com.positivity.securityservice.internal.enums.AuditExportStatus;
import java.util.UUID;

/**
 * The audit export job exists but has no file to download because it is not {@code COMPLETED}
 * (#2408). Answered as 409 {@code AUDIT_EXPORT_NOT_READY}.
 */
public class AuditExportNotReadyException extends RuntimeException {

    public AuditExportNotReadyException(UUID jobId, AuditExportStatus status) {
        super("Audit export job " + jobId + " is " + status + "; only a COMPLETED job can be downloaded");
    }
}

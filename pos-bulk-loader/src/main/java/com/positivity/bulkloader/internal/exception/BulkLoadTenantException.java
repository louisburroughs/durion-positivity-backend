package com.positivity.bulkloader.internal.exception;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;

/**
 * A bulk-load job could not be bound to a target tenant (ADR-0062, plan WS8). Carries the
 * {@code ApiError} code and status {@code BulkLoaderExceptionHandler} answers with, so the four
 * refusals stay distinguishable to a client: no tenant at all (400), a tenant the module does not
 * know as active (400), a tenant the caller is not allowed to load into (403), and a domain type
 * the platform tenant does not accept (403).
 */
public class BulkLoadTenantException extends RuntimeException {

    /** No {@code tenantId} on the request and no transitional default tenant to fall back on. */
    public static final String TENANT_REQUIRED = "BULK_JOB_TENANT_REQUIRED";

    /** The requested tenant is neither an active tenant of the registry nor the platform tenant. */
    public static final String TENANT_UNKNOWN = "BULK_JOB_TENANT_UNKNOWN";

    /** The caller is bound to a tenant other than the one the request names. */
    public static final String TENANT_FORBIDDEN = "BULK_JOB_TENANT_FORBIDDEN";

    /**
     * The target is the platform tenant, but the job's {@code domainType} is not one of the platform
     * data packs it is scoped to (ADR-0062 §7; Copilot review of PR #1955, Finding 5).
     */
    public static final String TENANT_DOMAIN_FORBIDDEN = "BULK_JOB_TENANT_DOMAIN_FORBIDDEN";

    private final String code;
    private final HttpStatus status;

    public BulkLoadTenantException(@NonNull String code, @NonNull HttpStatus status, @NonNull String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public @NonNull String getCode() {
        return code;
    }

    public @NonNull HttpStatus getStatus() {
        return status;
    }
}

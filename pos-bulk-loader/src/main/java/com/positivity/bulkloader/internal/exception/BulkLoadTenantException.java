package com.positivity.bulkloader.internal.exception;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;

/**
 * A bulk-load job could not be bound to a target tenant (ADR-0062, plan WS8). Carries the
 * {@code ApiError} code and status {@code BulkLoaderExceptionHandler} answers with, so the three
 * refusals stay distinguishable to a client: no tenant at all (400), a tenant the module does not
 * know as active (400), and a tenant the caller is not allowed to load into (403).
 */
public class BulkLoadTenantException extends RuntimeException {

    /** No {@code tenantId} on the request and no transitional default tenant to fall back on. */
    public static final String TENANT_REQUIRED = "BULK_JOB_TENANT_REQUIRED";

    /** The requested tenant is neither an active tenant of the registry nor the platform tenant. */
    public static final String TENANT_UNKNOWN = "BULK_JOB_TENANT_UNKNOWN";

    /** The caller is bound to another tenant and is not a platform-tenant caller. */
    public static final String TENANT_FORBIDDEN = "BULK_JOB_TENANT_FORBIDDEN";

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

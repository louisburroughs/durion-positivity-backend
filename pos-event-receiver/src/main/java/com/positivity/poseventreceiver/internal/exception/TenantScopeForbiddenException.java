package com.positivity.poseventreceiver.internal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A caller bound to an ordinary tenant asked for another tenant's statistics (ADR-0062 plan WS6):
 * the {@code tenantId} query parameter of the summary endpoints is accepted from the platform
 * tenant only. Rendered as a 403 {@code ApiError} by the shared exception handler.
 */
@ResponseStatus(code = HttpStatus.FORBIDDEN, reason = TenantScopeForbiddenException.REASON)
public class TenantScopeForbiddenException extends RuntimeException {

    public static final String REASON = "tenantId may be requested from the platform tenant only";

    private static final long serialVersionUID = 1L;

    public TenantScopeForbiddenException() {
        super(REASON);
    }
}

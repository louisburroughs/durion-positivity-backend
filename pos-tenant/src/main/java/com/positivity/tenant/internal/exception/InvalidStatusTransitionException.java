package com.positivity.tenant.internal.exception;

import com.positivity.tenant.internal.enums.TenantStatus;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The tenant status machine (ADR-0062 §7) does not allow the requested move. */
@ResponseStatus(HttpStatus.CONFLICT)
public class InvalidStatusTransitionException extends IllegalStateException {

    public InvalidStatusTransitionException(UUID tenantId, TenantStatus from, TenantStatus to) {
        super("Tenant " + tenantId + " cannot move from " + from + " to " + to);
    }
}

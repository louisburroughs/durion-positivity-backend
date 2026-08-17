package com.positivity.workorder.internal.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No fleet payment authorization is recorded for a workorder (#1346). */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class FleetAuthorizationNotFoundException extends RuntimeException {

    public FleetAuthorizationNotFoundException(UUID workorderId) {
        super("Workorder " + workorderId + " has no fleet payment authorization to resolve");
    }
}

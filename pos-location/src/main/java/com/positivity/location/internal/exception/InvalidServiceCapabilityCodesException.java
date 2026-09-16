package com.positivity.location.internal.exception;

import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A resource's specialty claim named a code that is not an active catalog operation code (CAP-325
 * D14). Rendered as 422 by {@code LocationGlobalExceptionHandler}, which is what {@code BayRequest}
 * and {@code MobileUnitRequest} document; a bare {@code IllegalArgumentException} fell through to the
 * catch-all as 500 (#2045 review).
 */
public class InvalidServiceCapabilityCodesException extends ResponseStatusException {

    private final List<String> invalidCodes;

    public InvalidServiceCapabilityCodesException(Collection<String> invalidCodes) {
        this("Invalid serviceCapabilityCodes: " + String.join(", ", invalidCodes), invalidCodes);
    }

    public InvalidServiceCapabilityCodesException(String reason, Collection<String> invalidCodes) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, reason);
        this.invalidCodes = List.copyOf(invalidCodes);
    }

    /** The offending codes, normalized; {@code <blank>} stands for a blank entry. */
    public List<String> getInvalidCodes() {
        return invalidCodes;
    }
}

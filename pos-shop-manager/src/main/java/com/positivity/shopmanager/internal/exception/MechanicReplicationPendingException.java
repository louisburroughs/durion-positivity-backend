package com.positivity.shopmanager.internal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A write named a person this service cannot yet resolve to a mechanic, and it cannot rule out
 * that the person is simply one it has not seen (#1987).
 *
 * <p>Mechanic rows are projected from ACTIVE TECHNICIAN staffing assignments arriving on
 * {@code people.events.v1}, so between pos-people storing an assignment and this service
 * consuming it there is a window in which a perfectly real mechanic is not here yet. Answering
 * {@code 404} in that window told the caller "no such mechanic" about a person who plainly
 * existed, and left it no way to decide whether retrying was sensible — the ambiguity this type
 * exists to remove. It means only "not yet, ask again"; a person this service holds an assignment
 * history for, none of it an ACTIVE TECHNICIAN one, is a real {@code 404}.
 *
 * <p>Carries {@code @ResponseStatus} as well as its advice mapping so that a bulk-ingest row —
 * which is reported inside a {@code 200} body and never reaches an advice — can be classified as
 * retryable rather than as a server-side fault.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class MechanicReplicationPendingException extends RuntimeException {

    private final transient String personId;

    public MechanicReplicationPendingException(String personId, String message) {
        super(message);
        this.personId = personId;
    }

    public String getPersonId() {
        return personId;
    }
}

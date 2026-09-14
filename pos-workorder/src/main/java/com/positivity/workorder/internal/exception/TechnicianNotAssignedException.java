package com.positivity.workorder.internal.exception;

/**
 * A reassign or release was asked for on a workorder that has no current technician (#1985).
 *
 * <p>Reassign is deliberately <em>not</em> treated as an assign when there is nobody to reassign
 * from. The two operations now mean different things — assign requires the workorder to be free,
 * reassign requires it to be held — and quietly promoting one to the other would put back exactly
 * the ambiguity {@link TechnicianAlreadyAssignedException} exists to remove: a caller who reassigns
 * a workorder it believed was held, and gets a 200, has learned nothing about the fact that its
 * view was stale.
 *
 * <p>409 for the same reason as its twin: well-formed request, refused by current state, and the
 * state is one {@code POST} (assign) can move past.
 */
public class TechnicianNotAssignedException extends RuntimeException {

    public static final String ERROR_CODE = "TECHNICIAN_NOT_ASSIGNED";

    public static final String NEXT_ACTION =
            "Use POST /v1/workorders/{workorderId}/technician to make the first assignment.";

    public TechnicianNotAssignedException(String message) {
        super(message);
    }
}

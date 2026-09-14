package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * {@code POST /v1/workorders/{id}/technician} was called on a workorder that already has a current
 * technician (#1985).
 *
 * <p>Assign used to overwrite silently, which made an accidental double assign indistinguishable
 * from a deliberate hand-over: the first technician's row was closed with a canned reason and
 * nobody was told. Assign now means "this workorder has no technician yet"; changing hands is
 * {@code PUT} (reassign), which takes a reason. 409 because the request is well-formed and the
 * refusal is about the workorder's current state, which {@code PUT} can move past.
 *
 * <p>The current technician's id rides on the {@code ApiError} as {@code referenceId} so the caller
 * can say who holds the job without a second round trip.
 */
public class TechnicianAlreadyAssignedException extends RuntimeException {

    public static final String ERROR_CODE = "TECHNICIAN_ALREADY_ASSIGNED";

    public static final String NEXT_ACTION = "Use PUT /v1/workorders/{workorderId}/technician to reassign the "
            + "workorder to a different technician, with a reason.";

    private final transient UUID currentTechnicianId;

    public TechnicianAlreadyAssignedException(String message, UUID currentTechnicianId) {
        super(message);
        this.currentTechnicianId = currentTechnicianId;
    }

    /** The technician currently holding the workorder. */
    public UUID getCurrentTechnicianId() {
        return currentTechnicianId;
    }
}

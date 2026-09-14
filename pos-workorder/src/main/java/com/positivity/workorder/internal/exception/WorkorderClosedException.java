package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * A closed workorder cannot have its service position or technician changed (#1983).
 *
 * <p>Closed means COMPLETED or CANCELLED — {@link
 * com.positivity.workorder.internal.entity.Workorder#isLocked()}, the same authority the dispatch
 * board and the occupancy index read, so a reopened COMPLETED workorder is <em>not</em> closed and
 * may still be moved. Assignment operations are open-lifecycle operations: once the job is done or
 * abandoned its position has already been released and reassigning it would resurrect an occupancy
 * claim nobody is honouring.
 *
 * <p>409 with a stable code, as the story requires: well-formed request, refused by the workorder's
 * current state.
 */
public class WorkorderClosedException extends RuntimeException {

    public static final String ERROR_CODE = "WORKORDER_CLOSED";

    public WorkorderClosedException(UUID workorderId, String status) {
        super("Workorder " + workorderId + " is " + status
                + "; its service position and technician can no longer be changed");
    }
}

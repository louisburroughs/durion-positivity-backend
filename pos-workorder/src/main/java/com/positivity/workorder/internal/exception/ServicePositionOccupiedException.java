package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * A service position already holds an open workorder, so it cannot take another (#1984).
 *
 * <p>409 rather than 422: the request is well-formed and the position is a real one the caller may
 * use — it is the <em>current state of the position</em> that refuses the assignment, and it will
 * stop refusing the moment the occupying workorder completes, is cancelled, or moves. That is the
 * conflict shape ADR-0017 §2 puts at 409.
 *
 * <p>The occupying workorder's id is carried as {@code referenceId} on the {@code ApiError} rather
 * than only being written into the message, so a dispatch board can link straight to the job in the
 * bay instead of parsing prose. It is nullable: the id is only known when the application-level
 * check is the thing that refused, and the same code is raised when the partial unique index
 * {@code workorder_open_position_uniq} refuses a racing assign, where the winner is whichever
 * transaction committed first and is not in this one's hands.
 */
public class ServicePositionOccupiedException extends RuntimeException {

    public static final String ERROR_CODE = "RESOURCE_OCCUPIED";

    private final transient UUID occupyingWorkorderId;

    public ServicePositionOccupiedException(String message, UUID occupyingWorkorderId) {
        super(message);
        this.occupyingWorkorderId = occupyingWorkorderId;
    }

    /** The open workorder already on the position, or {@code null} when a race hid its identity. */
    public UUID getOccupyingWorkorderId() {
        return occupyingWorkorderId;
    }
}

package com.positivity.workorder.internal.exception;

import java.util.UUID;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A workorder carries an {@code approvalId} but its own approval state does not back it: the
 * status is not {@code APPROVED}, or no {@code approvedAt} was ever stamped (issue #1477).
 *
 * <p>A conflict with the workorder's current state rather than a malformed request, and never
 * transient — nothing arrives later that makes an unapproved workorder approved.
 */
@Getter
public class CustomerApprovalInvalidException extends RuntimeException {

    public static final String ERROR_CODE = "CUSTOMER_APPROVAL_INVALID";

    public static final String NEXT_ACTION = "Record a customer approval on the workorder before creating it.";

    private final @Nullable UUID workorderId;

    public CustomerApprovalInvalidException(@NonNull String message, @Nullable UUID workorderId) {
        super(message);
        this.workorderId = workorderId;
    }
}

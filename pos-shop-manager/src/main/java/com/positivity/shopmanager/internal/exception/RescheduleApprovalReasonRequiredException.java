package com.positivity.shopmanager.internal.exception;

/**
 * DECISION-SHOPMGMT-004: the caller holds {@code appointments:reschedule:approve} and this
 * reschedule needs it (the 3rd or later non-exempt one on the appointment), but {@code
 * approvalReason} was missing or blank. Mapped to {@code 422} with {@code fieldErrors} naming
 * {@code approvalReason}, distinct from the plain {@code 400 VALIDATION_ERROR} a missing required
 * field otherwise gets: like {@link ServicePositionEligibilityException}, this is a policy
 * condition on an otherwise well-formed request (the field is conditionally required, not always),
 * never overridable.
 */
public class RescheduleApprovalReasonRequiredException extends RuntimeException {

    public static final String CODE = "RESCHEDULE_APPROVAL_REASON_REQUIRED";

    public RescheduleApprovalReasonRequiredException() {
        super("approvalReason is required to approve a reschedule beyond the free allowance"
                + " (DECISION-SHOPMGMT-004)");
    }
}

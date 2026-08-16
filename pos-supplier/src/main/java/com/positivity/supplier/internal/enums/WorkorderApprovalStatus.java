package com.positivity.supplier.internal.enums;

/**
 * Where the vendor-side completion sign-off has got to (CAP-323 #1229).
 *
 * <p>Tracked apart from {@link WorkorderAuthorizationStatus} because the two fail independently.
 * Work that was authorized months ago can still fail to be approved on completion, and a single
 * status column would make that job look unauthorized — which is both wrong and the kind of wrong
 * that gets a shop paid late.
 */
public enum WorkorderApprovalStatus {

    /** The workorder has not completed yet, so nothing has been sent. */
    NOT_REQUESTED,

    /** Completion approval has been attempted and has not yet succeeded. */
    PENDING,

    /** The vendor accepted the completion. */
    APPROVED,

    /**
     * Approval failed in a way retrying will not fix, and money is likely owed.
     *
     * <p>This is the state that must be visible to somebody. An unapproved completion is work
     * already performed that the fleet has not agreed to pay for, and unlike a failed fetch it
     * cannot be recovered by asking again later.
     */
    MANUAL_REVIEW;

    /** Whether the approver should keep trying. */
    public boolean isOutstanding() {
        return this == PENDING;
    }
}

package com.positivity.workorder.internal.enums;

/**
 * What an advisor decided to do about a fleet authorization that did not come back granted (#1346).
 *
 * <h2>Recorded because none of it is automatic</h2>
 *
 * The domain ruled that a refusal leaves the workorder open: it is not cancelled, and it is not
 * converted to customer-pay. Somebody chooses, and the choice is theirs to answer for — so it is
 * stored with who made it, when, and why, rather than inferred from the workorder's later shape.
 *
 * <p>Selecting a different payer is deliberately absent. That needs a payer model this domain does
 * not have yet, and inventing one here would decide a commercial question inside a start gate.
 */
public enum FleetAuthorizationResolution {

    /**
     * The work was changed so the fleet will cover it, and authorization will be sought again.
     *
     * <p>Recorded on the authorization rather than only on the workorder, so a second refusal after
     * a revision is legible as a second refusal rather than as the first one repeating.
     */
    SCOPE_REVISED,

    /** The advisor stopped pursuing fleet payment for this workorder. */
    CANCELLED
}

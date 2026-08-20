package com.positivity.workorder.internal.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A part cannot be issued because owned stock at the servicing site does not cover the quantity
 * (CAP #1315).
 *
 * <p>Deliberately blocks the issue rather than letting it through as a backorder, which is where
 * the work-order gate parts company with the sales-order one. A sale short of stock is still a
 * sale — the customer pays and waits. A part short of stock cannot be handed to a technician: the
 * job physically cannot proceed on that item, so recording an issue would assert that metal moved
 * when it did not, and every downstream quantity would inherit the lie.
 *
 * <p>Surfaced as 409 CONFLICT with a {@code nextAction} rather than a bare {@code
 * IllegalStateException}, so the advisor is told what to do instead of hitting a dead end.
 */
public class InsufficientPartAvailabilityException extends RuntimeException {

    public static final String NEXT_ACTION =
            "Order or transfer the part, or substitute an in-stock equivalent, then retry the issue.";

    private final transient UUID partLineId;

    public InsufficientPartAvailabilityException(
            UUID partLineId, String itemLabel, BigDecimal requested, BigDecimal available) {
        super("Part " + itemLabel + " on line " + partLineId + " cannot be issued: requested "
                + requested.toPlainString()
                + " but only " + available.toPlainString() + " available at the servicing site");
        this.partLineId = partLineId;
    }

    public UUID getPartLineId() {
        return partLineId;
    }
}

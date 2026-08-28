package com.positivity.supplier.internal.order.service.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * An operator's resolution of a transmission stuck in {@code MANUAL_REVIEW} (ADR-0052 §4).
 *
 * <h2>Evidence is mandatory, and that is not bureaucracy</h2>
 *
 * Each of these actions asserts something about the physical world that this system cannot
 * verify: that a vendor accepted an order, or that it never received one. Weeks later, when a
 * delivery arrives that nobody expected or fails to arrive that everybody did, the only record of
 * why the system believes what it believes is what the operator wrote here. So it is required,
 * stored, and published on the resulting event alongside the actor's name.
 *
 * <h2>There is no re-send action</h2>
 *
 * Deliberately absent. {@link Action#MARK_NOT_RECEIVED} terminates the intent, and ordering the
 * goods again is a fresh command from the ordering domain, which mints a new intent and a new
 * document id. Offering "send it again" here would be offering the one operation ADR-0052 exists
 * to prevent, at the exact moment an operator is most likely to want it.
 *
 * @param action which resolution to apply
 * @param evidence the operator's account of what they established and how; required
 * @param supplierOrderNumber the vendor's order reference, required for
 *     {@link Action#CONFIRM_WITH_VENDOR_REFERENCE} — a confirmation without one is an assertion
 *     with nothing behind it
 */
public record TransmissionResolutionRequest(
        @NotNull Action action,
        @NotBlank @Size(max = 2000) String evidence,
        @Nullable @Size(max = 64) String supplierOrderNumber) {

    /** The three resolutions ADR-0052 §4 defines. */
    public enum Action {
        /**
         * The operator confirmed with the vendor that it accepted the order, recording the vendor's
         * order reference as evidence. The transmission becomes {@code CONFIRMED} and fulfilment
         * polling begins, exactly as if the vendor had answered.
         */
        CONFIRM_WITH_VENDOR_REFERENCE,
        /**
         * The operator confirmed the vendor has no such order. The intent terminates as
         * {@code FAILED} and is never re-dispatched; re-ordering requires a new intent with a new
         * document id.
         */
        MARK_NOT_RECEIVED,
        /** The operator abandons the transmission; the intent terminates as {@code CANCELLED}. */
        CANCEL
    }
}

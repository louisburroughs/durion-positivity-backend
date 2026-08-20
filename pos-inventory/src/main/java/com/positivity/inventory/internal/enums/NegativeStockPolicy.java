package com.positivity.inventory.internal.enums;

import org.jspecify.annotations.NonNull;

/**
 * Per-event-type negative-stock policy matrix (odoo-parity K1, issue #1027).
 *
 * <p>Classifies every {@link InventoryLedgerEventType} by what happens when a
 * posting would take the {@code (stockItemId, locationId)} on-hand balance
 * below zero. The matrix is enforced exclusively inside the single ledger
 * posting funnel ({@code LedgerPostingServiceImpl}); a DB-level check is
 * impractical against an append-only ledger.
 *
 * <p>See {@code pos-inventory/docs/negative-stock-policy.md} for the full
 * matrix table with per-row rationale.
 */
public enum NegativeStockPolicy {

    /**
     * Posting below zero is always rejected ({@code InsufficientStockException},
     * {@code INSUFFICIENT_STOCK}). Physical outbound flows cannot take more
     * stock than exists.
     */
    BLOCKED,

    /**
     * Posting below zero is rejected unless the caller passes an explicit
     * negative-stock override flag after its own
     * {@code inventory:adjustment:override} permission check
     * ({@code NEGATIVE_STOCK_OVERRIDE_REQUIRED} otherwise).
     */
    BLOCKED_OVERRIDABLE,

    /**
     * Posting may drive on-hand to exactly zero but never below
     * ({@code NEGATIVE_STOCK_FLOOR_VIOLATION}); counts set reality, so they
     * can zero stock but not invent negative stock. Not overridable.
     *
     * <p><strong>FLOOR_AT_ZERO decision for bulk stock (ADR-0055 stage 4, #1416).</strong> Despite
     * its name, this is fail-loud, not a silent truncation: {@code
     * LedgerPostingServiceImpl.rejectNegativeProjection} <em>throws</em>
     * {@code NegativeStockPolicyViolationException} when the projected on-hand would go negative;
     * it never clamps the posted quantity to zero. So the risk the issue raised — "downward
     * variance truncates at zero silently" — does not describe this mechanism as written for
     * either {@code COUNT_VARIANCE_OUT} or {@code ADJUST_CYCLE_COUNT}.
     *
     * <p>The decision made here is to <strong>keep both {@code FLOOR_AT_ZERO}</strong> rather than
     * move them to {@code BLOCKED}, for a structural reason: a cycle-count adjustment posts a
     * <em>to-measured</em> change — {@code quantityAfter = currentOnHand + quantityChange} where
     * {@code quantityChange} is recomputed against current on-hand immediately before posting
     * ({@code CycleCountAdjustmentServiceImpl.postAdjustmentToLedger}) — and the measured quantity
     * that change is computed from is validated {@code >= 0} at every entry point ({@code
     * SubmitCountRequest.actualQuantity}, {@code CreateAdjustmentRequest.countedQuantity}). With no
     * interleaving posting between that recompute and the post itself (guaranteed by the funnel's
     * own lock scope), {@code quantityAfter} is algebraically equal to the non-negative measured
     * quantity, so the floor can never actually trigger on this path — it is structurally
     * unreachable, not merely untested. {@code ADJUST_CYCLE_COUNT} is additionally dead code: no
     * production path posts it (only {@code COUNT_VARIANCE_IN}/{@code COUNT_VARIANCE_OUT} are
     * posted by {@code CycleCountAdjustmentServiceImpl}).
     *
     * <p>{@code FLOOR_AT_ZERO} is kept — rather than relaxed to {@code UNCONSTRAINED} — as a
     * defense-in-depth invariant: if a future change ever posts a variance that is <em>not</em>
     * to-measured (e.g. an incremental adjustment rather than a recomputed-to-reality one), the
     * floor is exactly the guard that should catch it, fail loud, and refuse rather than silently
     * invent negative stock. Bulk shrinkage (evaporation, meter variance) is surfaced instead
     * through the tolerance mechanism (#1416) and the {@code ApprovalThresholdEvaluator} — not
     * through this policy, which was never the layer that decided whether a variance is worth
     * surfacing.
     */
    FLOOR_AT_ZERO,

    /**
     * No negative-stock constraint. Inbound flows and paired-location moves
     * (e.g. the PUTAWAY source decrement) post freely; neutral/ATP-only types
     * never touch on-hand and are equally unconstrained.
     */
    UNCONSTRAINED;

    /**
     * Returns the negative-stock policy for the given ledger event type.
     *
     * @param eventType the ledger event type being posted
     * @return the policy row of the matrix that applies to the event type
     */
    public static @NonNull NegativeStockPolicy forEventType(@NonNull InventoryLedgerEventType eventType) {
        return switch (eventType) {
            case GOODS_ISSUE, WORKORDER_CONSUMPTION, TRANSFER_OUT -> BLOCKED;
            case SCRAP_OUT -> BLOCKED_OVERRIDABLE;
            case ADJUSTMENT_OUT, COUNT_VARIANCE_OUT, ADJUST_CYCLE_COUNT -> FLOOR_AT_ZERO;
            default -> UNCONSTRAINED;
        };
    }
}

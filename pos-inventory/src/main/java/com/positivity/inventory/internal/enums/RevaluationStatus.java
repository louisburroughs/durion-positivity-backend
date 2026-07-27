package com.positivity.inventory.internal.enums;

/**
 * Status lifecycle for manual cost-revaluation documents (odoo-parity J4, issue #1054).
 *
 * <p>Mirrors the {@link ScrapStatus} lifecycle: a below-threshold revaluation auto-applies in the
 * create transaction; an above-threshold one waits for approval before the cost state is restated.
 *
 * <ul>
 * <li>PENDING_APPROVAL → APPLIED (on approval; cost state restated, fact emitted)</li>
 * <li>PENDING_APPROVAL → REJECTED (state untouched)</li>
 * <li>AUTO_APPLIED → (below-threshold; restated and fact emitted in the create transaction)</li>
 * </ul>
 */
public enum RevaluationStatus {

    /** Value delta exceeds a threshold; awaiting manual approval. No cost change yet. */
    PENDING_APPROVAL,

    /** Below all value thresholds; system-approved and applied in the create transaction. */
    AUTO_APPLIED,

    /** Manually approved and applied to the SKU cost state. Final successful state. */
    APPLIED,

    /** Rejected by an authorized user; cost state untouched. Final state. */
    REJECTED
}

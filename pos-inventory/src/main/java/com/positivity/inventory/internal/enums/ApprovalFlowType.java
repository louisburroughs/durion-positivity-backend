package com.positivity.inventory.internal.enums;

/**
 * Discriminates which approval workflow an {@code ApprovalThresholdConfig} row belongs to
 * (odoo-parity D1, issue #1030).
 *
 * <p>The config table was originally keyed by tier alone (cycle-count adjustments were its only
 * consumer). Scrap documents reuse the same tier mechanism with scrap-specific, value-based
 * thresholds, so each row now carries the flow it configures; uniqueness is
 * {@code (flowType, approvalTier)}.
 */
public enum ApprovalFlowType {

    /** Cycle-count adjustment approvals (unit / value / percentage thresholds, OR logic). */
    CYCLE_COUNT,

    /** Scrap/write-off approvals (value-based thresholds via cost snapshot). */
    SCRAP,

    /**
     * Manual cost-revaluation approvals (odoo-parity J4, issue #1054). Value-based thresholds via
     * the absolute inventory value delta (|Δunit-cost| × on-hand).
     */
    REVALUATION
}

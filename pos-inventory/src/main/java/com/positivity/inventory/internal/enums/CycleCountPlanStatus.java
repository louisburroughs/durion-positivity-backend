package com.positivity.inventory.internal.enums;

/**
 * Status lifecycle for cycle count plans.
 *
 * <p>
 * Common warehouse cycle-count workflows usually move from a planned schedule,
 * to active counting, then completion and optional supervisor approval.
 */
public enum CycleCountPlanStatus {
    /**
     * Plan is scheduled but counting has not started.
     */
    PLANNED,

    /**
     * Plan execution has started and counting is in progress.
     */
    STARTED,

    /**
     * Counting is complete and waiting for supervisor review/approval.
     */
    COMPLETED_PENDING_APPROVAL,

    /**
     * Plan has been reviewed and approved as final.
     */
    APPROVED,

    /**
     * Plan was reviewed and rejected.
     */
    REJECTED,

    /**
     * Plan was canceled before finalization.
     */
    CANCELLED
}

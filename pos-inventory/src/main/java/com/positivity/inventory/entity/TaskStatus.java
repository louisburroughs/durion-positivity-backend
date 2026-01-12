package com.positivity.inventory.entity;

/**
 * Enumeration of cycle count task statuses following the workflow defined in issue #27.
 * 
 * <p>Status flow:
 * <ul>
 *   <li>ASSIGNED → Auditor has been assigned the count task</li>
 *   <li>COUNTED_PENDING_REVIEW → Count submitted, awaiting manager review</li>
 *   <li>REQUIRES_INVESTIGATION → Max recounts exceeded or threshold breach</li>
 *   <li>APPROVED → Variance accepted, ready for inventory adjustment</li>
 *   <li>REJECTED → Count rejected, may require new task assignment</li>
 * </ul>
 */
public enum TaskStatus {
    /**
     * Task has been assigned to an auditor but not yet counted.
     */
    ASSIGNED,
    
    /**
     * Count has been submitted and is awaiting manager review.
     */
    COUNTED_PENDING_REVIEW,
    
    /**
     * Task requires investigation due to exceeding recount limits or high variance.
     * Requires manager sign-off and root-cause documentation.
     */
    REQUIRES_INVESTIGATION,
    
    /**
     * Count has been approved by manager. Ready for inventory adjustment.
     */
    APPROVED,
    
    /**
     * Count has been rejected. May require reassignment or further action.
     */
    REJECTED
}

package com.positivity.workorder.entity;

import java.util.Set;
import java.util.Map;
import java.util.HashMap;

public enum WorkOrderStatus {
    DRAFT,
    APPROVED,
    ASSIGNED,
    WORK_IN_PROGRESS,
    AWAITING_PARTS,
    AWAITING_APPROVAL,
    READY_FOR_PICKUP,
    COMPLETED,
    CANCELLED;

    private static final Map<WorkOrderStatus, Set<WorkOrderStatus>> ALLOWED_TRANSITIONS = new HashMap<>();

    static {
        ALLOWED_TRANSITIONS.put(DRAFT, Set.of(APPROVED, CANCELLED));
        ALLOWED_TRANSITIONS.put(APPROVED, Set.of(ASSIGNED, WORK_IN_PROGRESS, CANCELLED));
        ALLOWED_TRANSITIONS.put(ASSIGNED, Set.of(WORK_IN_PROGRESS, CANCELLED));
        ALLOWED_TRANSITIONS.put(WORK_IN_PROGRESS, Set.of(AWAITING_PARTS, AWAITING_APPROVAL, READY_FOR_PICKUP, COMPLETED, CANCELLED));
        ALLOWED_TRANSITIONS.put(AWAITING_PARTS, Set.of(WORK_IN_PROGRESS, CANCELLED));
        ALLOWED_TRANSITIONS.put(AWAITING_APPROVAL, Set.of(WORK_IN_PROGRESS, CANCELLED));
        ALLOWED_TRANSITIONS.put(READY_FOR_PICKUP, Set.of(COMPLETED, CANCELLED));
        ALLOWED_TRANSITIONS.put(COMPLETED, Set.of());
        ALLOWED_TRANSITIONS.put(CANCELLED, Set.of());
    }

    public boolean canTransitionTo(WorkOrderStatus newStatus) {
        Set<WorkOrderStatus> allowedTargets = ALLOWED_TRANSITIONS.get(this);
        return allowedTargets != null && allowedTargets.contains(newStatus);
    }

    public Set<WorkOrderStatus> getAllowedTransitions() {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of());
    }

    public static Set<WorkOrderStatus> getStartEligibleStatuses() {
        return Set.of(APPROVED, ASSIGNED);
    }

    public static Set<WorkOrderStatus> getInProgressSubStatuses() {
        return Set.of(WORK_IN_PROGRESS, AWAITING_PARTS, AWAITING_APPROVAL);
    }
}

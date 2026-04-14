package com.positivity.workorder.internal.enums;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public enum WorkorderStatus {
    DRAFT,
    APPROVED,
    ASSIGNED,
    WORK_IN_PROGRESS,
    AWAITING_PARTS,
    AWAITING_APPROVAL,
    READY_FOR_PICKUP,
    COMPLETED,
    CANCELLED;

    private static final Map<WorkorderStatus, Set<WorkorderStatus>> ALLOWED_TRANSITIONS = new HashMap<>();

    static {
        ALLOWED_TRANSITIONS.put(DRAFT, Set.of(APPROVED, CANCELLED));
        ALLOWED_TRANSITIONS.put(APPROVED, Set.of(ASSIGNED, WORK_IN_PROGRESS, AWAITING_APPROVAL, CANCELLED));
        ALLOWED_TRANSITIONS.put(ASSIGNED, Set.of(WORK_IN_PROGRESS, CANCELLED));
        ALLOWED_TRANSITIONS.put(
                WORK_IN_PROGRESS, Set.of(AWAITING_PARTS, AWAITING_APPROVAL, READY_FOR_PICKUP, COMPLETED, CANCELLED));
        ALLOWED_TRANSITIONS.put(AWAITING_PARTS, Set.of(WORK_IN_PROGRESS, COMPLETED, CANCELLED));
        ALLOWED_TRANSITIONS.put(AWAITING_APPROVAL, Set.of(WORK_IN_PROGRESS, COMPLETED, CANCELLED));
        ALLOWED_TRANSITIONS.put(READY_FOR_PICKUP, Set.of(COMPLETED, CANCELLED));
        ALLOWED_TRANSITIONS.put(COMPLETED, Set.of());
        ALLOWED_TRANSITIONS.put(CANCELLED, Set.of());
    }

    public boolean canTransitionTo(WorkorderStatus newStatus) {
        Set<WorkorderStatus> allowedTargets = ALLOWED_TRANSITIONS.get(this);
        return allowedTargets != null && allowedTargets.contains(newStatus);
    }

    public Set<WorkorderStatus> getAllowedTransitions() {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of());
    }

    public static Set<WorkorderStatus> getStartEligibleStatuses() {
        return Set.of(APPROVED, ASSIGNED);
    }

    public static Set<WorkorderStatus> getInProgressSubStatuses() {
        return Set.of(WORK_IN_PROGRESS, AWAITING_PARTS, AWAITING_APPROVAL);
    }
}

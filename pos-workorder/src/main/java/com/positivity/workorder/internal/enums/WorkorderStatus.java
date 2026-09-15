package com.positivity.workorder.internal.enums;

import java.util.EnumSet;
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
        // APPROVED no longer goes straight to WORK_IN_PROGRESS (#2011): work starts only once the
        // workorder has both a technician and a bay or mobile unit, which is what ASSIGNED means.
        ALLOWED_TRANSITIONS.put(APPROVED, Set.of(ASSIGNED, AWAITING_APPROVAL, CANCELLED));
        // ASSIGNED -> APPROVED is the reverse of that (#2010, #2011): releasing the technician, or
        // giving up the bay, leaves a workorder that is approved but no longer ready to be worked.
        ALLOWED_TRANSITIONS.put(ASSIGNED, Set.of(APPROVED, WORK_IN_PROGRESS, CANCELLED));
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

    /**
     * The statuses work may start from — {@link #ASSIGNED} alone since #2011.
     *
     * <p>{@code ASSIGNED} means the workorder has a current technician and a bay or mobile unit, so
     * requiring it is what makes "work starts when there is somebody to do it and somewhere to do
     * it" true on every path rather than only on the ones that happen to check.
     */
    public static Set<WorkorderStatus> getStartEligibleStatuses() {
        return Set.of(ASSIGNED);
    }

    public static Set<WorkorderStatus> getInProgressSubStatuses() {
        return Set.of(WORK_IN_PROGRESS, AWAITING_PARTS, AWAITING_APPROVAL);
    }

    /** Terminal statuses — a workorder in one of these is finished and no longer "open". */
    public static Set<WorkorderStatus> getTerminalStatuses() {
        return Set.of(COMPLETED, CANCELLED);
    }

    /**
     * "Open" workorders — every non-terminal status. Derived as the complement of
     * {@link #getTerminalStatuses()} so it stays correct if a new status is added.
     */
    public static Set<WorkorderStatus> getOpenStatuses() {
        return EnumSet.complementOf(EnumSet.copyOf(getTerminalStatuses()));
    }
}

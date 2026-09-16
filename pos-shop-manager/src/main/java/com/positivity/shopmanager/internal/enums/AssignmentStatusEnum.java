package com.positivity.shopmanager.internal.enums;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Assignment lifecycle per DECISION-SHOPMGMT-010 (CAP-326, durion#483).
 *
 * <p>Six members, one copy: the four-member twin that lived at {@code
 * internal.service.enums.AssignmentStatus} is gone, and {@code CONFIRMED} became {@link #ASSIGNED}
 * — the record's name for the same state. {@link #AWAITING_SKILL_FULFILLMENT} is the parked state a
 * booking lands in when no competent candidate exists at the location (D10.1,
 * {@code NO_COMPETENT_MECHANIC_ROSTERED}): it resolves only by re-assignment or cancellation, never
 * straight into work.
 *
 * <p>{@link #canTransitionTo} is the record's transition table verbatim; {@link #COMPLETED} and
 * {@link #CANCELLED} are terminal.
 */
public enum AssignmentStatusEnum {
    UNASSIGNED,
    ASSIGNED,
    AWAITING_SKILL_FULFILLMENT,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    private static final Map<AssignmentStatusEnum, Set<AssignmentStatusEnum>> ALLOWED_TRANSITIONS = Map.of(
            UNASSIGNED, EnumSet.of(ASSIGNED, CANCELLED),
            ASSIGNED, EnumSet.of(AWAITING_SKILL_FULFILLMENT, IN_PROGRESS, CANCELLED),
            AWAITING_SKILL_FULFILLMENT, EnumSet.of(ASSIGNED, CANCELLED),
            IN_PROGRESS, EnumSet.of(COMPLETED, CANCELLED),
            COMPLETED, EnumSet.noneOf(AssignmentStatusEnum.class),
            CANCELLED, EnumSet.noneOf(AssignmentStatusEnum.class));

    /** Whether DECISION-SHOPMGMT-010 permits moving from this state to {@code target}. */
    public boolean canTransitionTo(@NonNull AssignmentStatusEnum target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** {@code true} for the two states nothing follows. */
    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /**
     * The states that occupy the appointment's single active-assignment slot. A parked
     * ({@link #AWAITING_SKILL_FULFILLMENT}) assignment still holds it: the booking exists and is
     * waiting on staff, not on a second assignment.
     */
    public static @NonNull List<AssignmentStatusEnum> active() {
        return List.of(ASSIGNED, AWAITING_SKILL_FULFILLMENT, IN_PROGRESS);
    }
}

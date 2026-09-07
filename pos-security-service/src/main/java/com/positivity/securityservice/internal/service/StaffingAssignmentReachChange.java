package com.positivity.securityservice.internal.service;

import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.securityservice.internal.entity.ExtStaffingAssignmentReplica;
import java.time.LocalDate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Decides whether a {@code people.staffing-assignment.updated} fact <em>narrows</em> the reach a
 * person's live access tokens may already carry (ADR-0061 §4, #1874).
 *
 * <p>Reach only narrows when the assignment was contributing to the person's assigned-node set
 * today — {@code ACTIVE} and effective on {@code today} by the same predicate
 * {@code ExtStaffingAssignmentReplicaRepository.findActiveEffectiveOn} uses — and the incoming fact
 * stops or shrinks that contribution:
 *
 * <ul>
 *   <li>{@code status} leaves {@code ACTIVE} (typically {@code ENDED});
 *   <li>{@code locationId} changes — the old node is no longer covered by this assignment;
 *   <li>{@code effectiveFrom} moves after {@code today} (or is dropped, which the projection
 *       query treats as never effective);
 *   <li>{@code effectiveTo} is set where it was open-ended, or moves earlier than it was.
 * </ul>
 *
 * <p>Everything else is widening or neutral and must not revoke: a brand-new assignment (no
 * prior replica row — nothing was issued from it), a reactivation, a later or removed
 * {@code effectiveTo}, an earlier {@code effectiveFrom}, or a change to {@code primary}. The next
 * token simply picks those up. Pure and deterministic: {@code today} is supplied by the caller
 * from the issuer clock.
 */
final class StaffingAssignmentReachChange {

    private StaffingAssignmentReachChange() {}

    static boolean narrows(
            @Nullable ExtStaffingAssignmentReplica existing,
            @NonNull StaffingAssignmentUpdatedV1 incoming,
            @NonNull LocalDate today) {
        if (existing == null || !contributesOn(existing, today)) {
            return false;
        }
        if (!ExtStaffingAssignmentReplica.STATUS_ACTIVE.equals(incoming.status())) {
            return true;
        }
        if (!existing.getLocationId().equals(incoming.locationId())) {
            return true;
        }
        LocalDate from = incoming.effectiveFrom();
        if (from == null || from.isAfter(today)) {
            return true;
        }
        LocalDate to = incoming.effectiveTo();
        return to != null && (existing.getEffectiveTo() == null || to.isBefore(existing.getEffectiveTo()));
    }

    /** Mirrors {@code findActiveEffectiveOn}: a null {@code effectiveFrom} never contributes. */
    private static boolean contributesOn(ExtStaffingAssignmentReplica replica, LocalDate today) {
        LocalDate from = replica.getEffectiveFrom();
        LocalDate to = replica.getEffectiveTo();
        return ExtStaffingAssignmentReplica.STATUS_ACTIVE.equals(replica.getStatus())
                && from != null
                && !from.isAfter(today)
                && (to == null || !to.isBefore(today));
    }
}

package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Who may act on, and who may see, a person's work session (issue #2061, BR4; #85 OQ2).
 *
 * <p>The rule, from the story's owner: only the user linked to the person, or a supervisor /
 * manager, may clock a person in or out or read their clock state. "Supervisor" is a permission,
 * not a role: {@link PeoplePermissions#TIMEKEEPING_APPROVE} for the mutations (start, stop, break
 * control, submit) and {@link PeoplePermissions#TIMEKEEPING_VIEW} for the reads. A person reads
 * their own state with {@link PeoplePermissions#SELF_VIEW} alone and manages it with nothing more
 * than authentication, as before.
 *
 * <h2>Location scope (ADR-0061)</h2>
 *
 * A supervisory permission that is location-scoped for the caller must cover the person: one of
 * the person's ACTIVE staffing assignments today lies within the caller's reach. A person with no
 * assignment is out of every scoped caller's reach (fail closed). A caller whose grant is global,
 * or whose token predates the scope claims, is unaffected — that is the "not activated" case,
 * and it needs no code path of its own because {@link LocationScope#reach} is empty for it. The
 * gate runs after the existence check so a 404 precedes a 403 and ids cannot be probed.
 *
 * <p>The self check resolves the caller's person through the user-link replica (ADR-0044 §6);
 * an unlinked caller is never "self".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkSessionAccessPolicy {

    private final UserPersonTranslationService userPersonTranslationService;
    private final EmployeeLocationAssignmentRepository assignmentRepository;
    private final Clock clock;

    /**
     * The caller's own person, or empty when the caller is unauthenticated, unlinked, or linked
     * only by a link that is no longer ACTIVE.
     */
    public @NonNull Optional<UUID> callerPersonId() {
        String username;
        try {
            username = SecurityContextHelper.getCurrentUsername().orElse(null);
        } catch (IllegalStateException ex) {
            username = null;
        }
        if (username == null) {
            return Optional.empty();
        }
        // ACTIVE-only and non-throwing, both on purpose: an INACTIVE link is a historical
        // association that must not still grant the self-service path, and an exception thrown
        // through the translation service's own transactional boundary would mark the caller's
        // transaction rollback-only even when caught here.
        return userPersonTranslationService.findActivePersonUuidForUser(username);
    }

    /**
     * The caller may start, stop, break or submit this person's work session: it is their own,
     * or they hold {@code people:timekeeping:approve} covering the person's location.
     *
     * @throws AccessDeniedException when neither applies
     * @throws LocationScopeDeniedException when the supervisory grant is scoped and does not
     *     cover the person
     */
    public void requireMayManage(@NonNull UUID personId) {
        if (isSelf(personId)) {
            return;
        }
        if (!SecurityContextHelper.hasAuthority(PeoplePermissions.TIMEKEEPING_APPROVE)) {
            throw new AccessDeniedException("Only the person themself or a caller holding "
                    + PeoplePermissions.TIMEKEEPING_APPROVE + " may manage this work session");
        }
        requireInReach(PeoplePermissions.TIMEKEEPING_APPROVE, personId);
    }

    /**
     * The caller may read this person's current work session: it is their own and they hold
     * {@code people:self:view}, or they hold {@code people:timekeeping:view} covering the
     * person's location.
     *
     * @throws AccessDeniedException when neither applies
     * @throws LocationScopeDeniedException when the supervisory grant is scoped and does not
     *     cover the person
     */
    public void requireMayView(@NonNull UUID personId) {
        if (isSelf(personId) && SecurityContextHelper.hasAuthority(PeoplePermissions.SELF_VIEW)) {
            return;
        }
        if (!SecurityContextHelper.hasAuthority(PeoplePermissions.TIMEKEEPING_VIEW)) {
            throw new AccessDeniedException("Only the person themself (with " + PeoplePermissions.SELF_VIEW
                    + ") or a caller holding " + PeoplePermissions.TIMEKEEPING_VIEW
                    + " may read this work session");
        }
        requireInReach(PeoplePermissions.TIMEKEEPING_VIEW, personId);
    }

    /**
     * The per-request inputs to {@link #mayViewClockState} on the availability list, resolved
     * once so a page of N people costs one person-link lookup, not N.
     */
    public @NonNull ClockStateViewer clockStateViewer() {
        return new ClockStateViewer(callerPersonId().orElse(null), SecurityContextHelper.locationScope());
    }

    /** The caller of one availability request: their linked person (if any) and their location scope. */
    public record ClockStateViewer(
            @Nullable UUID callerPersonId, @NonNull LocationScope scope) {}

    /**
     * Visibility of clock state on the availability list (#2061 OQ1): the caller's own row is
     * always visible; every other row needs {@code people:timekeeping:view} covering the row's
     * location. Rows the caller may not see carry null clock fields rather than failing the
     * request, so a manager without timekeeping rights still gets a usable board.
     */
    public boolean mayViewClockState(
            @NonNull ClockStateViewer viewer, @NonNull UUID personId, @NonNull UUID locationId) {
        if (personId.equals(viewer.callerPersonId())) {
            return true;
        }
        // The authority check precedes the scope decision on purpose (#1890): a caller holding
        // no timekeeping grant takes no location decision at all.
        if (!SecurityContextHelper.hasAuthority(PeoplePermissions.TIMEKEEPING_VIEW)) {
            return false;
        }
        LocationScope scope = viewer.scope();
        return scope.covers(PeoplePermissions.TIMEKEEPING_VIEW, locationId);
    }

    private boolean isSelf(UUID personId) {
        return callerPersonId().map(personId::equals).orElse(false);
    }

    /**
     * A location-scoped supervisory grant must cover one of the person's active assignment
     * locations today. Unscoped grants and pre-rollout tokens pass without a lookup.
     */
    private void requireInReach(String permission, UUID personId) {
        LocationScope scope = SecurityContextHelper.locationScope();
        if (scope.reach(permission).isEmpty()) {
            return;
        }
        List<UUID> locations = assignmentRepository.findActiveByPersonIdAndDate(personId, LocalDate.now(clock)).stream()
                .map(EmployeeLocationAssignment::getLocationId)
                .distinct()
                .toList();
        for (UUID locationId : locations) {
            if (scope.covers(permission, locationId)) {
                return;
            }
        }
        log.debug(
                "Caller's {} is location-scoped and covers none of the {} active assignment location(s) of person {}",
                permission,
                locations.size(),
                personId);
        throw new LocationScopeDeniedException(
                permission,
                locations.isEmpty() ? "unassigned" : locations.getFirst().toString());
    }
}

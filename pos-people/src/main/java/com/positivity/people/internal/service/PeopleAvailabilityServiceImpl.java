package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.PeopleAvailabilityResponse;
import com.positivity.people.internal.dto.PrimaryLocationResolution;
import com.positivity.people.internal.dto.WorkSessionClockStateResponse;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.ExtLocationReplica;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.ExtLocationReplicaRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.SecurityContextHelper;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Availability and primary-location projections over the staffing assignments.
 *
 * <h2>Location scope on the availability list (ADR-0061 §3, #1872)</h2>
 *
 * {@code @PreAuthorize} on the controller answers "may this caller view availability"; the
 * caller's {@link LocationScope} on {@code people:availability:view} answers "…where".
 * {@link #getPeopleAvailability} takes {@code locationId} as an optional filter that defaults to
 * the requester's own assignment, so it has two shapes:
 *
 * <ul>
 * <li><b>Named location — gate.</b> The caller must cover it, or the request is a 403
 * {@code LOCATION_SCOPE_DENIED}.</li>
 * <li><b>No location — narrow.</b> The filter is the requester's own location; a scoped caller
 * is not denied, the list is the intersection of that one location with their reach — the
 * rows when it is covered, an empty list when it is not. (An assignment feeds the token's
 * scope claims, so the two normally agree; the check keeps a stale token from widening.)</li>
 * </ul>
 *
 * A caller whose permission is global, or whose token predates the scope claims, sees the
 * list exactly as before.
 *
 * <h2>Clock state on the availability list (#2061)</h2>
 *
 * Each row also carries the person's current work-session state, resolved for the whole page by
 * {@link WorkSessionService#resolveClockStates} in a bounded number of queries (BR7). Visibility
 * is per row, decided by {@link WorkSessionAccessPolicy#mayViewClockState}: the caller's own row
 * always, every other row only under {@code people:timekeeping:view} covering the location
 * (OQ1). Rows the caller may not see keep null clock fields rather than failing the request.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PeopleAvailabilityServiceImpl implements PeopleAvailabilityService {

    private final EmployeeLocationAssignmentRepository assignmentRepository;

    private final ExtPersonReplicaRepository extPersonReplicaRepository;

    private final UserPersonTranslationService userPersonTranslationService;

    private final Clock clock;

    private final ExtLocationReplicaRepository extLocationReplicaRepository;

    private final LocationReferenceService locationReferenceService;

    private final WorkSessionService workSessionService;

    private final WorkSessionAccessPolicy workSessionAccessPolicy;

    @Override
    @NonNull
    public List<PeopleAvailabilityResponse> getPeopleAvailability(UUID locationId, LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        LocationScope scope = SecurityContextHelper.locationScope();
        UUID resolvedLocationId;
        if (locationId != null) {
            // Gate: a named location must be within the caller's reach, or this is a 403.
            scope.require(PeoplePermissions.AVAILABILITY_VIEW, locationId);
            resolvedLocationId = locationId;
        } else {
            // Narrow: the defaulted location is the requester's own; outside a scoped caller's
            // reach it yields an empty list rather than a refusal (unscoped callers unchanged).
            resolvedLocationId = resolveRequesterLocationId(targetDate);
            if (!scope.covers(PeoplePermissions.AVAILABILITY_VIEW, resolvedLocationId)) {
                return List.of();
            }
        }

        List<EmployeeLocationAssignment> assignments =
                assignmentRepository.findActiveByDateAndOptionalLocation(targetDate, resolvedLocationId);

        Map<UUID, ExtPersonReplica> peopleById = extPersonReplicaRepository
                .findAllById(assignments.stream()
                        .map(EmployeeLocationAssignment::getPersonId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ExtPersonReplica::getPersonId, person -> person));

        Map<UUID, WorkSessionClockStateResponse> clockStates = resolveVisibleClockStates(assignments);

        return assignments.stream()
                .map(assignment -> {
                    ExtPersonReplica person = peopleById.get(assignment.getPersonId());
                    WorkSessionClockStateResponse clockState = clockStates.get(assignment.getPersonId());
                    return PeopleAvailabilityResponse.builder()
                            .personId(assignment.getPersonId())
                            .firstName(person != null ? person.getFirstName() : null)
                            .lastName(person != null ? person.getLastName() : null)
                            .locationId(assignment.getLocationId())
                            .role(assignment.getRole())
                            .primary(assignment.isPrimary())
                            .assignmentStatus(assignment.getStatus())
                            .effectiveFrom(assignment.getEffectiveFrom())
                            .effectiveTo(assignment.getEffectiveTo())
                            .availableOn(targetDate)
                            .clockState(clockState == null ? null : clockState.getClockState())
                            .workSessionId(clockState == null ? null : clockState.getWorkSessionId())
                            .clockedInAt(clockState == null ? null : clockState.getClockedInAt())
                            .breakStartedAt(clockState == null ? null : clockState.getBreakStartedAt())
                            .build();
                })
                .toList();
    }

    /**
     * Clock state for exactly the rows the caller may see, fetched in one batched call — or no
     * call at all when they may see none (#2061 AC4, OQ1).
     */
    @NonNull
    private Map<UUID, WorkSessionClockStateResponse> resolveVisibleClockStates(
            @NonNull List<EmployeeLocationAssignment> assignments) {
        if (assignments.isEmpty()) {
            return Map.of();
        }
        WorkSessionAccessPolicy.ClockStateViewer viewer = workSessionAccessPolicy.clockStateViewer();
        Set<UUID> visiblePersonIds = assignments.stream()
                .filter(assignment -> workSessionAccessPolicy.mayViewClockState(
                        viewer, assignment.getPersonId(), assignment.getLocationId()))
                .map(EmployeeLocationAssignment::getPersonId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (visiblePersonIds.isEmpty()) {
            return Map.of();
        }
        return workSessionService.resolveClockStates(visiblePersonIds);
    }

    @Override
    @NonNull
    public PrimaryLocationResolution resolveCurrentUserPrimaryLocation() {
        LocalDate targetDate = LocalDate.now(clock);
        String username = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new EntityNotFoundException("Authenticated user context is missing"));

        Optional<UUID> primaryLocationId =
                tryResolvePersonId(username).flatMap(personId -> findPrimaryLocationId(personId, targetDate));
        if (primaryLocationId.isPresent()) {
            UUID locationId = primaryLocationId.get();
            return new PrimaryLocationResolution(
                    locationId,
                    locationReferenceService.findLocationName(locationId).orElse(null),
                    false);
        }

        return resolveTopLevelLocationId()
                .map(topLevelId -> new PrimaryLocationResolution(
                        topLevelId,
                        locationReferenceService.findLocationName(topLevelId).orElse(null),
                        true))
                .orElseThrow(() -> new EntityNotFoundException("No primary location assignment exists for requester on "
                        + targetDate + " and no top-level default location is available"));
    }

    /**
     * Person-link resolution that treats a missing link as "no assignment" (so the
     * top-level default can apply) instead of an error. Issue: #1636.
     */
    @NonNull
    private Optional<UUID> tryResolvePersonId(@NonNull String username) {
        try {
            return Optional.of(userPersonTranslationService.getPersonUuidForUser(username));
        } catch (EntityNotFoundException ex) {
            return Optional.empty();
        }
    }

    /**
     * Resolves the platform's top-level default location from the event-fed {@code ext_location}
     * / {@code ext_location_parent} replicas (ADR-0044 §6): the active hierarchy root (a parent
     * that is no location's child), else the oldest active location (UUID v7 order). Mirrors
     * pos-location's own {@code GET /v1/locations/top-level} semantics. Issue: #1636.
     */
    @NonNull
    private Optional<UUID> resolveTopLevelLocationId() {
        return extLocationReplicaRepository.findActiveHierarchyRoots().stream()
                .findFirst()
                .or(extLocationReplicaRepository::findFirstByActiveTrueOrderByLocationIdAsc)
                .map(ExtLocationReplica::getLocationId);
    }

    @Override
    @NonNull
    public PrimaryLocationResolution resolvePrimaryLocationId(@NonNull UUID personId) {
        LocalDate targetDate = LocalDate.now(clock);
        UUID locationId = findPrimaryLocationId(personId, targetDate)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No primary location assignment exists for person " + personId + " on " + targetDate));
        return new PrimaryLocationResolution(
                locationId,
                locationReferenceService.findLocationName(locationId).orElse(null),
                false);
    }

    @NonNull
    private Optional<UUID> findPrimaryLocationId(@NonNull UUID personId, @NonNull LocalDate targetDate) {
        return assignmentRepository.findActiveByPersonIdAndDate(personId, targetDate).stream()
                .filter(EmployeeLocationAssignment::isPrimary)
                .findFirst()
                .map(EmployeeLocationAssignment::getLocationId);
    }

    @NonNull
    private UUID resolveRequesterLocationId(@NonNull LocalDate targetDate) {
        return assignmentRepository.findActiveByPersonIdAndDate(resolveRequesterPersonId(), targetDate).stream()
                .findFirst()
                .map(EmployeeLocationAssignment::getLocationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "locationId was not provided and no active location assignment exists for requester on "
                                + targetDate));
    }

    @NonNull
    private UUID resolveRequesterPersonId() {
        String username = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new EntityNotFoundException("Authenticated user context is missing"));
        return userPersonTranslationService.getPersonUuidForUser(username);
    }
}

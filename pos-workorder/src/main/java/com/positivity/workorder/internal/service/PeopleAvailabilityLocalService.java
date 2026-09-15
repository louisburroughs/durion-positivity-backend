package com.positivity.workorder.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse;
import com.positivity.workorder.internal.entity.ExtPersonReplica;
import com.positivity.workorder.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.workorder.internal.repository.ExtPersonReplicaRepository;
import com.positivity.workorder.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.workorder.internal.repository.ExtUserLinkReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Local mechanic availability + primary-location resolution over the staffing replicas
 * (ADR-0044 §6, #877). Replaces the retired {@code PeopleAvailabilityClient} and
 * {@code PeopleLocationClient}: availability = ACTIVE assignments effective on the date at the
 * location joined with person names — exactly what the retired endpoint computed. Real-time
 * fields (clock/break/PTO/schedule) were never populated by that endpoint and remain null.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeopleAvailabilityLocalService {

    private static final String ACTIVE = "ACTIVE";

    private final Clock clock;
    private final ExtStaffingAssignmentReplicaRepository assignmentReplicaRepository;
    private final ExtPersonReplicaRepository personReplicaRepository;
    private final ExtUserLinkReplicaRepository linkReplicaRepository;

    @NonNull
    public PeopleAvailabilityResponse fetchAvailability(@NonNull String locationId, @NonNull LocalDate date) {
        UUID locationUuid = UUID.fromString(locationId);
        List<ExtStaffingAssignmentReplica> assignments =
                assignmentReplicaRepository.findByLocationIdAndStatus(locationUuid, ACTIVE).stream()
                        .filter(a -> effectiveOn(a, date))
                        .toList();

        Map<UUID, ExtPersonReplica> peopleById = personReplicaRepository
                .findByPersonIdIn(assignments.stream()
                        .map(ExtStaffingAssignmentReplica::getPersonId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ExtPersonReplica::getPersonId, p -> p));

        List<PeopleAvailabilityResponse.PersonAvailability> people = assignments.stream()
                .map(a -> {
                    ExtPersonReplica person = peopleById.get(a.getPersonId());
                    return PeopleAvailabilityResponse.PersonAvailability.builder()
                            .personId(a.getPersonId().toString())
                            .firstName(person != null ? person.getFirstName() : null)
                            .lastName(person != null ? person.getLastName() : null)
                            .currentLocationId(a.getLocationId().toString())
                            .build();
                })
                .toList();

        return PeopleAvailabilityResponse.builder()
                .asOf(Instant.now(clock))
                .location(locationId)
                .people(people)
                .build();
    }

    /** The current user's primary location via the link + assignment replicas. */
    @NonNull
    public Optional<UUID> resolveCurrentUserPrimaryLocation() {
        String username = SecurityContextHelper.getCurrentUsername().orElse(null);
        if (username == null) {
            return Optional.empty();
        }
        return linkReplicaRepository
                .findFirstByUsernameAndStatus(username, ACTIVE)
                .flatMap(link ->
                        assignmentReplicaRepository
                                .findByPersonIdAndStatusAndPrimaryTrue(link.getPersonId(), ACTIVE)
                                .stream()
                                .filter(a -> effectiveOn(a, LocalDate.now(clock)))
                                .findFirst()
                                .map(ExtStaffingAssignmentReplica::getLocationId));
    }

    /**
     * Whether {@code personId} may hold a workorder at {@code siteId} on {@code date} (#1990).
     *
     * <p>True unless the person has one or more ACTIVE staffing rows effective on {@code date} and
     * none of them is at {@code siteId}. A person with no active staffing at all answers {@code
     * true}: replica lag, bootstrap and a stalled DLQ must not take a shop offline — that is the
     * only softening, there is no staleness threshold. {@code is_primary} is not considered and
     * role is not filtered on, for the same reasons {@link #fetchAvailability} does not: role is
     * free text, and filtering on it would refuse a real technician over a typo. This is the exact
     * inverse of {@link #fetchAvailability}'s site roster — same predicate (ACTIVE, effective
     * today, no primary filter), filtered by person instead of by location — so the two must never
     * disagree, and both go through the same {@link #effectiveOn} definition to guarantee it.
     *
     * @param personId the technician being checked
     * @param siteId   the workorder's site — the unit's base site when the position is a mobile
     *                 unit, the workorder's own {@code locationId} otherwise
     * @param date     the date staffing must be effective on, normally today
     * @return whether {@code personId} is eligible to hold a workorder at {@code siteId}
     */
    public boolean isEligibleAtSite(@NonNull UUID personId, @NonNull UUID siteId, @NonNull LocalDate date) {
        List<ExtStaffingAssignmentReplica> activeAssignments =
                assignmentReplicaRepository.findByPersonIdAndStatus(personId, ACTIVE).stream()
                        .filter(a -> effectiveOn(a, date))
                        .toList();
        return activeAssignments.isEmpty()
                || activeAssignments.stream().anyMatch(a -> siteId.equals(a.getLocationId()));
    }

    private boolean effectiveOn(ExtStaffingAssignmentReplica assignment, LocalDate date) {
        boolean started = assignment.getEffectiveFrom() == null
                || !assignment.getEffectiveFrom().isAfter(date);
        boolean notEnded = assignment.getEffectiveTo() == null
                || !assignment.getEffectiveTo().isBefore(date);
        return started && notEnded;
    }
}

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

    private boolean effectiveOn(ExtStaffingAssignmentReplica assignment, LocalDate date) {
        boolean started = assignment.getEffectiveFrom() == null
                || !assignment.getEffectiveFrom().isAfter(date);
        boolean notEnded = assignment.getEffectiveTo() == null
                || !assignment.getEffectiveTo().isBefore(date);
        return started && notEnded;
    }
}

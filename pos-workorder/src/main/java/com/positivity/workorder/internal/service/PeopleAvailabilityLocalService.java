package com.positivity.workorder.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse;
import com.positivity.workorder.internal.entity.ExtEmployeeReplica;
import com.positivity.workorder.internal.entity.ExtPersonCredentialReplica;
import com.positivity.workorder.internal.entity.ExtPersonReplica;
import com.positivity.workorder.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.workorder.internal.repository.ExtEmployeeReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPersonCredentialReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPersonReplicaRepository;
import com.positivity.workorder.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.workorder.internal.repository.ExtUserLinkReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Local mechanic availability + primary-location resolution over the staffing replicas
 * (ADR-0044 §6, #877). Replaces the retired {@code PeopleAvailabilityClient} and
 * {@code PeopleLocationClient}: availability = ACTIVE assignments effective on the date at the
 * location joined with person names — exactly what the retired endpoint computed. Real-time
 * fields (clock/break/PTO/schedule) were never populated by that endpoint and remain null.
 *
 * <p>Employment (#2119, #2120): a person whose latest {@code ext_people_employee} status is
 * inactive ({@link ExtEmployeeReplica#INACTIVE_EMPLOYMENT_STATUSES}) is off the roster and not
 * eligible anywhere. A person with no employee row is treated as employed — replica lag must not
 * take a shop offline.
 *
 * <p>Certifications (#2122): each person's {@code certifications} are the codes of the credentials
 * they hold on the availability date, from the {@code ext_person_credential} replica. A person with
 * no credential rows at all gets {@code null} — "no data", which the dispatch board treats as
 * silence — while a person with rows but none held gets an empty list, "holds none".
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
    private final ExtEmployeeReplicaRepository employeeReplicaRepository;
    private final ExtPersonCredentialReplicaRepository credentialReplicaRepository;

    @NonNull
    public PeopleAvailabilityResponse fetchAvailability(@NonNull String locationId, @NonNull LocalDate date) {
        UUID locationUuid = UUID.fromString(locationId);
        List<ExtStaffingAssignmentReplica> effective =
                assignmentReplicaRepository.findByLocationIdAndStatus(locationUuid, ACTIVE).stream()
                        .filter(a -> effectiveOn(a, date))
                        .toList();
        Set<UUID> offboarded = inactivePersonIds(effective.stream()
                .map(ExtStaffingAssignmentReplica::getPersonId)
                .toList());
        List<ExtStaffingAssignmentReplica> assignments = effective.stream()
                .filter(a -> !offboarded.contains(a.getPersonId()))
                .toList();

        Map<UUID, ExtPersonReplica> peopleById = personReplicaRepository
                .findByPersonIdIn(assignments.stream()
                        .map(ExtStaffingAssignmentReplica::getPersonId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ExtPersonReplica::getPersonId, p -> p));

        Map<UUID, List<ExtPersonCredentialReplica>> credentialsByPerson = credentialsByPerson(assignments.stream()
                .map(ExtStaffingAssignmentReplica::getPersonId)
                .collect(Collectors.toSet()));

        List<PeopleAvailabilityResponse.PersonAvailability> people = assignments.stream()
                .map(a -> {
                    ExtPersonReplica person = peopleById.get(a.getPersonId());
                    return PeopleAvailabilityResponse.PersonAvailability.builder()
                            .personId(a.getPersonId().toString())
                            .firstName(person != null ? person.getFirstName() : null)
                            .lastName(person != null ? person.getLastName() : null)
                            .currentLocationId(a.getLocationId().toString())
                            .certifications(heldCertifications(credentialsByPerson.get(a.getPersonId()), date))
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
     * @return whether {@code personId} is eligible to hold a workorder at {@code siteId}; always false
     *         for a person whose latest employment status is inactive, checked before the
     *         no-staffing softening above (#2120)
     */
    public boolean isEligibleAtSite(@NonNull UUID personId, @NonNull UUID siteId, @NonNull LocalDate date) {
        if (inactiveEmploymentStatus(personId).isPresent()) {
            return false;
        }
        List<ExtStaffingAssignmentReplica> activeAssignments =
                assignmentReplicaRepository.findByPersonIdAndStatus(personId, ACTIVE).stream()
                        .filter(a -> effectiveOn(a, date))
                        .toList();
        return activeAssignments.isEmpty()
                || activeAssignments.stream().anyMatch(a -> siteId.equals(a.getLocationId()));
    }

    /**
     * The person's employment status when it is inactive (TERMINATED, DISABLED or SUSPENDED) on
     * their latest {@code ext_people_employee} row; empty when they are employed or have no row
     * (#2119, #2120). The single definition of "off the roster" shared by the board and the assign
     * gate.
     */
    @NonNull
    public Optional<String> inactiveEmploymentStatus(@NonNull UUID personId) {
        return ExtEmployeeReplica.latest(employeeReplicaRepository.findByPersonId(personId))
                .map(ExtEmployeeReplica::getStatus)
                .filter(ExtEmployeeReplica::isInactiveStatus);
    }

    private Map<UUID, List<ExtPersonCredentialReplica>> credentialsByPerson(@NonNull Set<UUID> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        return credentialReplicaRepository.findByPersonIdIn(personIds).stream()
                .collect(Collectors.groupingBy(ExtPersonCredentialReplica::getPersonId));
    }

    /**
     * The certification codes a person holds on {@code date}, or {@code null} when the replica has no
     * credential row for them (no data, not "holds none").
     *
     * <p>The string space of a workorder's {@code requiredCertifications} is not defined anywhere
     * (nothing writes the column, and the fixtures use {@code BRAKE_CERT}), while pos-people
     * identifies a credential by registry skill code ({@code BRAKES-LIGHT}) and the competence it
     * certifies ({@code BRAKES}). A held credential therefore contributes both codes, so a
     * requirement written in either form is met.
     */
    @Nullable
    private static List<String> heldCertifications(
            @Nullable List<ExtPersonCredentialReplica> credentials, @NonNull LocalDate date) {
        if (credentials == null || credentials.isEmpty()) {
            return null;
        }
        Set<String> held = new LinkedHashSet<>();
        for (ExtPersonCredentialReplica credential : credentials) {
            if (credential.isHeldOn(date)) {
                held.add(credential.getSkillCode());
                held.add(credential.getCompetenceCode());
            }
        }
        return List.copyOf(held);
    }

    private Set<UUID> inactivePersonIds(@NonNull List<UUID> personIds) {
        if (personIds.isEmpty()) {
            return Set.of();
        }
        Map<UUID, List<ExtEmployeeReplica>> byPerson =
                employeeReplicaRepository.findByPersonIdIn(new HashSet<>(personIds)).stream()
                        .collect(Collectors.groupingBy(ExtEmployeeReplica::getPersonId));
        Set<UUID> inactive = new HashSet<>();
        byPerson.forEach((personId, rows) -> {
            if (ExtEmployeeReplica.latest(rows)
                    .map(ExtEmployeeReplica::getStatus)
                    .filter(ExtEmployeeReplica::isInactiveStatus)
                    .isPresent()) {
                inactive.add(personId);
            }
        });
        return inactive;
    }

    private boolean effectiveOn(ExtStaffingAssignmentReplica assignment, LocalDate date) {
        boolean started = assignment.getEffectiveFrom() == null
                || !assignment.getEffectiveFrom().isAfter(date);
        boolean notEnded = assignment.getEffectiveTo() == null
                || !assignment.getEffectiveTo().isBefore(date);
        return started && notEnded;
    }
}

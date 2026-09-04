package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.PeopleAvailabilityResponse;
import com.positivity.people.internal.dto.PrimaryLocationResolution;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.ExtLocationReplica;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.ExtLocationReplicaRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.security.common.SecurityContextHelper;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    @NonNull
    public List<PeopleAvailabilityResponse> getPeopleAvailability(UUID locationId, LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        UUID resolvedLocationId = locationId != null ? locationId : resolveRequesterLocationId(targetDate);

        List<EmployeeLocationAssignment> assignments =
                assignmentRepository.findActiveByDateAndOptionalLocation(targetDate, resolvedLocationId);

        Map<UUID, ExtPersonReplica> peopleById = extPersonReplicaRepository
                .findAllById(assignments.stream()
                        .map(EmployeeLocationAssignment::getPersonId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ExtPersonReplica::getPersonId, person -> person));

        return assignments.stream()
                .map(assignment -> {
                    ExtPersonReplica person = peopleById.get(assignment.getPersonId());
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
                            .build();
                })
                .toList();
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

package com.positivity.people.internal.service;

import com.positivity.people.internal.config.PeopleEventPublisher;
import com.positivity.people.internal.dto.CreateStaffingAssignmentRequest;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.internal.dto.UpdateStaffingAssignmentRequest;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Person-to-location staffing assignments.
 *
 * <h2>Location scope on the mutations (ADR-0061 §3, #1872)</h2>
 *
 * An assignment is what feeds a person's location-scope claims, so a caller whose
 * {@code people:employee:edit} is location-scoped must only be able to assign, reshape or end
 * assignments within their own reach — otherwise a LOCATION-scoped HR user could grant
 * themselves or others reach they do not hold. Every mutation is therefore a <b>gate</b> on the
 * assignment's location: {@link #create} on the requested location, {@link #update} on both the
 * existing assignment's location and the requested one, {@link #end} on the existing one. Each
 * gate runs after the existence checks so a 404 precedes a 403 and ids cannot be probed; an
 * uncovered location is a 403 {@code LOCATION_SCOPE_DENIED}. Callers whose permission is global,
 * or whose token predates the scope claims, are unaffected.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StaffingAssignmentServiceImpl implements StaffingAssignmentService {

    private final EmployeeLocationAssignmentRepository repository;

    private final ExtPersonReplicaRepository extPersonReplicaRepository;

    private final PeopleEventPublisher peopleEventPublisher;

    private final EmployeeRepository employeeRepository;

    private final LocationReferenceService locationReferenceService;

    private final Clock clock;

    @Override
    @Transactional
    public @NonNull StaffingAssignmentResponse create(
            @NonNull CreateStaffingAssignmentRequest request, @NonNull String actor) {
        validatePersonAndLocation(request.getPersonId(), request.getLocationId());
        requireLocationInReach(request.getLocationId());

        if (repository.existsOverlapping(
                request.getPersonId(),
                request.getLocationId(),
                request.getRole(),
                request.getEffectiveFrom(),
                request.getEffectiveTo())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An overlapping assignment already exists for this person, location, and role");
        }

        boolean primary = request.isPrimary();
        if (primary) {
            repository
                    .findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(
                            request.getPersonId(), AssignmentStatus.ACTIVE)
                    .ifPresent(existing -> {
                        if (!dateRangesOverlap(
                                existing.getEffectiveFrom(),
                                existing.getEffectiveTo(),
                                request.getEffectiveFrom(),
                                request.getEffectiveTo())) {
                            return;
                        }

                        LocalDate demotionDate = request.getEffectiveFrom().minusDays(1);
                        if (demotionDate.isBefore(existing.getEffectiveFrom())) {
                            demotionDate = existing.getEffectiveFrom();
                        }

                        existing.setEffectiveTo(demotionDate);
                        existing.setStatus(AssignmentStatus.ENDED);
                        peopleEventPublisher.publishStaffingAssignmentUpdated(repository.save(existing));
                    });
        } else if (repository
                .findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(request.getPersonId(), AssignmentStatus.ACTIVE)
                .isEmpty()) {
            // A person's only location must be their primary: /me/primary-location
            // (and its service-to-service consumers, e.g. pos-workorder location
            // resolution) returns 404 when no assignment carries the flag.
            primary = true;
            log.info(
                    "Defaulting assignment to primary: person {} has no active primary location",
                    request.getPersonId());
        }

        EmployeeLocationAssignment assignment = EmployeeLocationAssignment.builder()
                .employee(resolveEmployee(request.getPersonId()))
                .locationId(request.getLocationId())
                .role(request.getRole())
                .isPrimary(primary)
                .effectiveFrom(request.getEffectiveFrom())
                .effectiveTo(request.getEffectiveTo())
                .status(AssignmentStatus.ACTIVE)
                .createdBy(actor)
                .build();

        EmployeeLocationAssignment saved = repository.save(assignment);
        peopleEventPublisher.publishStaffingAssignmentUpdated(saved);
        log.info(
                "Created staffing assignment {} for person {} at location {}",
                saved.getId(),
                request.getPersonId(),
                request.getLocationId());

        return toResponse(saved);
    }

    @Override
    public @NonNull List<StaffingAssignmentResponse> findByPersonId(@NonNull UUID personId) {
        return repository.findByEmployee_PersonId(personId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public @NonNull List<StaffingAssignmentResponse> findActiveByPersonId(@NonNull UUID personId) {
        return repository.findActiveByPersonIdAndDate(personId, LocalDate.now(clock)).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public @NonNull Optional<StaffingAssignmentResponse> findById(@NonNull UUID assignmentId) {
        return repository.findById(assignmentId).map(this::toResponse);
    }

    @Override
    @Transactional
    public @NonNull Optional<StaffingAssignmentResponse> update(
            @NonNull UUID assignmentId, @NonNull UpdateStaffingAssignmentRequest request, @NonNull String actor) {
        Optional<EmployeeLocationAssignment> existingAssignment = repository.findById(assignmentId);
        if (existingAssignment.isEmpty()) {
            return Optional.empty();
        }
        // Both ends of the move are gated: the assignment being taken away from its current
        // location, and the location it is being given to.
        requireLocationInReach(existingAssignment.get().getLocationId());
        validatePersonAndLocation(request.getPersonId(), request.getLocationId());
        requireLocationInReach(request.getLocationId());

        if (repository.existsOverlappingExcludingId(
                assignmentId,
                request.getPersonId(),
                request.getLocationId(),
                request.getRole(),
                request.getEffectiveFrom(),
                request.getEffectiveTo())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An overlapping assignment already exists for this person, location, and role");
        }

        EmployeeLocationAssignment assignment = existingAssignment.get();

        if (request.isPrimary()) {
            repository
                    .findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(
                            request.getPersonId(), AssignmentStatus.ACTIVE)
                    .filter(existing -> !existing.getId().equals(assignmentId))
                    .ifPresent(existing -> {
                        if (!dateRangesOverlap(
                                existing.getEffectiveFrom(),
                                existing.getEffectiveTo(),
                                request.getEffectiveFrom(),
                                request.getEffectiveTo())) {
                            return;
                        }

                        LocalDate demotionDate = request.getEffectiveFrom().minusDays(1);
                        if (demotionDate.isBefore(existing.getEffectiveFrom())) {
                            demotionDate = existing.getEffectiveFrom();
                        }

                        existing.setEffectiveTo(demotionDate);
                        existing.setStatus(AssignmentStatus.ENDED);
                        peopleEventPublisher.publishStaffingAssignmentUpdated(repository.save(existing));
                    });
        }

        assignment.setEmployee(resolveEmployee(request.getPersonId()));
        assignment.setLocationId(request.getLocationId());
        assignment.setRole(request.getRole());
        assignment.setPrimary(request.isPrimary());
        assignment.setEffectiveFrom(request.getEffectiveFrom());
        assignment.setEffectiveTo(request.getEffectiveTo());

        EmployeeLocationAssignment saved = repository.save(assignment);
        peopleEventPublisher.publishStaffingAssignmentUpdated(saved);
        log.info("Updated staffing assignment {} by actor {}", saved.getId(), actor);
        return Optional.of(toResponse(saved));
    }

    @Override
    @Transactional
    public void end(@NonNull UUID assignmentId) {
        EmployeeLocationAssignment assignment = repository
                .findById(assignmentId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found: " + assignmentId));
        requireLocationInReach(assignment.getLocationId());
        assignment.setStatus(AssignmentStatus.ENDED);
        if (assignment.getEffectiveTo() == null) {
            assignment.setEffectiveTo(LocalDate.now(clock));
        }
        peopleEventPublisher.publishStaffingAssignmentUpdated(repository.save(assignment));
    }

    private StaffingAssignmentResponse toResponse(EmployeeLocationAssignment assignment) {
        return new StaffingAssignmentResponse(
                assignment.getId(),
                assignment.getPersonId(),
                assignment.getLocationId(),
                assignment.getRole(),
                assignment.isPrimary(),
                assignment.getStatus(),
                assignment.getEffectiveFrom(),
                assignment.getEffectiveTo(),
                assignment.getCreatedAt(),
                assignment.getUpdatedAt(),
                assignment.getCreatedBy());
    }

    private boolean dateRangesOverlap(
            LocalDate leftStart, LocalDate leftEnd, LocalDate rightStart, LocalDate rightEnd) {
        LocalDate normalizedLeftEnd = leftEnd != null ? leftEnd : LocalDate.MAX;
        LocalDate normalizedRightEnd = rightEnd != null ? rightEnd : LocalDate.MAX;
        return !normalizedLeftEnd.isBefore(rightStart) && !normalizedRightEnd.isBefore(leftStart);
    }

    /**
     * Gate: the caller's {@code people:employee:edit} must cover {@code locationId}, or this is a
     * 403 {@code LOCATION_SCOPE_DENIED}. A global or pre-rollout caller always passes.
     */
    private static void requireLocationInReach(@NonNull UUID locationId) {
        LocationScope scope = SecurityContextHelper.locationScope();
        scope.require(PeoplePermissions.EMPLOYEE_EDIT, locationId);
    }

    /** Resolve the Employee row for a person; 404 if the person is not an employee. */
    private Employee resolveEmployee(@NonNull UUID personId) {
        return employeeRepository
                .findByPersonId(personId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Employee not found for person: " + personId));
    }

    private void validatePersonAndLocation(@NonNull UUID personId, @NonNull UUID locationId) {
        extPersonReplicaRepository
                .findById(personId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found: " + personId));

        EmployeeStatus status = employeeRepository
                .findByPersonId(personId)
                .map(com.positivity.people.internal.entity.Employee::getStatus)
                .orElse(null);
        if (status != EmployeeStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Person is not active: " + personId);
        }

        if (!locationReferenceService.isLocationActive(locationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found or inactive: " + locationId);
        }
    }
}

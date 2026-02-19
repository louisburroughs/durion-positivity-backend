package com.positivity.people.service;

import com.positivity.people.internal.client.LocationReferenceClient;
import com.positivity.people.internal.dto.CreateStaffingAssignmentRequest;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.internal.dto.UpdateStaffingAssignmentRequest;
import com.positivity.people.internal.entity.PersonLocationAssignment;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.PersonLocationAssignmentRepository;
import com.positivity.people.internal.repository.PersonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StaffingAssignmentServiceImpl implements StaffingAssignmentService {

    private final PersonLocationAssignmentRepository repository;
    private final PersonRepository personRepository;
    private final LocationReferenceClient locationReferenceClient;

    @Override
    @Transactional
    public @NonNull StaffingAssignmentResponse create(
            @NonNull CreateStaffingAssignmentRequest request,
            @NonNull String actor) {
        validatePersonAndLocation(request.personId(), request.locationId());

        if (repository.existsOverlapping(
                request.personId(), request.locationId(), request.role(),
                request.effectiveFrom(), request.effectiveTo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An overlapping assignment already exists for this person, location, and role");
        }

        if (request.isPrimary()) {
            repository.findFirstByPersonIdAndIsPrimaryTrueAndStatus(
                    request.personId(), AssignmentStatus.ACTIVE)
                    .ifPresent(existing -> {
                        if (!dateRangesOverlap(
                                existing.getEffectiveFrom(),
                                existing.getEffectiveTo(),
                                request.effectiveFrom(),
                                request.effectiveTo())) {
                            return;
                        }

                        LocalDate demotionDate = request.effectiveFrom().minusDays(1);
                        if (demotionDate.isBefore(existing.getEffectiveFrom())) {
                            demotionDate = existing.getEffectiveFrom();
                        }

                        existing.setEffectiveTo(demotionDate);
                        existing.setStatus(AssignmentStatus.ENDED);
                        repository.save(existing);
                    });
        }

        PersonLocationAssignment assignment = PersonLocationAssignment.builder()
                .personId(request.personId())
                .locationId(request.locationId())
                .role(request.role())
                .isPrimary(request.isPrimary())
                .effectiveFrom(request.effectiveFrom())
                .effectiveTo(request.effectiveTo())
                .status(AssignmentStatus.ACTIVE)
                .createdBy(actor)
                .build();

        PersonLocationAssignment saved = repository.save(assignment);
        log.info("Created staffing assignment {} for person {} at location {}",
                saved.getId(), request.personId(), request.locationId());

        return toResponse(saved);
    }

    @Override
    public @NonNull List<StaffingAssignmentResponse> findByPersonId(@NonNull UUID personId) {
        return repository.findByPersonId(personId).stream()
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
            @NonNull UUID assignmentId,
            @NonNull UpdateStaffingAssignmentRequest request,
            @NonNull String actor) {
        Optional<PersonLocationAssignment> existingAssignment = repository.findById(assignmentId);
        if (existingAssignment.isEmpty()) {
            return Optional.empty();
        }
        validatePersonAndLocation(request.personId(), request.locationId());

        if (repository.existsOverlappingExcludingId(
                assignmentId,
                request.personId(),
                request.locationId(),
                request.role(),
                request.effectiveFrom(),
                request.effectiveTo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An overlapping assignment already exists for this person, location, and role");
        }

        PersonLocationAssignment assignment = existingAssignment.get();

        if (request.isPrimary()) {
            repository.findFirstByPersonIdAndIsPrimaryTrueAndStatus(
                    request.personId(), AssignmentStatus.ACTIVE)
                    .filter(existing -> !existing.getId().equals(assignmentId))
                    .ifPresent(existing -> {
                        if (!dateRangesOverlap(
                                existing.getEffectiveFrom(),
                                existing.getEffectiveTo(),
                                request.effectiveFrom(),
                                request.effectiveTo())) {
                            return;
                        }

                        LocalDate demotionDate = request.effectiveFrom().minusDays(1);
                        if (demotionDate.isBefore(existing.getEffectiveFrom())) {
                            demotionDate = existing.getEffectiveFrom();
                        }

                        existing.setEffectiveTo(demotionDate);
                        existing.setStatus(AssignmentStatus.ENDED);
                        repository.save(existing);
                    });
        }

        assignment.setPersonId(request.personId());
        assignment.setLocationId(request.locationId());
        assignment.setRole(request.role());
        assignment.setPrimary(request.isPrimary());
        assignment.setEffectiveFrom(request.effectiveFrom());
        assignment.setEffectiveTo(request.effectiveTo());

        PersonLocationAssignment saved = repository.save(assignment);
        log.info("Updated staffing assignment {} by actor {}", saved.getId(), actor);
        return Optional.of(toResponse(saved));
    }

    @Override
    @Transactional
    public void end(@NonNull UUID assignmentId) {
        PersonLocationAssignment assignment = repository.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Assignment not found: " + assignmentId));
        assignment.setStatus(AssignmentStatus.ENDED);
        if (assignment.getEffectiveTo() == null) {
            assignment.setEffectiveTo(LocalDate.now());
        }
        repository.save(assignment);
    }

    private StaffingAssignmentResponse toResponse(PersonLocationAssignment assignment) {
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

    private boolean dateRangesOverlap(LocalDate leftStart, LocalDate leftEnd, LocalDate rightStart, LocalDate rightEnd) {
        LocalDate normalizedLeftEnd = leftEnd != null ? leftEnd : LocalDate.MAX;
        LocalDate normalizedRightEnd = rightEnd != null ? rightEnd : LocalDate.MAX;
        return !normalizedLeftEnd.isBefore(rightStart) && !normalizedRightEnd.isBefore(leftStart);
    }

    private void validatePersonAndLocation(@NonNull UUID personId, @NonNull UUID locationId) {
        var person = personRepository.findById(personId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found: " + personId));

        if (person.getStatus() != EmployeeStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Person is not active: " + personId);
        }

        if (!locationReferenceClient.isLocationActive(locationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found or inactive: " + locationId);
        }
    }
}

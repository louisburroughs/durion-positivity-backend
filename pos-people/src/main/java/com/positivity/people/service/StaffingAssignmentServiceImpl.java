package com.positivity.people.service;

import com.positivity.people.internal.dto.CreateStaffingAssignmentRequest;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.internal.entity.PersonLocationAssignment;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.repository.PersonLocationAssignmentRepository;
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

    @Override
    @Transactional
    public @NonNull StaffingAssignmentResponse create(
            @NonNull CreateStaffingAssignmentRequest request,
            @NonNull String actor) {

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
                        existing.setEffectiveTo(request.effectiveFrom().minusDays(1));
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
                assignment.getCreatedBy()
        );
    }
}
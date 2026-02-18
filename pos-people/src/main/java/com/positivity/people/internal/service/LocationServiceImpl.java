package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.AssignStaffRequest;
import com.positivity.people.internal.dto.CreateLocationRequest;
import com.positivity.people.internal.dto.LocationDto;
import com.positivity.people.internal.dto.PersonLocationAssignmentDto;
import com.positivity.people.internal.dto.UpdateLocationRequest;
import com.positivity.people.internal.entity.Location;
import com.positivity.people.internal.entity.PersonLocationAssignment;
import com.positivity.people.internal.exception.DuplicateLocationCodeException;
import com.positivity.people.internal.exception.LocationAssignmentNotFoundException;
import com.positivity.people.internal.exception.LocationNotFoundException;
import com.positivity.people.internal.exception.PersonLocationAssignmentConflictException;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.repository.LocationRepository;
import com.positivity.people.internal.repository.PersonLocationAssignmentRepository;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.service.LocationService;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional
public class LocationServiceImpl implements LocationService {

    private final LocationRepository locationRepository;
    private final PersonLocationAssignmentRepository assignmentRepository;
    private final PersonRepository personRepository;

    public LocationServiceImpl(
            @NonNull LocationRepository locationRepository,
            @NonNull PersonLocationAssignmentRepository assignmentRepository,
            @NonNull PersonRepository personRepository) {
        this.locationRepository = locationRepository;
        this.assignmentRepository = assignmentRepository;
        this.personRepository = personRepository;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<LocationDto> listActiveLocations() {
        return locationRepository.findAllByActiveTrue().stream().map(this::toLocationDto).toList();
    }

    @Override
    @NonNull
    public LocationDto createLocation(@NonNull CreateLocationRequest request) {
        validateTimezone(request.getTimezone());
        String normalizedCode = normalizeCode(request.getCode());
        locationRepository.findByCode(normalizedCode).ifPresent(existing -> {
            throw new DuplicateLocationCodeException(normalizedCode);
        });

        Location location = new Location();
        location.setCode(normalizedCode);
        location.setDisplayName(request.getDisplayName().trim());
        location.setLocationType(request.getLocationType());
        location.setAddress(request.getAddress());
        location.setTimezone(request.getTimezone().trim());
        location.setManagerId(request.getManagerId());
        location.setActive(true);

        return toLocationDto(locationRepository.save(location));
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public LocationDto getLocation(@NonNull UUID locationId) {
        Location location = locationRepository.findByLocationIdAndActiveTrue(locationId)
                .orElseThrow(() -> new LocationNotFoundException(locationId));
        return toLocationDto(location);
    }

    @Override
    @NonNull
    public LocationDto updateLocation(@NonNull UUID locationId, @NonNull UpdateLocationRequest request) {
        Location location = locationRepository.findByLocationIdAndActiveTrue(locationId)
                .orElseThrow(() -> new LocationNotFoundException(locationId));

        if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
            location.setDisplayName(request.getDisplayName().trim());
        }
        if (request.getLocationType() != null) {
            location.setLocationType(request.getLocationType());
        }
        if (request.getAddress() != null) {
            location.setAddress(request.getAddress());
        }
        if (request.getTimezone() != null) {
            validateTimezone(request.getTimezone());
            location.setTimezone(request.getTimezone().trim());
        }
        if (request.getManagerId() != null) {
            location.setManagerId(request.getManagerId());
        }
        if (request.getActive() != null) {
            location.setActive(request.getActive());
        }

        return toLocationDto(locationRepository.save(location));
    }

    @Override
    public void deleteLocation(@NonNull UUID locationId) {
        Location location = locationRepository.findByLocationIdAndActiveTrue(locationId)
                .orElseThrow(() -> new LocationNotFoundException(locationId));
        location.setActive(false);
        locationRepository.save(location);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<PersonLocationAssignmentDto> getAssignmentsByLocation(@NonNull UUID locationId) {
        ensureLocationExists(locationId);
        return assignmentRepository.findByLocationId(locationId).stream()
                .sorted(Comparator.comparing(PersonLocationAssignment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toAssignmentDto)
                .toList();
    }

    @Override
    @NonNull
    public PersonLocationAssignmentDto assignStaff(@NonNull UUID locationId, @NonNull AssignStaffRequest request) {
        ensureLocationExists(locationId);
        personRepository.findById(request.getPersonId())
                .orElseThrow(() -> new PersonNotFoundException(request.getPersonId()));

        LocalDate effectiveFrom = request.getEffectiveFrom() == null ? LocalDate.now() : request.getEffectiveFrom();
        LocalDate effectiveTo = request.getEffectiveTo();

        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be greater than or equal to effectiveFrom");
        }

        List<PersonLocationAssignment> existingAtLocation = assignmentRepository
                .findByLocationIdAndPersonId(locationId, request.getPersonId());

        for (PersonLocationAssignment existing : existingAtLocation) {
            if (hasOverlap(existing.getEffectiveFrom(), existing.getEffectiveTo(), effectiveFrom, effectiveTo)) {
                throw new PersonLocationAssignmentConflictException("Person is already assigned at this location");
            }
        }

        if (Boolean.TRUE.equals(request.getIsPrimary())) {
            demoteExistingPrimaryAssignments(request.getPersonId(), effectiveFrom);
        }

        PersonLocationAssignment assignment = new PersonLocationAssignment();
        assignment.setLocationId(locationId);
        assignment.setPersonId(request.getPersonId());
        assignment.setRole(request.getRole() == null || request.getRole().isBlank() ? "ASSOCIATE" : request.getRole().trim());
        assignment.setPrimary(Boolean.TRUE.equals(request.getIsPrimary()));
        assignment.setEffectiveFrom(effectiveFrom);
        assignment.setEffectiveTo(effectiveTo);

        return toAssignmentDto(assignmentRepository.save(assignment));
    }

    @Override
    public void unassignStaff(@NonNull UUID locationId, @NonNull UUID personId) {
        ensureLocationExists(locationId);

        List<PersonLocationAssignment> existing = assignmentRepository.findByLocationIdAndPersonId(locationId, personId);
        if (existing.isEmpty()) {
            throw new LocationAssignmentNotFoundException(locationId, personId);
        }

        LocalDate today = LocalDate.now();
        PersonLocationAssignment activeAssignment = existing.stream()
                .filter(a -> a.getEffectiveTo() == null || !a.getEffectiveTo().isBefore(today))
                .findFirst()
                .orElse(existing.get(0));

        activeAssignment.setPrimary(false);
        activeAssignment.setEffectiveTo(today);
        assignmentRepository.save(activeAssignment);
    }

    private void ensureLocationExists(UUID locationId) {
        locationRepository.findByLocationIdAndActiveTrue(locationId)
                .orElseThrow(() -> new LocationNotFoundException(locationId));
    }

    private void demoteExistingPrimaryAssignments(UUID personId, LocalDate newPrimaryStartDate) {
        List<PersonLocationAssignment> assignments = assignmentRepository.findByPersonId(personId);

        for (PersonLocationAssignment assignment : assignments) {
            if (assignment.isPrimary() && isActiveOn(assignment, newPrimaryStartDate)) {
                assignment.setPrimary(false);
                LocalDate demotedEndDate = newPrimaryStartDate.minusDays(1);
                if (assignment.getEffectiveTo() == null || assignment.getEffectiveTo().isAfter(demotedEndDate)) {
                    assignment.setEffectiveTo(demotedEndDate);
                }
                assignmentRepository.save(assignment);
            }
        }
    }

    private boolean isActiveOn(PersonLocationAssignment assignment, LocalDate onDate) {
        boolean starts = !assignment.getEffectiveFrom().isAfter(onDate);
        boolean ends = assignment.getEffectiveTo() == null || !assignment.getEffectiveTo().isBefore(onDate);
        return starts && ends;
    }

    private boolean hasOverlap(LocalDate startA, LocalDate endA, LocalDate startB, LocalDate endB) {
        LocalDate normalizedEndA = endA == null ? LocalDate.of(9999, 12, 31) : endA;
        LocalDate normalizedEndB = endB == null ? LocalDate.of(9999, 12, 31) : endB;
        return !startA.isAfter(normalizedEndB) && !startB.isAfter(normalizedEndA);
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private void validateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("timezone is required");
        }
        try {
            ZoneId.of(timezone.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("timezone must be a valid IANA timezone");
        }
    }

    private LocationDto toLocationDto(Location location) {
        return LocationDto.builder()
                .locationId(location.getLocationId())
                .code(location.getCode())
                .displayName(location.getDisplayName())
                .locationType(location.getLocationType())
                .address(location.getAddress())
                .timezone(location.getTimezone())
                .active(location.isActive())
                .managerId(location.getManagerId())
                .createdAt(location.getCreatedAt())
                .updatedAt(location.getUpdatedAt())
                .build();
    }

    private PersonLocationAssignmentDto toAssignmentDto(PersonLocationAssignment assignment) {
        return PersonLocationAssignmentDto.builder()
                .assignmentId(assignment.getAssignmentId())
                .locationId(assignment.getLocationId())
                .personId(assignment.getPersonId())
                .role(assignment.getRole())
                .isPrimary(assignment.isPrimary())
                .effectiveFrom(assignment.getEffectiveFrom())
                .effectiveTo(assignment.getEffectiveTo())
                .createdAt(assignment.getCreatedAt())
                .updatedAt(assignment.getUpdatedAt())
                .build();
    }
}

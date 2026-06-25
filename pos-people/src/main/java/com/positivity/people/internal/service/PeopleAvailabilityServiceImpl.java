package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.PeopleAvailabilityResponse;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.service.PeopleAvailabilityService;
import com.positivity.people.service.UserPersonTranslationService;
import com.positivity.security.common.SecurityContextHelper;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    private final PersonRepository personRepository;

    private final UserPersonTranslationService userPersonTranslationService;

    private final Clock clock;

    @Override
    @NonNull
    public List<PeopleAvailabilityResponse> getPeopleAvailability(UUID locationId, LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        UUID resolvedLocationId = locationId != null ? locationId : resolveRequesterLocationId(targetDate);

        List<EmployeeLocationAssignment> assignments =
                assignmentRepository.findActiveByDateAndOptionalLocation(targetDate, resolvedLocationId);

        Map<UUID, Person> peopleById = personRepository
                .findAllById(assignments.stream()
                        .map(EmployeeLocationAssignment::getPersonId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Person::getId, person -> person));

        return assignments.stream()
                .map(assignment -> {
                    Person person = peopleById.get(assignment.getPersonId());
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
    public UUID resolveCurrentUserPrimaryLocationId() {
        return resolveRequesterLocationId(LocalDate.now(clock));
    }

    @NonNull
    private UUID resolveRequesterLocationId(@NonNull LocalDate targetDate) {
        String currentUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new EntityNotFoundException(
                        "locationId was not provided and authenticated user context is missing"));

        UUID userId = parseUuid(currentUserId, "authenticated userId");
        UUID personId = userPersonTranslationService.getPersonUuidForUser(userId);

        return assignmentRepository.findActiveByPersonIdAndDate(personId, targetDate).stream()
                .findFirst()
                .map(EmployeeLocationAssignment::getLocationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "locationId was not provided and no active location assignment exists for requester on "
                                + targetDate));
    }

    @NonNull
    private UUID parseUuid(@NonNull String value, @NonNull String label) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value, ex);
        }
    }
}

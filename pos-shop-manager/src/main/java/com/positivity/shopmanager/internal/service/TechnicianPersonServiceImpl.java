package com.positivity.shopmanager.internal.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.positivity.shopmanager.internal.dto.PersonDTO;
import com.positivity.shopmanager.internal.entity.ExtPersonReplica;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Person identity for a technician at a location. "Works here" is an ACTIVE TECHNICIAN staffing
 * assignment on the {@code ext_people_staffing_assignment} replica (CAP-328 replaced the unwritten
 * {@code technician} table); names and contact points come from the people-contact replica.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TechnicianPersonServiceImpl implements TechnicianPersonService {

    static final String TECHNICIAN_ROLE = "TECHNICIAN";
    private static final String ASSIGNMENT_STATUS_ACTIVE = "ACTIVE";
    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder().build();

    private final ExtStaffingAssignmentReplicaRepository assignmentReplicaRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public PersonDTO getTechnicianPerson(@NonNull UUID locationId, @NonNull UUID personId) {
        boolean worksHere =
                assignmentReplicaRepository.findByPersonIdAndStatus(personId, ASSIGNMENT_STATUS_ACTIVE).stream()
                        .anyMatch(assignment -> isTechnicianAt(assignment, locationId));
        if (!worksHere) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TECHNICIAN_NOT_FOUND_AT_LOCATION");
        }
        return extPersonReplicaRepository
                .findById(personId)
                .map(this::toPersonDto)
                .orElseGet(() -> {
                    // The assignment is here; the person row is not yet (feed lag or not yet
                    // bootstrapped), so answer with the id and let names arrive with the feed.
                    log.debug(
                            "No people-contact replica row for technician person {} at location {}",
                            personId,
                            locationId);
                    return PersonDTO.builder().id(personId).build();
                });
    }

    private static boolean isTechnicianAt(ExtStaffingAssignmentReplica assignment, UUID locationId) {
        return TECHNICIAN_ROLE.equals(assignment.getRole()) && locationId.equals(assignment.getLocationId());
    }

    private PersonDTO toPersonDto(ExtPersonReplica replica) {
        List<ContactPoint> contactPoints = parseContactPoints(replica.getContactPoints());
        return PersonDTO.builder()
                .id(replica.getPersonId())
                .firstName(replica.getFirstName())
                .lastName(replica.getLastName())
                .primaryEmail(replica.getPrimaryEmail())
                .secondaryEmail(contactPoints.stream()
                        .filter(cp -> "EMAIL".equals(cp.contactType()) && cp.value() != null)
                        .map(ContactPoint::value)
                        .filter(value -> !value.equals(replica.getPrimaryEmail()))
                        .findFirst()
                        .orElse(null))
                .phoneNumbers(contactPoints.stream()
                        .filter(cp ->
                                cp.contactType() != null && cp.contactType().startsWith("PHONE"))
                        .map(ContactPoint::value)
                        .filter(value -> value != null && !value.isBlank())
                        .toList())
                .build();
    }

    private List<ContactPoint> parseContactPoints(String contactPointsJson) {
        if (contactPointsJson == null || contactPointsJson.isBlank()) {
            return List.of();
        }
        try {
            return JSON_MAPPER.readValue(contactPointsJson, new TypeReference<List<ContactPoint>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Unreadable contact_points snapshot in ext_people_contact_person", e);
            return List.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ContactPoint(String contactType, String value, boolean primary) {}
}

package com.positivity.shopmanager.internal.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.positivity.shopmanager.internal.dto.PersonDTO;
import com.positivity.shopmanager.internal.entity.ExtPersonReplica;
import com.positivity.shopmanager.internal.entity.Technician;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.TechnicianRepository;
import com.positivity.shopmanager.service.TechnicianPersonService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class TechnicianPersonServiceImpl implements TechnicianPersonService {

    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder().build();

    private final TechnicianRepository technicianRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public PersonDTO getTechnicianPerson(@NonNull UUID locationId, @NonNull UUID personId) {
        Technician technician = technicianRepository
                .findFirstByShopIdAndPersonId(locationId, personId)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TECHNICIAN_NOT_FOUND_AT_LOCATION"));
        UUID technicianPersonId = technician.getPersonId();
        return extPersonReplicaRepository
                .findById(technicianPersonId)
                .map(this::toPersonDto)
                .orElseGet(() -> {
                    // The technician exists; the replica row is missing (feed lag or not yet
                    // bootstrapped), so answer with the id and let names arrive with the feed.
                    log.debug(
                            "No people-contact replica row for technician person {} at location {}",
                            technicianPersonId,
                            locationId);
                    return PersonDTO.builder().id(technicianPersonId).build();
                });
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

    /** Local mirror of {@code PersonUpdatedV1.ContactPointV1} as persisted in the replica. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ContactPoint(String contactType, String value, boolean primary) {}
}

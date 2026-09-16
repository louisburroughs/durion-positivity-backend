package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.dto.PersonDTO;
import com.positivity.shopmanager.internal.entity.ExtPersonReplica;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Works here" is an ACTIVE TECHNICIAN staffing assignment at the location (CAP-328 retired the
 * unwritten {@code technician} table); identity comes from the people-contact replica.
 */
class TechnicianPersonServiceTest {

    private static final UUID LOCATION_ID = UUID.fromString("018e1c9f-6b5a-7890-abcd-1234567890ab");
    private static final UUID OTHER_LOCATION_ID = UUID.fromString("018e1c9f-6b5a-7890-abcd-1234567890ac");
    private static final UUID PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000001");

    private ExtStaffingAssignmentReplicaRepository assignmentRepository;
    private ExtPersonReplicaRepository extPersonReplicaRepository;
    private TechnicianPersonService service;

    @BeforeEach
    void setUp() {
        assignmentRepository = mock(ExtStaffingAssignmentReplicaRepository.class);
        extPersonReplicaRepository = mock(ExtPersonReplicaRepository.class);
        service = new TechnicianPersonServiceImpl(assignmentRepository, extPersonReplicaRepository);
    }

    private static ExtStaffingAssignmentReplica assignment(UUID locationId, String role) {
        return ExtStaffingAssignmentReplica.builder()
                .assignmentId(UUID.randomUUID())
                .employeeId(UUID.randomUUID())
                .personId(PERSON_ID)
                .locationId(locationId)
                .role(role)
                .primary(true)
                .status("ACTIVE")
                .aggregateVersion(1)
                .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("Replica hit maps identity, emails, and phone numbers")
    void replicaHitMapsIdentity() {
        when(assignmentRepository.findByPersonIdAndStatus(PERSON_ID, "ACTIVE"))
                .thenReturn(List.of(assignment(LOCATION_ID, "TECHNICIAN")));
        when(extPersonReplicaRepository.findById(PERSON_ID))
                .thenReturn(Optional.of(ExtPersonReplica.builder()
                        .personId(PERSON_ID)
                        .firstName("Jane")
                        .lastName("Doe")
                        .primaryEmail("jane.doe@example.com")
                        .contactPoints(
                                "[{\"contactType\":\"EMAIL\",\"value\":\"jane.doe@example.com\",\"primary\":true},"
                                        + "{\"contactType\":\"EMAIL\",\"value\":\"jane.d@example.com\",\"primary\":false},"
                                        + "{\"contactType\":\"PHONE_WORK\",\"value\":\"+1-217-555-0100\",\"primary\":false}]")
                        .aggregateVersion(1L)
                        .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                        .build()));

        PersonDTO person = service.getTechnicianPerson(LOCATION_ID, PERSON_ID);

        assertThat(person.getId()).isEqualTo(PERSON_ID);
        assertThat(person.getFirstName()).isEqualTo("Jane");
        assertThat(person.getLastName()).isEqualTo("Doe");
        assertThat(person.getPrimaryEmail()).isEqualTo("jane.doe@example.com");
        assertThat(person.getSecondaryEmail()).isEqualTo("jane.d@example.com");
        assertThat(person.getPhoneNumbers()).containsExactly("+1-217-555-0100");
    }

    @Test
    @DisplayName("Replica miss answers with the person id only")
    void replicaMissAnswersIdOnly() {
        when(assignmentRepository.findByPersonIdAndStatus(PERSON_ID, "ACTIVE"))
                .thenReturn(List.of(assignment(LOCATION_ID, "TECHNICIAN")));
        when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.empty());

        PersonDTO person = service.getTechnicianPerson(LOCATION_ID, PERSON_ID);

        assertThat(person.getId()).isEqualTo(PERSON_ID);
        assertThat(person.getFirstName()).isNull();
    }

    @Test
    @DisplayName("404 when no ACTIVE TECHNICIAN assignment links the person to the location")
    void notFoundWhenNoTechnicianAssignmentHere() {
        // A technician elsewhere and a non-technician role here are both "not a technician here".
        when(assignmentRepository.findByPersonIdAndStatus(PERSON_ID, "ACTIVE"))
                .thenReturn(
                        List.of(assignment(OTHER_LOCATION_ID, "TECHNICIAN"), assignment(LOCATION_ID, "DISPATCHER")));

        assertThatThrownBy(() -> service.getTechnicianPerson(LOCATION_ID, PERSON_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(extPersonReplicaRepository);
    }
}

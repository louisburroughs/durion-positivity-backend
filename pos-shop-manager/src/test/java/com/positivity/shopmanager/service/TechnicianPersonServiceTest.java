package com.positivity.shopmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.dto.PersonDTO;
import com.positivity.shopmanager.internal.entity.ExtPersonReplica;
import com.positivity.shopmanager.internal.entity.Technician;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.TechnicianRepository;
import com.positivity.shopmanager.internal.service.TechnicianPersonServiceImpl;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TechnicianPersonServiceTest {

    private static final UUID LOCATION_ID = UUID.fromString("018e1c9f-6b5a-7890-abcd-1234567890ab");
    private static final UUID PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000001");

    private TechnicianRepository technicianRepository;
    private ExtPersonReplicaRepository extPersonReplicaRepository;
    private TechnicianPersonService service;

    @BeforeEach
    void setUp() {
        technicianRepository = mock(TechnicianRepository.class);
        extPersonReplicaRepository = mock(ExtPersonReplicaRepository.class);
        service = new TechnicianPersonServiceImpl(technicianRepository, extPersonReplicaRepository);
    }

    private Technician technician() {
        return Technician.builder().id(UUID.randomUUID()).personId(PERSON_ID).build();
    }

    @Test
    @DisplayName("Replica hit maps identity, emails, and phone numbers")
    void replicaHitMapsIdentity() {
        when(technicianRepository.findFirstByShopIdAndPersonId(LOCATION_ID, PERSON_ID))
                .thenReturn(Optional.of(technician()));
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
        when(technicianRepository.findFirstByShopIdAndPersonId(LOCATION_ID, PERSON_ID))
                .thenReturn(Optional.of(technician()));
        when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.empty());

        PersonDTO person = service.getTechnicianPerson(LOCATION_ID, PERSON_ID);

        assertThat(person.getId()).isEqualTo(PERSON_ID);
        assertThat(person.getFirstName()).isNull();
    }

    @Test
    @DisplayName("404 when no technician links the person to the location")
    void notFoundWhenNoTechnician() {
        when(technicianRepository.findFirstByShopIdAndPersonId(LOCATION_ID, PERSON_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTechnicianPerson(LOCATION_ID, PERSON_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}

package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.entity.ExtPersonReplica;
import com.positivity.customer.internal.repository.ExtPersonReplicaRepository;
import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the replica-backed person directory (ADR-0044 §6, #877).
 */
class PersonDirectoryServiceTest {

    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ExtPersonReplicaRepository replicaRepository = mock(ExtPersonReplicaRepository.class);
    private final PeopleContactCommandEmitter commandEmitter = mock(PeopleContactCommandEmitter.class);

    private PersonDirectoryService service;

    @BeforeEach
    void setUp() {
        service = new PersonDirectoryService(new ObjectMapper(), replicaRepository, commandEmitter);
        when(replicaRepository.findByPrimaryEmailIgnoreCase(anyString())).thenReturn(List.of());
        when(replicaRepository.findByContactPointsContaining(anyString())).thenReturn(List.of());
        when(replicaRepository.findByLastNameIgnoreCase(anyString())).thenReturn(List.of());
        when(replicaRepository.findByFirstNameIgnoreCase(anyString())).thenReturn(List.of());
    }

    private ExtPersonReplica replicaPerson() {
        return ExtPersonReplica.builder()
                .personId(PERSON_ID)
                .firstName("Jane")
                .lastName("Smith")
                .primaryEmail("jane@example.com")
                .contactPoints("[{\"contactType\":\"EMAIL\",\"value\":\"jane@example.com\",\"primary\":true},"
                        + "{\"contactType\":\"PHONE_MOBILE\",\"value\":\"+15551234567\",\"primary\":false}]")
                .aggregateVersion(1)
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Identity mapping surfaces typed contact points from the replica JSON")
    void fetchIdentities_mapsTypedContacts() {
        when(replicaRepository.findByPersonIdIn(List.of(PERSON_ID))).thenReturn(List.of(replicaPerson()));

        Map<UUID, PersonDirectoryService.PersonIdentity> identities = service.fetchPersonIdentities(List.of(PERSON_ID));

        PersonDirectoryService.PersonIdentity identity = identities.get(PERSON_ID);
        assertThat(identity.displayName()).isEqualTo("Jane Smith");
        assertThat(identity.emails()).containsExactly("jane@example.com");
        assertThat(identity.phones()).containsExactly("+15551234567");
    }

    @Test
    @DisplayName("Email match at the authority's weight resolves without creating")
    void resolve_matchesByEmail() {
        when(replicaRepository.findByPrimaryEmailIgnoreCase("jane@example.com")).thenReturn(List.of(replicaPerson()));

        UUID resolved = service.resolveOrCreatePersonId("jane@example.com", null, "Smith", "Jane");

        assertThat(resolved).isEqualTo(PERSON_ID);
        verify(commandEmitter, org.mockito.Mockito.never()).requestPersonUpsert(any());
    }

    @Test
    @DisplayName("Name-only signals stay below threshold and create via upsert command")
    void resolve_nameOnlyCreates() {
        when(replicaRepository.findByLastNameIgnoreCase("Smith")).thenReturn(List.of(replicaPerson()));
        when(replicaRepository.findByFirstNameIgnoreCase("Jane")).thenReturn(List.of(replicaPerson()));

        UUID resolved = service.resolveOrCreatePersonId("other@example.com", null, "Smith", "Jane");

        assertThat(resolved).isNotEqualTo(PERSON_ID);
        ArgumentCaptor<PersonUpsertRequestedV1> command = ArgumentCaptor.forClass(PersonUpsertRequestedV1.class);
        verify(commandEmitter).requestPersonUpsert(command.capture());
        assertThat(command.getValue().personId()).isEqualTo(resolved);
        assertThat(command.getValue().primaryEmail()).isEqualTo("other@example.com");
    }

    @Test
    @DisplayName("setContactPoints sends a full-typed upsert carrying the replica's names")
    void setContactPoints_sendsTypedUpsertWithNames() {
        when(replicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(replicaPerson()));

        service.setContactPoints(
                PERSON_ID, List.of(new PersonDirectoryService.ContactPointUpsert("EMAIL", "new@example.com", true)));

        ArgumentCaptor<PersonUpsertRequestedV1> command = ArgumentCaptor.forClass(PersonUpsertRequestedV1.class);
        verify(commandEmitter).requestPersonUpsert(command.capture());
        assertThat(command.getValue().firstName()).isEqualTo("Jane");
        assertThat(command.getValue().contactPoints()).hasSize(1);
        assertThat(command.getValue().contactPoints().get(0).value()).isEqualTo("new@example.com");
    }
}

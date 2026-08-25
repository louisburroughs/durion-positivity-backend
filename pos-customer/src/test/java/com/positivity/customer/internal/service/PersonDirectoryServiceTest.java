package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.entity.ExtPersonReplica;
import com.positivity.customer.internal.repository.ExtPersonReplicaRepository;
import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Weighted person matching against the {@code ext_people_contact_person} replica (ADR-0044 §6).
 *
 * <h2>Why this test exists</h2>
 *
 * The class sat at 44.8% branch — the weakest of Phase 3.6 — and {@code bestMatch}, the method
 * that decides whether a customer being saved <em>is</em> an existing person, had 13 of its 26
 * branches covered, with the phone re-verification lambda at 0 of 6. This is identity resolution:
 * a false match attaches one customer's CRM history to another person; a missed match forks a
 * second identity for someone who already exists. The weights (EMAIL 60 / PHONE 25 / LAST_NAME 10
 * / FIRST_NAME 5, threshold 30) mirror the authority's, and nothing was pinning any of them.
 */
@DisplayName("PersonDirectoryService — weighted matching and replica reads")
class PersonDirectoryServiceTest {

    private static final UUID ALICE = UUID.fromString("01980a58-0002-7000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("01980a58-0002-7000-8000-00000000000b");

    private final ExtPersonReplicaRepository replicaRepository = mock(ExtPersonReplicaRepository.class);
    private final PeopleContactCommandEmitter commandEmitter = mock(PeopleContactCommandEmitter.class);

    private PersonDirectoryService service;

    @BeforeEach
    void setUp() {
        service = new PersonDirectoryService(JsonMapper.builder().build(), replicaRepository, commandEmitter);
        when(replicaRepository.findByPrimaryEmailIgnoreCase(any())).thenReturn(List.of());
        when(replicaRepository.findByContactPointsContaining(any())).thenReturn(List.of());
        when(replicaRepository.findByLastNameIgnoreCase(any())).thenReturn(List.of());
        when(replicaRepository.findByFirstNameIgnoreCase(any())).thenReturn(List.of());
    }

    @Nested
    @DisplayName("resolveOrCreatePersonId")
    class ResolveOrCreate {

        @Test
        @DisplayName("an email match alone clears the threshold")
        void emailAloneMatches() {
            when(replicaRepository.findByPrimaryEmailIgnoreCase("alice@example.com"))
                    .thenReturn(List.of(person(ALICE)));

            UUID resolved = service.resolveOrCreatePersonId(" Alice@Example.COM ", null, null, null);

            // EMAIL is 60 against a threshold of 30 — and the lookup normalises case and
            // whitespace, so a re-typed address still finds the same person.
            assertThat(resolved).isEqualTo(ALICE);
            verifyNoInteractions(commandEmitter);
        }

        @Test
        @DisplayName("a phone match alone does not — 25 is below the threshold")
        void phoneAloneCreates() {
            withPhonePerson(ALICE, "PHONE_MOBILE", "+15550001");

            UUID resolved = service.resolveOrCreatePersonId(null, "+15550001", null, null);

            // A shared phone (a household, a front desk) must not merge two people.
            assertThat(resolved).isNotEqualTo(ALICE);
            verify(commandEmitter).requestPersonUpsert(any());
        }

        @Test
        @DisplayName("phone plus last name clears the threshold together")
        void phonePlusLastNameMatches() {
            withPhonePerson(ALICE, "PHONE_MOBILE", "+15550001");
            when(replicaRepository.findByLastNameIgnoreCase("Smith")).thenReturn(List.of(person(ALICE)));

            assertThat(service.resolveOrCreatePersonId(null, "+15550001", "Smith", null))
                    .isEqualTo(ALICE);
        }

        @Test
        @DisplayName("names alone never reach the threshold")
        void namesAloneCreate() {
            when(replicaRepository.findByLastNameIgnoreCase("Smith")).thenReturn(List.of(person(ALICE)));
            when(replicaRepository.findByFirstNameIgnoreCase("Alice")).thenReturn(List.of(person(ALICE)));

            // 10 + 5 = 15: two people sharing a name is ordinary, and merging on it would be
            // wrong far more often than right.
            assertThat(service.resolveOrCreatePersonId(null, null, "Smith", "Alice"))
                    .isNotEqualTo(ALICE);
        }

        @Test
        @DisplayName("a JSON-containment phone candidate is re-verified as a typed PHONE point")
        void phoneContainmentIsReVerified() {
            // The digits appear in Alice's contact JSON — but as an EMAIL value, not a typed
            // phone. The DB containment query cannot tell those apart; the code must.
            withPhonePerson(ALICE, "EMAIL", "+15550001");
            when(replicaRepository.findByLastNameIgnoreCase("Smith")).thenReturn(List.of(person(ALICE)));

            assertThat(service.resolveOrCreatePersonId(null, "+15550001", "Smith", null))
                    .isNotEqualTo(ALICE);
        }

        @Test
        @DisplayName("a tie on score resolves deterministically, not by map order")
        void tieBreaksDeterministically() {
            when(replicaRepository.findByPrimaryEmailIgnoreCase("shared@example.com"))
                    .thenReturn(List.of(person(ALICE), person(BOB)));

            // Same 60 for both: the same request must resolve to the same person every time it
            // is made, so the tie-break is the UUID's string order rather than hash order.
            UUID first = service.resolveOrCreatePersonId("shared@example.com", null, null, null);
            UUID second = service.resolveOrCreatePersonId("shared@example.com", null, null, null);
            assertThat(first).isEqualTo(second).isEqualTo(BOB);
        }

        @Test
        @DisplayName("no match queues an upsert carrying exactly what was provided")
        void noMatchQueuesUpsert() {
            UUID resolved = service.resolveOrCreatePersonId("new@example.com", "+15550009", "New", "Nancy");

            ArgumentCaptor<PersonUpsertRequestedV1> captor = ArgumentCaptor.forClass(PersonUpsertRequestedV1.class);
            verify(commandEmitter).requestPersonUpsert(captor.capture());
            PersonUpsertRequestedV1 command = captor.getValue();
            assertThat(command.personId()).isEqualTo(resolved);
            assertThat(command.firstName()).isEqualTo("Nancy");
            assertThat(command.lastName()).isEqualTo("New");
            assertThat(command.primaryEmail()).isEqualTo("new@example.com");
            assertThat(command.workPhones()).containsExactly("+15550009");
        }

        @Test
        @DisplayName("blank inputs query nothing and a blank phone upserts an empty phone list")
        void blankInputsQueryNothing() {
            service.resolveOrCreatePersonId(" ", " ", " ", " ");

            verify(replicaRepository, never()).findByPrimaryEmailIgnoreCase(any());
            verify(replicaRepository, never()).findByContactPointsContaining(any());
            verify(replicaRepository, never()).findByLastNameIgnoreCase(any());
            verify(replicaRepository, never()).findByFirstNameIgnoreCase(any());
            ArgumentCaptor<PersonUpsertRequestedV1> captor = ArgumentCaptor.forClass(PersonUpsertRequestedV1.class);
            verify(commandEmitter).requestPersonUpsert(captor.capture());
            assertThat(captor.getValue().workPhones()).isEmpty();
        }
    }

    @Nested
    @DisplayName("contact-point upserts")
    class ContactPoints {

        @Test
        @DisplayName("a replicated person's names travel with the upsert so it cannot blank them")
        void replicatedNamesTravel() {
            ExtPersonReplica existing = person(ALICE);
            existing.setFirstName("Alice");
            existing.setLastName("Smith");
            existing.setPreferredName("Ali");
            when(replicaRepository.findById(ALICE)).thenReturn(java.util.Optional.of(existing));

            service.setContactPoints(
                    ALICE, List.of(new PersonDirectoryService.ContactPointUpsert("EMAIL", "a@example.com", true)));

            ArgumentCaptor<PersonUpsertRequestedV1> captor = ArgumentCaptor.forClass(PersonUpsertRequestedV1.class);
            verify(commandEmitter).requestPersonUpsert(captor.capture());
            assertThat(captor.getValue().firstName()).isEqualTo("Alice");
            assertThat(captor.getValue().preferredName()).isEqualTo("Ali");
            assertThat(captor.getValue().contactPoints()).hasSize(1);
        }

        @Test
        @DisplayName("a not-yet-replicated person upserts with null names rather than failing")
        void unreplicatedPersonKeepsNullNames() {
            when(replicaRepository.findById(ALICE)).thenReturn(java.util.Optional.empty());

            service.setContactPoints(
                    ALICE, List.of(new PersonDirectoryService.ContactPointUpsert("EMAIL", "a@example.com", true)));

            ArgumentCaptor<PersonUpsertRequestedV1> captor = ArgumentCaptor.forClass(PersonUpsertRequestedV1.class);
            verify(commandEmitter).requestPersonUpsert(captor.capture());
            // The upsert is keyed by personId; the authority keeps its own names until this
            // person's fact replicates.
            assertThat(captor.getValue().firstName()).isNull();
            assertThat(captor.getValue().lastName()).isNull();
        }
    }

    @Nested
    @DisplayName("replica reads")
    class ReplicaReads {

        @Test
        @DisplayName("an empty id set answers empty without a query")
        void emptyIdsSkipTheQuery() {
            assertThat(service.fetchPersonIdentities(List.of())).isEqualTo(Map.of());
            verify(replicaRepository, never()).findByPersonIdIn(any());
        }

        @Test
        @DisplayName("a batch lookup maps the ids it finds and leaves unknown ids absent")
        void batchLookupLeavesUnknownIdsAbsent() {
            ExtPersonReplica known = person(ALICE);
            known.setFirstName("Alice");
            when(replicaRepository.findByPersonIdIn(List.of(ALICE, BOB))).thenReturn(List.of(known));

            Map<UUID, PersonDirectoryService.PersonIdentity> identities =
                    service.fetchPersonIdentities(List.of(ALICE, BOB));

            // Absent, not null-valued: callers distinguish "not replicated yet" by containsKey.
            assertThat(identities).containsOnlyKeys(ALICE);
            assertThat(identities.get(ALICE).firstName()).isEqualTo("Alice");
        }

        @Test
        @DisplayName("a null or blank search answers empty without a query")
        void blankSearchAnswersEmpty() {
            assertThat(service.searchPersons(null)).isEmpty();
            assertThat(service.searchPersons("   ")).isEmpty();
            verify(replicaRepository, never()).search(any());
        }

        @Test
        @DisplayName("a search hit maps the replica row, and unparsable contact JSON degrades to none")
        void searchMapsRowsAndSurvivesBadJson() {
            ExtPersonReplica garbled = person(ALICE);
            garbled.setFirstName("Alice");
            garbled.setPrimaryEmail("alice@example.com");
            garbled.setContactPoints("{not json");
            when(replicaRepository.search("ali")).thenReturn(List.of(garbled));

            List<PersonDirectoryService.PersonIdentity> results = service.searchPersons(" ali ");

            // A corrupt contact_points blob costs the contact points, not the person.
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().firstName()).isEqualTo("Alice");
            assertThat(results.getFirst().contactPoints()).isEmpty();
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static ExtPersonReplica person(UUID personId) {
        ExtPersonReplica person = new ExtPersonReplica();
        person.setPersonId(personId);
        return person;
    }

    private void withPhonePerson(UUID personId, String contactType, String value) {
        ExtPersonReplica person = person(personId);
        person.setContactPoints(
                "[{\"contactType\":\"" + contactType + "\",\"value\":\"" + value + "\",\"primary\":true}]");
        when(replicaRepository.findByContactPointsContaining(value)).thenReturn(List.of(person));
    }
}

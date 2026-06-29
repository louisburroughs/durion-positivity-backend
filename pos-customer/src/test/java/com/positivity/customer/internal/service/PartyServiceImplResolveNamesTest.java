package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.client.PeopleClient;
import com.positivity.customer.internal.dto.PartyNameRef;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PartyServiceImpl#resolveNames}: commercial display/legal fallback, person
 * names resolved in a single batched pos-people call, dedupe, and omission of unresolvable ids.
 */
@ExtendWith(MockitoExtension.class)
class PartyServiceImplResolveNamesTest {

    @Mock
    private java.time.Clock clock;

    @Mock
    private CommercialPartyRepository partyRepository;

    @Mock
    private PersonPartyRepository personPartyRepository;

    @Mock
    private com.positivity.customer.internal.repository.PartyRelationshipRepository partyRelationshipRepository;

    @Mock
    private org.springframework.cache.CacheManager cacheManager;

    @Mock
    private PeopleClient peopleClient;

    @Mock
    private com.positivity.customer.internal.client.VehicleInventoryClient vehicleInventoryClient;

    @InjectMocks
    private PartyServiceImpl service;

    private static CommercialParty commercial(UUID partyId, String displayName, String legalName) {
        CommercialParty p = new CommercialParty();
        p.setPartyId(partyId);
        p.setDisplayName(displayName);
        p.setLegalName(legalName);
        return p;
    }

    private static PersonParty person(UUID partyId, UUID personId) {
        PersonParty p = new PersonParty();
        p.setPartyId(partyId);
        p.setPersonId(personId);
        return p;
    }

    private static PeopleClient.PersonIdentity identity(UUID personId, String first, String last) {
        return new PeopleClient.PersonIdentity(personId, first, last, null, List.of());
    }

    private static PeopleClient.PersonIdentity identityWithPhones(
            UUID personId, String first, String last, List<PeopleClient.ContactPoint> contactPoints) {
        return new PeopleClient.PersonIdentity(personId, first, last, null, contactPoints);
    }

    @Test
    void emptyInput_returnsEmpty_withoutTouchingRepositories() {
        assertThat(service.resolveNames(List.of())).isEmpty();
        verify(partyRepository, never()).findAllById(anyCollection());
        verify(personPartyRepository, never()).findAllById(anyCollection());
    }

    @Test
    void commercial_prefersDisplayName_fallsBackToLegalName() {
        UUID withDisplay = UUID.fromString("11111111-0000-0000-0000-000000000001");
        UUID legalOnly = UUID.fromString("11111111-0000-0000-0000-000000000002");
        when(partyRepository.findAllById(List.of(withDisplay, legalOnly)))
                .thenReturn(List.of(
                        commercial(withDisplay, "Acme Supply", "Acme Industrial Supply LLC"),
                        commercial(legalOnly, "  ", "Legal Only Inc")));
        when(personPartyRepository.findAllById(List.of(withDisplay, legalOnly))).thenReturn(List.of());

        List<PartyNameRef> result = service.resolveNames(List.of(withDisplay, legalOnly));

        assertThat(result)
                .containsExactlyInAnyOrder(
                        new PartyNameRef(withDisplay, "Acme Supply", null),
                        new PartyNameRef(legalOnly, "Legal Only Inc", null));
        verify(peopleClient, never()).fetchPersonIdentitiesQuietly(anyCollection());
    }

    @Test
    void person_resolvedViaSingleBatchedPeopleCall() {
        UUID partyA = UUID.fromString("22222222-0000-0000-0000-000000000001");
        UUID partyB = UUID.fromString("22222222-0000-0000-0000-000000000002");
        UUID personA = UUID.fromString("33333333-0000-0000-0000-000000000001");
        UUID personB = UUID.fromString("33333333-0000-0000-0000-000000000002");
        when(partyRepository.findAllById(List.of(partyA, partyB))).thenReturn(List.of());
        when(personPartyRepository.findAllById(List.of(partyA, partyB)))
                .thenReturn(List.of(person(partyA, personA), person(partyB, personB)));
        when(peopleClient.fetchPersonIdentitiesQuietly(anyCollection()))
                .thenReturn(
                        Map.of(personA, identity(personA, "John", "Smith"), personB, identity(personB, "Jane", "Doe")));

        List<PartyNameRef> result = service.resolveNames(List.of(partyA, partyB));

        assertThat(result)
                .containsExactlyInAnyOrder(
                        new PartyNameRef(partyA, "John Smith", null), new PartyNameRef(partyB, "Jane Doe", null));
        // single batch call, not one per person
        verify(peopleClient).fetchPersonIdentitiesQuietly(anyCollection());
    }

    @Test
    void person_picksPrimaryPhoneFromContactPoints() {
        UUID partyA = UUID.fromString("66666666-0000-0000-0000-000000000001");
        UUID personA = UUID.fromString("77777777-0000-0000-0000-000000000001");
        when(partyRepository.findAllById(List.of(partyA))).thenReturn(List.of());
        when(personPartyRepository.findAllById(List.of(partyA))).thenReturn(List.of(person(partyA, personA)));
        when(peopleClient.fetchPersonIdentitiesQuietly(anyCollection()))
                .thenReturn(Map.of(
                        personA,
                        identityWithPhones(
                                personA,
                                "John",
                                "Smith",
                                List.of(
                                        new PeopleClient.ContactPoint("EMAIL", "j@x.com", true),
                                        new PeopleClient.ContactPoint("PHONE_HOME", "+1-555-0001", false),
                                        new PeopleClient.ContactPoint("PHONE_MOBILE", "+1-555-0002", true)))));

        List<PartyNameRef> result = service.resolveNames(List.of(partyA));

        assertThat(result).containsExactly(new PartyNameRef(partyA, "John Smith", "+1-555-0002"));
    }

    @Test
    void dedupesIds_andOmitsUnresolvable() {
        UUID known = UUID.fromString("44444444-0000-0000-0000-000000000001");
        UUID unknown = UUID.fromString("44444444-0000-0000-0000-000000000002");
        UUID personNoIdentity = UUID.fromString("44444444-0000-0000-0000-000000000003");
        UUID personId = UUID.fromString("55555555-0000-0000-0000-000000000003");
        // duplicate `known` in the input collapses to one
        when(partyRepository.findAllById(List.of(known, unknown, personNoIdentity)))
                .thenReturn(List.of(commercial(known, "Known Co", "Known Co LLC")));
        when(personPartyRepository.findAllById(List.of(known, unknown, personNoIdentity)))
                .thenReturn(List.of(person(personNoIdentity, personId)));
        when(peopleClient.fetchPersonIdentitiesQuietly(anyCollection())).thenReturn(Map.of());

        List<PartyNameRef> result = service.resolveNames(List.of(known, known, unknown, personNoIdentity));

        assertThat(result).containsExactly(new PartyNameRef(known, "Known Co", null));
    }
}

package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.config.OutboxEventWriter;
import com.positivity.customer.internal.entity.BillingRulesEmbeddable;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.domainevents.customer.CustomerPersonIdentityUpdatedV1;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link CustomerFactPublisher} (ADR-0044 §6, #889).
 */
class CustomerFactPublisherTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);

    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);
    private final PersonDirectoryService personDirectoryService = mock(PersonDirectoryService.class);
    private final PersonPartyRepository personPartyRepository = mock(PersonPartyRepository.class);
    private final PartyRelationshipRepository partyRelationshipRepository = mock(PartyRelationshipRepository.class);

    private CustomerFactPublisher publisher;

    @BeforeEach
    void setUp() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        publisher = new CustomerFactPublisher(
                writerProvider,
                TEST_CLOCK,
                personDirectoryService,
                personPartyRepository,
                partyRelationshipRepository,
                "customer.events.v1");
    }

    private CommercialParty commercialParty(AccountStatus status, Boolean creditHold) {
        CommercialParty party = new CommercialParty();
        party.setPartyId(UUID.randomUUID());
        party.setCustomerNumber("CUST-001");
        party.setLegalName("Acme Corp");
        party.setStatus(status);
        if (creditHold != null) {
            BillingRulesEmbeddable rules = new BillingRulesEmbeddable();
            rules.setCreditHold(creditHold);
            party.setBillingRules(rules);
        }
        return party;
    }

    @Test
    @DisplayName("Commercial party fact carries the requirements-met verdict (credit hold blocks)")
    void commercialPartyFact() {
        CommercialParty party = commercialParty(AccountStatus.ACTIVE, true);

        publisher.partyChanged(party);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("customer.events.v1"), captor.capture());
        CustomerPartyUpdatedV1 fact = (CustomerPartyUpdatedV1) captor.getValue().payload();
        assertThat(captor.getValue().eventType()).isEqualTo(CustomerPartyUpdatedV1.EVENT_TYPE);
        assertThat(fact.partyId()).isEqualTo(party.getPartyId());
        assertThat(fact.partyType()).isEqualTo("COMMERCIAL");
        assertThat(fact.displayName()).isEqualTo("Acme Corp");
        assertThat(fact.legalName()).isEqualTo("Acme Corp");
        assertThat(fact.requirementsMet()).isFalse();
        assertThat(fact.creditHold()).isTrue();
        assertThat(fact.personId()).isNull();
    }

    @Test
    @DisplayName("Active commercial party without credit hold meets requirements")
    void requirementsMetWhenNoHold() {
        publisher.partyChanged(commercialParty(AccountStatus.ACTIVE, false));

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("customer.events.v1"), captor.capture());
        assertThat(((CustomerPartyUpdatedV1) captor.getValue().payload()).requirementsMet())
                .isTrue();
    }

    @Test
    @DisplayName("Person party emits the party fact plus its person-identity fact")
    void personPartyEmitsBothFacts() {
        UUID personId = UUID.randomUUID();
        PersonParty person = new PersonParty();
        person.setPersonPartyId(UUID.randomUUID());
        person.setPersonId(personId);
        person.setCustomerNumber("CUST-PER-1");
        person.setStatus(AccountStatus.ACTIVE);
        when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(personId)))
                .thenReturn(Map.of(
                        personId,
                        new PersonDirectoryService.PersonIdentity(personId, "Jane", "Smith", null, List.of())));
        when(personPartyRepository.findByPersonId(personId)).thenReturn(Optional.of(person));
        when(partyRelationshipRepository.findActiveByToPersonPartyId(eq(person.getPersonPartyId()), any()))
                .thenReturn(List.of());

        publisher.partyChanged(person);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer, times(2)).publish(eq("customer.events.v1"), captor.capture());
        CustomerPartyUpdatedV1 partyFact =
                (CustomerPartyUpdatedV1) captor.getAllValues().get(0).payload();
        assertThat(partyFact.partyType()).isEqualTo("PERSON");
        assertThat(partyFact.personId()).isEqualTo(personId);
        assertThat(partyFact.displayName()).isEqualTo("Jane Smith");
        assertThat(partyFact.requirementsMet()).isTrue();

        CustomerPersonIdentityUpdatedV1 identityFact =
                (CustomerPersonIdentityUpdatedV1) captor.getAllValues().get(1).payload();
        assertThat(identityFact.personId()).isEqualTo(personId);
        assertThat(identityFact.individualCustomer()).isTrue();
        assertThat(identityFact.commercialContact()).isFalse();
        assertThat(identityFact.commercialAccountCount()).isZero();
    }

    @Test
    @DisplayName("Person party delete emits the deleted fact and clears the identity flags")
    void personPartyDelete() {
        UUID personId = UUID.randomUUID();
        PersonParty person = new PersonParty();
        person.setPersonPartyId(UUID.randomUUID());
        person.setPersonId(personId);
        person.setStatus(AccountStatus.ACTIVE);
        when(personPartyRepository.findByPersonId(personId)).thenReturn(Optional.empty());

        publisher.partyDeleted(person);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer, times(2)).publish(eq("customer.events.v1"), captor.capture());
        CustomerPartyDeletedV1 deleted =
                (CustomerPartyDeletedV1) captor.getAllValues().get(0).payload();
        assertThat(deleted.partyId()).isEqualTo(person.getPersonPartyId());
        assertThat(deleted.personId()).isEqualTo(personId);

        CustomerPersonIdentityUpdatedV1 identityFact =
                (CustomerPersonIdentityUpdatedV1) captor.getAllValues().get(1).payload();
        assertThat(identityFact.individualCustomer()).isFalse();
        assertThat(identityFact.personPartyId()).isNull();
        assertThat(identityFact.commercialAccountCount()).isZero();
    }

    @Test
    @DisplayName("All methods no-op when Kafka publishing is disabled")
    void noopWhenWriterAbsent() {
        when(writerProvider.getIfAvailable()).thenReturn(null);

        publisher.partyChanged(commercialParty(AccountStatus.ACTIVE, null));
        publisher.personIdentityChanged(UUID.randomUUID());
        publisher.personReplicaChanged(UUID.randomUUID());

        verify(writer, never()).publish(any(), any());
    }

    @Test
    @DisplayName("personReplicaChanged re-emits the party fact only for persons with a customer record")
    void personReplicaChanged() {
        UUID personId = UUID.randomUUID();
        when(personPartyRepository.findByPersonId(personId)).thenReturn(Optional.empty());

        publisher.personReplicaChanged(personId);

        verify(writer, never()).publish(any(), any());
    }
}

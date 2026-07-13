package com.positivity.customer.internal.service;

import com.positivity.customer.internal.config.OutboxEventWriter;
import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.domainevents.customer.CustomerPersonIdentityUpdatedV1;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Publishes customer-owned facts to {@code customer.events.v1} through the transactional outbox
 * (ADR-0044 §6, issue #889 Phase 4.1).
 *
 * <p>Every party mutation site calls {@link #partyChanged}/{@link #partyDeleted} inside its
 * business transaction; commercial-relationship mutations call {@link #personIdentityChanged}.
 * When Kafka publishing is disabled ({@code pos.customer.kafka.enabled=false}) the outbox writer
 * bean is absent and every method is a no-op, so callers never need their own guard.
 *
 * <p>The envelope's {@code aggregateVersion} is the emission timestamp in epoch millis — parties
 * carry no optimistic-lock version, and millisecond last-writer-wins ordering matches the
 * established pattern of this module's billing-rules fact.
 */
@Slf4j
@Component
public class CustomerFactPublisher {

    private static final String SOURCE = "pos-customer";

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final Clock clock;
    private final PersonDirectoryService personDirectoryService;
    private final PersonPartyRepository personPartyRepository;
    private final PartyRelationshipRepository partyRelationshipRepository;
    private final String eventsTopic;

    public CustomerFactPublisher(
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            Clock clock,
            PersonDirectoryService personDirectoryService,
            PersonPartyRepository personPartyRepository,
            PartyRelationshipRepository partyRelationshipRepository,
            // Same property ManifestPublisher scans event_outbox by, so an override can never
            // desync the outbox rows from the manifest computation.
            @Value("${pos.customer.kafka.events-topic:customer.events.v1}") String eventsTopic) {
        this.outboxEventWriter = outboxEventWriter;
        this.clock = clock;
        this.personDirectoryService = personDirectoryService;
        this.personPartyRepository = personPartyRepository;
        this.partyRelationshipRepository = partyRelationshipRepository;
        this.eventsTopic = eventsTopic;
    }

    /**
     * The configured fact topic — exposed so other in-module producers (the billing-rules fact in
     * {@code PartyServiceImpl}) publish to the same topic the manifest computation scans.
     */
    public @NonNull String eventsTopic() {
        return eventsTopic;
    }

    /**
     * Emit {@code customer.party.updated} for a just-saved party. Person parties additionally
     * re-emit their {@code customer.person-identity.updated} fact, because creating or updating
     * the individual-customer record changes the person's CRM standing.
     */
    public void partyChanged(@NonNull AbstractParty party) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        publishPartyUpdated(writer, party);
        if (party instanceof PersonParty person && person.getPersonId() != null) {
            publishPersonIdentity(writer, person.getPersonId());
        }
    }

    /**
     * Emit {@code customer.party.deleted} for a party removed in the current transaction. Call
     * with the entity loaded <em>before</em> the delete so the person linkage is still available;
     * for person parties the person-identity fact is re-emitted with the individual-customer flag
     * cleared.
     */
    public void partyDeleted(@NonNull AbstractParty party) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        UUID personId = party instanceof PersonParty person ? person.getPersonId() : null;
        CustomerPartyDeletedV1 payload = new CustomerPartyDeletedV1(party.getPartyId(), personId);
        publish(writer, CustomerPartyDeletedV1.EVENT_TYPE, party.getPartyId(), payload);
        if (personId != null) {
            publishPersonIdentity(writer, personId);
        }
    }

    /**
     * Emit {@code customer.person-identity.updated} for a person whose commercial relationships
     * changed (relationship created, deactivated, or reassigned by an account merge).
     */
    public void personIdentityChanged(@NonNull UUID personId) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        publishPersonIdentity(writer, personId);
    }

    /**
     * Re-emit the party fact of the person's individual-customer record after the local
     * people-contact replica changed — the fact's {@code displayName} is materialized from that
     * replica, so name changes must propagate to {@code ext_customer_party} consumers.
     */
    public void personReplicaChanged(@NonNull UUID personId) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        personPartyRepository.findByPersonId(personId).ifPresent(person -> publishPartyUpdated(writer, person));
    }

    private void publishPartyUpdated(@NonNull OutboxEventWriter writer, @NonNull AbstractParty party) {
        String displayName = null;
        String legalName = null;
        UUID personId = null;
        UUID parentPartyId = null;
        Boolean creditHold = null;
        String partyType;

        if (party instanceof CommercialParty commercial) {
            partyType = commercial.getPartyType() != null
                    ? commercial.getPartyType().name()
                    : "COMMERCIAL";
            legalName = commercial.getLegalName();
            displayName = StringUtils.hasText(commercial.getDisplayName()) ? commercial.getDisplayName() : legalName;
            parentPartyId = commercial.getParentParty() != null
                    ? commercial.getParentParty().getPartyId()
                    : null;
            creditHold = commercial.getBillingRules() != null
                    ? commercial.getBillingRules().getCreditHold()
                    : null;
        } else if (party instanceof PersonParty person) {
            partyType = "PERSON";
            personId = person.getPersonId();
            displayName = resolvePersonDisplayName(personId);
        } else {
            partyType = party.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
        }

        CustomerPartyUpdatedV1 payload = new CustomerPartyUpdatedV1(
                party.getPartyId(),
                partyType,
                party.getCustomerNumber(),
                displayName,
                legalName,
                personId,
                party.getStatus() != null ? party.getStatus().name() : "ACTIVE",
                party.getTier() != null ? party.getTier().name() : null,
                CustomerRequirementsService.requirementsMet(party),
                creditHold,
                parentPartyId);
        publish(writer, CustomerPartyUpdatedV1.EVENT_TYPE, party.getPartyId(), payload);
    }

    private void publishPersonIdentity(@NonNull OutboxEventWriter writer, @NonNull UUID personId) {
        Optional<PersonParty> person = personPartyRepository.findByPersonId(personId);
        UUID personPartyId = person.map(PersonParty::getPersonPartyId).orElse(null);
        long commercialAccountCount = personPartyId == null
                ? 0
                : partyRelationshipRepository.findActiveByToPersonPartyId(personPartyId, LocalDate.now(clock)).stream()
                        .map(rel -> rel.getFromParty().getPartyId())
                        .distinct()
                        .count();
        CustomerPersonIdentityUpdatedV1 payload = new CustomerPersonIdentityUpdatedV1(
                personId, personPartyId, person.isPresent(), commercialAccountCount > 0, (int) commercialAccountCount);
        publish(writer, CustomerPersonIdentityUpdatedV1.EVENT_TYPE, personId, payload);
    }

    private @Nullable String resolvePersonDisplayName(@Nullable UUID personId) {
        if (personId == null) {
            return null;
        }
        PersonDirectoryService.PersonIdentity identity = personDirectoryService
                .fetchPersonIdentitiesQuietly(Set.of(personId))
                .get(personId);
        if (identity == null) {
            return null;
        }
        String displayName = identity.displayName();
        return displayName.isBlank() ? null : displayName;
    }

    private void publish(
            @NonNull OutboxEventWriter writer, @NonNull String eventType, @NonNull UUID aggregateId, Object payload) {
        DomainEventEnvelope<Object> envelope = DomainEventEnvelope.of(
                eventType, 1, aggregateId, Instant.now(clock).toEpochMilli(), SOURCE, null, null, payload, clock);
        writer.publish(eventsTopic, envelope);
        log.debug("Queued {} for aggregate {}", eventType, aggregateId);
    }

    /** Distinct person ids on a batch of relationships — merge helper for bulk reassignment. */
    public static @NonNull List<UUID> affectedPersonIds(
            @NonNull List<com.positivity.customer.internal.entity.PartyRelationship> relationships) {
        return relationships.stream()
                .map(rel -> rel.getToPerson().getPersonId())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }
}

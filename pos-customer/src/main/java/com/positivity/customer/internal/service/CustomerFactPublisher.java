package com.positivity.customer.internal.service;

import com.positivity.customer.internal.config.OutboxEventWriter;
import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.domainevents.AggregateTouch;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.customer.CustomerConsentDecisionChangedV1;
import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyTagChangedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.domainevents.customer.CustomerPersonIdentityUpdatedV1;
import com.positivity.domainevents.customer.CustomerRedemptionRecordedV1;
import com.positivity.domainevents.customer.CustomerSegmentChangedV1;
import com.positivity.domainevents.customer.CustomerSegmentResolvedV1;
import com.positivity.domainevents.customer.CustomerSuppressionChangedV1;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * <p>{@code AbstractParty} carries a JPA {@code @Version} (#1486), and {@code customer.party.updated}
 * / {@code customer.party.deleted} — the two facts pos-order and pos-accounting version-guard —
 * publish that counter as {@code aggregateVersion}: it strictly increments on every committed
 * mutation, so unlike the retired {@code Instant.now(clock)}-stamped emission timestamp, two
 * mutations landing in the same millisecond can never tie. Migration V30 seeded the column from
 * wall-clock millis at migration time (not {@code updated_at} — see the migration header for why),
 * so the published sequence continues above every version consumers already hold. {@link
 * #publishPartyUpdated} flushes the pending mutation before reading the version, so the increment
 * Hibernate is about to apply is already reflected in the emitted fact; {@link #partyDeleted}
 * publishes {@code version + 1} from the entity loaded before the delete (the tombstone pattern —
 * no flush needed, since the row is being removed, not re-saved). A consumer on these two facts
 * skips a fact only when the version it already holds is strictly greater than the incoming one —
 * an equal version applies, both because it is an idempotent no-op for live traffic and because it
 * is what would let a future regenerate-from-state replay repair a replica that holds the version
 * number but wrong or missing data.
 *
 * <p>Every other fact this class publishes (person-identity, tag-changed, suppression, redemption,
 * consent-decision, segment-changed, segment-resolved) stays on the {@code Instant.now(clock)}
 * emission-millis scheme: none of them is about the {@code Party} aggregate's own version (several
 * describe a different aggregate entirely — a segment, a suppressed address, a resolved query), and
 * a repo-wide survey (#1486 follow-up) found no consumer anywhere that version-guards any of them,
 * so there is no strictly-advancing contract to violate and nothing that would benefit from moving
 * off wall-clock millis yet.
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
    private final EntityManager entityManager;

    public CustomerFactPublisher(
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            Clock clock,
            PersonDirectoryService personDirectoryService,
            PersonPartyRepository personPartyRepository,
            PartyRelationshipRepository partyRelationshipRepository,
            // Same property ManifestPublisher scans event_outbox by, so an override can never
            // desync the outbox rows from the manifest computation.
            @Value("${pos.customer.kafka.events-topic:customer.events.v1}") String eventsTopic,
            EntityManager entityManager) {
        this.outboxEventWriter = outboxEventWriter;
        this.clock = clock;
        this.personDirectoryService = personDirectoryService;
        this.personPartyRepository = personPartyRepository;
        this.partyRelationshipRepository = partyRelationshipRepository;
        this.eventsTopic = eventsTopic;
        this.entityManager = entityManager;
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
     *
     * <p>Versioned as {@code version + 1} from the entity loaded before the delete (#1486) — one
     * past every fact this party has ever published, the same tombstone pattern pos-catalog and
     * pos-location use. No flush here: the row is about to be deleted, not re-saved, so there is
     * no pending {@code @Version} increment to pick up.
     */
    public void partyDeleted(@NonNull AbstractParty party) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        UUID personId = party instanceof PersonParty person ? person.getPersonId() : null;
        CustomerPartyDeletedV1 payload = new CustomerPartyDeletedV1(party.getPartyId(), personId);
        publish(
                writer,
                CustomerPartyDeletedV1.EVENT_TYPE,
                CustomerPartyDeletedV1.SCHEMA_VERSION,
                party.getPartyId(),
                payload,
                party.getVersion() + 1);
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
     *
     * <p>The replica that actually changed ({@code ext_people_contact_person}) is a side table the
     * {@code PersonParty} row has no mapped relationship to, so nothing here would otherwise dirty
     * the party row — the publisher's flush would have no pending {@code @Version} increment to
     * apply, and the fact would carry a changed {@code displayName} under an unchanged
     * {@code aggregateVersion} (#1486). Bump {@code updatedAt} and save before publishing, the same
     * convention pos-location's {@code addParentInternal} follows.
     */
    public void personReplicaChanged(@NonNull UUID personId) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        personPartyRepository.findByPersonId(personId).ifPresent(person -> {
            person.setUpdatedAt(AggregateTouch.monotonicUpdatedAt(person.getUpdatedAt(), clock));
            personPartyRepository.save(person);
            publishPartyUpdated(writer, person);
        });
    }

    /**
     * Emit {@code customer.party.tag-changed} for a tag attach or removal (Story #1136), so
     * marketing audience replicas track CRM classification without reading the CRM tables.
     */
    public void partyTagChanged(
            @NonNull UUID partyId,
            @NonNull UUID tagId,
            @NonNull String tagName,
            boolean assigned,
            @Nullable String source) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        CustomerPartyTagChangedV1 payload = new CustomerPartyTagChangedV1(partyId, tagId, tagName, assigned, source);
        publish(
                writer,
                CustomerPartyTagChangedV1.EVENT_TYPE,
                CustomerPartyTagChangedV1.SCHEMA_VERSION,
                partyId,
                payload);
    }

    /**
     * Emit {@code customer.suppression.changed} so pos-marketing can keep a local suppression
     * replica and check it per recipient without a synchronous CRM call (Story #1140).
     *
     * <p>Suppression has no UUID aggregate of its own — the subject is an address. The
     * aggregate id is therefore derived deterministically from {@code channel:addressHash},
     * which keeps per-address ordering intact and lets the topic compact.
     */
    public void suppressionChanged(
            @NonNull String channel,
            @NonNull String addressHash,
            @Nullable UUID partyId,
            boolean suppressed,
            @Nullable String reason) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        UUID aggregateId = UUID.nameUUIDFromBytes((channel + ":" + addressHash).getBytes(StandardCharsets.UTF_8));
        CustomerSuppressionChangedV1 payload =
                new CustomerSuppressionChangedV1(channel, addressHash, partyId, suppressed, reason);
        publish(
                writer,
                CustomerSuppressionChangedV1.EVENT_TYPE,
                CustomerSuppressionChangedV1.SCHEMA_VERSION,
                aggregateId,
                payload);
    }

    /**
     * Emit {@code customer.redemption.recorded} so pos-marketing can attribute conversions
     * back to a campaign (Story #1142). Emitted for every redemption, including those with no
     * {@code campaignCode} — marketing needs the non-attributed baseline for a campaign's lift
     * to mean anything.
     */
    public void redemptionRecorded(
            @NonNull UUID redemptionId,
            @NonNull UUID promotionId,
            @NonNull UUID customerId,
            @NonNull UUID workorderId,
            @NonNull String promotionCode,
            @Nullable String campaignCode,
            @Nullable BigDecimal discountAmount) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        CustomerRedemptionRecordedV1 payload = new CustomerRedemptionRecordedV1(
                redemptionId, promotionId, customerId, workorderId, promotionCode, campaignCode, discountAmount);
        publish(
                writer,
                CustomerRedemptionRecordedV1.EVENT_TYPE,
                CustomerRedemptionRecordedV1.SCHEMA_VERSION,
                customerId,
                payload);
    }

    /**
     * Emit {@code customer.consent.decision-changed} for one party and channel (Story #1138).
     *
     * <p>Publishes the <em>resolved</em> decision rather than the raw consent fields: the
     * commercial rule (decision O-2) belongs to this module, and re-deriving it in every
     * consumer would guarantee the copies drift.
     */
    public void consentDecisionChanged(
            @NonNull UUID partyId,
            @NonNull String channel,
            boolean allowed,
            @NonNull String reason,
            @Nullable UUID governingPartyId) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        CustomerConsentDecisionChangedV1 payload =
                new CustomerConsentDecisionChangedV1(partyId, channel, allowed, reason, governingPartyId);
        publish(
                writer,
                CustomerConsentDecisionChangedV1.EVENT_TYPE,
                CustomerConsentDecisionChangedV1.SCHEMA_VERSION,
                partyId,
                payload);
    }

    /** Emit {@code customer.segment.changed} — metadata only, never membership (Story #1137). */
    public void segmentChanged(
            @NonNull UUID segmentId,
            @Nullable String name,
            @Nullable String audienceType,
            @Nullable String type,
            boolean active,
            boolean deleted) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        CustomerSegmentChangedV1 payload =
                new CustomerSegmentChangedV1(segmentId, name, audienceType, type, active, deleted);
        publish(
                writer,
                CustomerSegmentChangedV1.EVENT_TYPE,
                CustomerSegmentChangedV1.SCHEMA_VERSION,
                segmentId,
                payload);
    }

    /**
     * Emit {@code customer.segment.resolved} in reply to a resolve command (Story #1137).
     *
     * <p>Dynamic membership is derived from party data and has no event boundary, so it cannot
     * be replicated continuously — a requester asks and this module answers.
     */
    public void segmentResolved(
            @NonNull UUID requestId,
            @NonNull UUID segmentId,
            @NonNull String audienceType,
            @NonNull List<UUID> partyIds,
            boolean truncated) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        CustomerSegmentResolvedV1 payload =
                new CustomerSegmentResolvedV1(requestId, segmentId, audienceType, partyIds, truncated);
        publish(
                writer,
                CustomerSegmentResolvedV1.EVENT_TYPE,
                CustomerSegmentResolvedV1.SCHEMA_VERSION,
                segmentId,
                payload);
    }

    /**
     * Flushed before reading {@code aggregateVersion} (#1486): the mutation that triggered this
     * call is still pending in the persistence context, and flushing here forces Hibernate to
     * apply the {@code @Version} increment so the envelope carries the version the row is about to
     * commit as, not the one it held before this write.
     */
    private void publishPartyUpdated(@NonNull OutboxEventWriter writer, @NonNull AbstractParty party) {
        entityManager.flush();
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
        publish(
                writer,
                CustomerPartyUpdatedV1.EVENT_TYPE,
                CustomerPartyUpdatedV1.SCHEMA_VERSION,
                party.getPartyId(),
                payload,
                party.getVersion());
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
        publish(
                writer,
                CustomerPersonIdentityUpdatedV1.EVENT_TYPE,
                CustomerPersonIdentityUpdatedV1.SCHEMA_VERSION,
                personId,
                payload);
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

    /** Emission-millis {@code aggregateVersion} — every fact except party-updated/deleted. */
    private void publish(
            @NonNull OutboxEventWriter writer,
            @NonNull String eventType,
            int schemaVersion,
            @NonNull UUID aggregateId,
            Object payload) {
        publish(
                writer,
                eventType,
                schemaVersion,
                aggregateId,
                payload,
                Instant.now(clock).toEpochMilli());
    }

    /** {@code @Version}-backed {@code aggregateVersion} (#1486) — party-updated and -deleted. */
    private void publish(
            @NonNull OutboxEventWriter writer,
            @NonNull String eventType,
            int schemaVersion,
            @NonNull UUID aggregateId,
            Object payload,
            long aggregateVersion) {
        DomainEventEnvelope<Object> envelope = DomainEventEnvelope.of(
                eventType, schemaVersion, aggregateId, aggregateVersion, SOURCE, null, null, payload, clock);
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

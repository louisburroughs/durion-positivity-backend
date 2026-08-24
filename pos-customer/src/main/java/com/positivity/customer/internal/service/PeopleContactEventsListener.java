package com.positivity.customer.internal.service;

import com.positivity.customer.internal.entity.ExtOrganizationPostalAddress;
import com.positivity.customer.internal.entity.ExtPersonReplica;
import com.positivity.customer.internal.entity.ProcessedEvent;
import com.positivity.customer.internal.repository.ExtOrganizationPostalAddressRepository;
import com.positivity.customer.internal.repository.ExtPersonReplicaRepository;
import com.positivity.customer.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.peoplecontact.OrganizationAddressRemovedV1;
import com.positivity.domainevents.peoplecontact.OrganizationAddressUpdatedV1;
import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.PostalAddressV1;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code people-contact.events.v1} into the {@code ext_people_contact_person} replica
 * (ADR-0044 §6, #877). Same contract as the vehicle replica listener: idempotent via
 * {@code processed_events}, stale versions skipped (strictly-below guard — the producer's
 * aggregateVersion is an emission-timestamp LWW hint), transient errors rethrown for retry/DLQ.
 * Link facts are ignored here (pos-security-service owns that projection).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.customer.kafka", name = "enabled", havingValue = "true")
public class PeopleContactEventsListener {
    private static final String PAYLOAD = "payload";

    private static final String AGGREGATE_VERSION = "aggregateVersion";

    static final String OWNER = "people-contact";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final ExtOrganizationPostalAddressRepository extOrganizationPostalAddressRepository;
    private final CustomerFactPublisher customerFactPublisher;
    private final Counter payloadRejectedCounter;

    public PeopleContactEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtPersonReplicaRepository extPersonReplicaRepository,
            ExtOrganizationPostalAddressRepository extOrganizationPostalAddressRepository,
            CustomerFactPublisher customerFactPublisher,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extPersonReplicaRepository = extPersonReplicaRepository;
        this.extOrganizationPostalAddressRepository = extOrganizationPostalAddressRepository;
        this.customerFactPublisher = customerFactPublisher;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description("Replica event payloads rejected due to Jackson databind failures"
                                + " (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "people-contact-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.customer.kafka.people-contact-events-topic:people-contact.events.v1}",
            groupId = "${pos.customer.kafka.people-contact-events-consumer-group:pos-customer-people-contact-events}")
    @Transactional
    public void onPeopleContactEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable people-contact event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping people-contact event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            switch (eventType == null ? "" : eventType) {
                case PersonUpdatedV1.EVENT_TYPE -> applyPersonUpdated(envelope);
                case PersonDeletedV1.EVENT_TYPE -> applyPersonDeleted(envelope);
                case OrganizationAddressUpdatedV1.EVENT_TYPE -> applyOrganizationAddressUpdated(envelope);
                case OrganizationAddressRemovedV1.EVENT_TYPE -> applyOrganizationAddressRemoved(envelope);
                default ->
                    // Ignored types still fall through to the processed_events insert below: the
                    // owner's manifest counts every fact in the window, so skipping the insert
                    // would register as replica drift and trigger a pointless replay.
                    log.debug("Ignoring people-contact event type={}", eventType);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed people-contact event payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed people-contact event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyPersonUpdated(JsonNode envelope) {
        PersonUpdatedV1 payload = objectMapper.treeToValue(envelope.path(PAYLOAD), PersonUpdatedV1.class);
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
        ExtPersonReplica existing =
                extPersonReplicaRepository.findById(payload.personId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        String primaryEmail = payload.contactPoints().stream()
                .filter(cp -> "EMAIL".equals(cp.contactType()) && cp.primary())
                .map(PersonUpdatedV1.ContactPointV1::value)
                .findFirst()
                .orElse(null);
        String contactJson;
        try {
            contactJson = objectMapper.writeValueAsString(payload.contactPoints());
        } catch (Exception e) {
            contactJson = null;
        }
        PostalAddressV1 address = payload.postalAddress();
        extPersonReplicaRepository.save(ExtPersonReplica.builder()
                .personId(payload.personId())
                .firstName(payload.firstName())
                .lastName(payload.lastName())
                .preferredName(payload.preferredName())
                .primaryEmail(primaryEmail)
                .contactPoints(contactJson)
                .addressLine1(address == null ? null : address.line1())
                .addressLine2(address == null ? null : address.line2())
                .addressCity(address == null ? null : address.city())
                .addressRegion(address == null ? null : address.region())
                .addressPostalCode(address == null ? null : address.postalCode())
                .addressCountryCode(address == null ? null : address.countryCode())
                .personCreatedAt(payload.createdAt())
                .personUpdatedAt(payload.updatedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        // The person-party fact's displayName is materialized from this replica — re-emit it so
        // ext_customer_party consumers pick up the name change (#889). No-op when the person has
        // no individual-customer record.
        customerFactPublisher.personReplicaChanged(payload.personId());
        log.info("Updated ext_people_contact_person personId={} version={}", payload.personId(), aggregateVersion);
    }

    private void applyPersonDeleted(JsonNode envelope) {
        PersonDeletedV1 payload = objectMapper.treeToValue(envelope.path(PAYLOAD), PersonDeletedV1.class);
        extPersonReplicaRepository.deleteById(payload.personId());
        log.info("Deleted ext_people_contact_person personId={}", payload.personId());
    }

    private void applyOrganizationAddressUpdated(JsonNode envelope) {
        OrganizationAddressUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path(PAYLOAD), OrganizationAddressUpdatedV1.class);
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
        ExtOrganizationPostalAddress existing = extOrganizationPostalAddressRepository
                .findById(payload.organizationId())
                .orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        PostalAddressV1 address = payload.postalAddress();
        extOrganizationPostalAddressRepository.save(ExtOrganizationPostalAddress.builder()
                .organizationId(payload.organizationId())
                .line1(address.line1())
                .line2(address.line2())
                .city(address.city())
                .region(address.region())
                .postalCode(address.postalCode())
                .countryCode(address.countryCode())
                .addressUpdatedAt(payload.updatedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Updated ext_organization_postal_address organizationId={} version={}",
                payload.organizationId(),
                aggregateVersion);
    }

    /**
     * Removal is a versioned tombstone, not a hard delete: a delete would discard the version
     * watermark, letting a replayed older update resurrect stale data and letting a stale
     * removal (replayed after a newer update) wipe a current address. All-null address fields
     * read as "no address on file" everywhere the replica is consumed.
     */
    private void applyOrganizationAddressRemoved(JsonNode envelope) {
        OrganizationAddressRemovedV1 payload =
                objectMapper.treeToValue(envelope.path(PAYLOAD), OrganizationAddressRemovedV1.class);
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
        ExtOrganizationPostalAddress existing = extOrganizationPostalAddressRepository
                .findById(payload.organizationId())
                .orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extOrganizationPostalAddressRepository.save(ExtOrganizationPostalAddress.builder()
                .organizationId(payload.organizationId())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Tombstoned ext_organization_postal_address organizationId={} version={}",
                payload.organizationId(),
                aggregateVersion);
    }
}

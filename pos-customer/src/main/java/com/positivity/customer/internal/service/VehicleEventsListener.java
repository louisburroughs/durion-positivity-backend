package com.positivity.customer.internal.service;

import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.ExtVehicle;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.entity.ProcessedEvent;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.vehicle.VehicleUpdatedV1;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code vehicle.events.v1} into the {@code ext_vehicle} replica (ADR-0044 §6, #843).
 *
 * <p>Same contract as pos-accounting's replica listeners: idempotent via {@code processed_events}
 * in the upsert transaction, stale envelopes (aggregateVersion at or below the replica's) skipped,
 * transient DB errors rethrown for container retry/DLQ, malformed payloads logged and skipped.
 *
 * <p>Beyond the replica, this listener maintains the vehicle-party association pos-customer owns
 * (ADR-0012): the event's {@code accountId} is the owning party, so the VIN is added to that
 * party's set and removed from any other party still holding it (ownership transfer), and a
 * deactivation removes the VIN everywhere.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.customer.kafka", name = "enabled", havingValue = "true")
public class VehicleEventsListener {

    static final String OWNER = "vehicle";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtVehicleRepository extVehicleRepository;
    private final PersonPartyRepository personPartyRepository;
    private final CommercialPartyRepository commercialPartyRepository;

    @KafkaListener(
            topics = "${pos.customer.kafka.vehicle-events-topic:vehicle.events.v1}",
            groupId = "${pos.customer.kafka.vehicle-events-consumer-group:pos-customer-vehicle-events}")
    @Transactional
    public void onVehicleEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable vehicle event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        if (!VehicleUpdatedV1.EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring vehicle event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping vehicle event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate vehicle event eventId={}", eventId);
            return;
        }

        try {
            applyVehicleUpdate(envelope);
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed vehicle event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyVehicleUpdate(JsonNode envelope) {
        VehicleUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), VehicleUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        UUID vehicleId = payload.vehicleId();

        ExtVehicle existing = extVehicleRepository.findById(vehicleId).orElse(null);
        // Versions are strictly increasing per vehicle (committed JPA @Version, flushed before
        // emit), so version 0 (the create) participates in the comparison too — a late or
        // replayed version-0 event must never overwrite a newer replica row.
        if (existing != null && existing.getAggregateVersion() >= aggregateVersion) {
            log.debug(
                    "Skipping stale vehicle event vehicleId={} eventVersion={} replicaVersion={}",
                    vehicleId,
                    aggregateVersion,
                    existing.getAggregateVersion());
            return;
        }

        extVehicleRepository.save(ExtVehicle.builder()
                .vehicleId(vehicleId)
                .accountId(payload.accountId())
                .vin(payload.vin())
                .vinNormalized(payload.vinNormalized())
                .unitNumber(payload.unitNumber())
                .description(payload.description())
                .licensePlate(payload.licensePlate())
                .licensePlateJurisdiction(payload.licensePlateJurisdiction())
                .year(payload.year())
                .make(payload.make())
                .model(payload.model())
                .trim(payload.trim())
                .active(payload.active())
                .vehicleCreatedAt(payload.createdAt())
                .vehicleUpdatedAt(payload.updatedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());

        syncPartyAssociation(payload);
        log.info(
                "Updated ext_vehicle replica vehicleId={} accountId={} active={} version={}",
                vehicleId,
                payload.accountId(),
                payload.active(),
                aggregateVersion);
    }

    /**
     * Keeps the customer-owned vehicle-party association (ADR-0012) aligned with the owner's
     * {@code accountId} fact: the VIN lives in exactly the owning party's set while the vehicle is
     * active, and in no party's set once deactivated.
     */
    private void syncPartyAssociation(VehicleUpdatedV1 payload) {
        String vin = payload.vin();
        List<AbstractParty> holders = new ArrayList<>();
        holders.addAll(personPartyRepository.findByVehicleVin(vin));
        holders.addAll(commercialPartyRepository.findByVehicleVin(vin));

        for (AbstractParty holder : holders) {
            if (!payload.active() || !holder.getPartyId().equals(payload.accountId())) {
                holder.removeVehicleVin(vin);
                saveParty(holder);
            }
        }

        if (!payload.active()) {
            return;
        }
        boolean ownerAlreadyHolds =
                holders.stream().anyMatch(h -> h.getPartyId().equals(payload.accountId()));
        if (ownerAlreadyHolds) {
            return;
        }
        findParty(payload.accountId())
                .ifPresentOrElse(
                        owner -> {
                            owner.addVehicleVin(vin);
                            saveParty(owner);
                        },
                        // Not every account is a CRM party (e.g. internal fleet); the replica row alone
                        // is enough in that case.
                        () -> log.debug(
                                "No CRM party {} for vehicle {}; association skipped",
                                payload.accountId(),
                                payload.vehicleId()));
    }

    private java.util.Optional<AbstractParty> findParty(UUID partyId) {
        return personPartyRepository
                .findById(partyId)
                .map(p -> (AbstractParty) p)
                .or(() -> commercialPartyRepository.findById(partyId).map(p -> (AbstractParty) p));
    }

    private void saveParty(AbstractParty party) {
        if (party instanceof PersonParty person) {
            personPartyRepository.save(person);
        } else if (party instanceof CommercialParty commercial) {
            commercialPartyRepository.save(commercial);
        }
    }
}

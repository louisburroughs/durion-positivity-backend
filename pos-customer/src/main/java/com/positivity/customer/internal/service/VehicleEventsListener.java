package com.positivity.customer.internal.service;

import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.ExtVehicle;
import com.positivity.customer.internal.entity.ExtVehicleCarePreference;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.entity.ProcessedEvent;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ExtVehicleCarePreferenceRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.vehicle.VehicleCarePreferenceUpdatedV1;
import com.positivity.domainevents.vehicle.VehicleUpdatedV1;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * Consumes {@code vehicle.events.v1} into the {@code ext_vehicle} and
 * {@code ext_vehicle_care_preference} replicas (ADR-0044 §6, #843, #1175).
 *
 * <p>Same contract as pos-accounting's replica listeners: idempotent via {@code processed_events}
 * in the upsert transaction, stale envelopes skipped, transient DB errors rethrown for container
 * retry/DLQ, malformed payloads logged and skipped. Every parsed envelope with an eventId is
 * recorded in {@code processed_events} — including event types this module ignores — because the
 * owner's reconciliation manifest counts every event on the topic and an unrecorded eventId would
 * read as drift (#1175).
 *
 * <p>Beyond the replica, this listener maintains the vehicle-party association pos-customer owns
 * (ADR-0012): the event's {@code accountId} is the owning party, so the VIN is added to that
 * party's set and removed from any other party still holding it (ownership transfer), and a
 * deactivation removes the VIN everywhere.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.customer.kafka", name = "enabled", havingValue = "true")
public class VehicleEventsListener {

    static final String OWNER = "vehicle";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtVehicleRepository extVehicleRepository;
    private final ExtVehicleCarePreferenceRepository extVehicleCarePreferenceRepository;
    private final PersonPartyRepository personPartyRepository;
    private final CommercialPartyRepository commercialPartyRepository;
    private final Counter payloadRejectedCounter;

    public VehicleEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtVehicleRepository extVehicleRepository,
            ExtVehicleCarePreferenceRepository extVehicleCarePreferenceRepository,
            PersonPartyRepository personPartyRepository,
            CommercialPartyRepository commercialPartyRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extVehicleRepository = extVehicleRepository;
        this.extVehicleCarePreferenceRepository = extVehicleCarePreferenceRepository;
        this.personPartyRepository = personPartyRepository;
        this.commercialPartyRepository = commercialPartyRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description("Replica event payloads rejected due to Jackson databind failures"
                                + " (e.g. omitted primitive fields)")
                        .tag("owner", "vehicle")
                        .tag("entity", "vehicle-events")
                        .register(registry);
    }

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
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping vehicle event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate vehicle event eventId={}", eventId);
            return;
        }

        String eventType = envelope.path("eventType").stringValue(null);
        try {
            if (VehicleUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyVehicleUpdate(envelope);
            } else if (VehicleCarePreferenceUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyCarePreferenceUpdate(envelope);
            } else {
                // Recorded below anyway: the owner's manifest counts every event on the topic,
                // so an ignored-but-unrecorded eventId would read as drift every window (#1175).
                log.debug("Ignoring vehicle event type={}", eventType);
            }
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed vehicle event payload eventId={}: {}", eventId, e.getMessage(), e);
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

    private void applyCarePreferenceUpdate(JsonNode envelope) {
        VehicleCarePreferenceUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), VehicleCarePreferenceUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        UUID vehicleId = payload.vehicleId();

        ExtVehicleCarePreference existing =
                extVehicleCarePreferenceRepository.findById(vehicleId).orElse(null);
        // The care-preference aggregateVersion is a last-writer-wins epoch-millis stamp, not a
        // JPA row version (the row is hard-deleted and re-created upstream), so the guard is
        // strictly-greater — an equal stamp applies (people-contact replica precedent).
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            log.debug(
                    "Skipping stale care-preference event vehicleId={} eventVersion={} replicaVersion={}",
                    vehicleId,
                    aggregateVersion,
                    existing.getAggregateVersion());
            return;
        }

        // A delete is a versioned tombstone (interval null, version advanced), never a hard
        // delete: a surviving row is what lets this guard reject a replayed older update after
        // the removal was applied.
        extVehicleCarePreferenceRepository.save(ExtVehicleCarePreference.builder()
                .vehicleId(vehicleId)
                .serviceIntervalMonths(payload.deleted() ? null : payload.serviceIntervalMonths())
                .preferenceUpdatedAt(payload.updatedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Updated ext_vehicle_care_preference replica vehicleId={} serviceIntervalMonths={} deleted={}",
                vehicleId,
                payload.serviceIntervalMonths(),
                payload.deleted());
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

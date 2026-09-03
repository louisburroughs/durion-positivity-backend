package com.positivity.shopmanager.internal.service;

import com.positivity.domainevents.location.BayDeletedV1;
import com.positivity.domainevents.location.BayUpdatedV1;
import com.positivity.domainevents.location.MobileUnitDeletedV1;
import com.positivity.domainevents.location.MobileUnitUpdatedV1;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtMobileUnitReplica;
import com.positivity.shopmanager.internal.entity.ProcessedEvent;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * Consumes {@code location.events.v1} into the {@code ext_bay} and {@code ext_mobile_unit}
 * replicas (ADR-0044 §6, #1658) — the bay/mobile-unit topology behind the shop dashboard's unit
 * roster.
 *
 * <h2>Why a replica and not a live read (#1658 AC11)</h2>
 *
 * A synchronous {@code RestClient} from pos-shop-manager into pos-location would work today and
 * would need no new tables — bay topology changes rarely, so the staleness argument for events is
 * weak here. It was rejected anyway, for three reasons:
 *
 * <ol>
 *   <li>It is a domain→domain synchronous call, which ADR-0044 R1 forbids outright. There is no
 *       standing grant covering it: the only synchronous exceptions on the books are the
 *       {@code SupplierStockService} grant (ADR-0026 D1–D5) and pos-warranty's scoped v1
 *       exception. Taking this route means minting a <em>new</em> recorded ADR-0044 exception on
 *       the pos-warranty precedent (durion-positivity-backend#786) — a real architectural cost,
 *       paid permanently, for a read that is not on a latency-critical path.
 *   <li>pos-workorder answered the identical question the opposite way one story earlier (#1656,
 *       {@code ExtBayReplica} / {@code ExtMobileUnitReplica} on this same topic). Two modules
 *       replicating the same two aggregates in the same shape is one upstream publisher away from
 *       done; one replicating and one calling is a permanent inconsistency in how the platform
 *       reads location topology.
 *   <li>This module already runs four replica consumers over this exact contract
 *       ({@code ext_customer_party}, {@code ext_vehicle}, {@code ext_people_contact_person},
 *       {@code ext_people_staffing_assignment}). The replica is the cheap option here and the
 *       live call is the expensive one, which is the reverse of the usual trade.
 * </ol>
 *
 * <p><strong>The honest consequence:</strong> pos-location's {@code LocationFactPublisher} does not
 * publish bay or mobile-unit facts yet — it emits {@code location.location.*} and
 * {@code location.storage-location.updated} and nothing else. So these two tables start empty and
 * stay empty until that publisher exists, and until then the dashboard's {@code units[]} is empty
 * while {@code openWorkorders[]} is fully populated. A live read would have returned units today
 * at the price above. The upstream publisher is the cross-repo follow-up that closes the gap for
 * both this module and pos-workorder at once.
 *
 * <p>Consumer contract as per this module's other replica listeners: {@code processed_events}
 * idempotency in the apply transaction, strictly-below {@code aggregateVersion} stale guard,
 * transient DB errors rethrown for container retry/DLQ, malformed payloads swallowed but recorded.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.shop-manager.kafka", name = "enabled", havingValue = "true")
public class LocationEventsListener {

    static final String OWNER = "location";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtBayReplicaRepository extBayReplicaRepository;
    private final ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;
    private final Counter payloadRejectedCounter;

    public LocationEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtBayReplicaRepository extBayReplicaRepository,
            ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extBayReplicaRepository = extBayReplicaRepository;
        this.extMobileUnitReplicaRepository = extMobileUnitReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. a malformed identifier)")
                        .tag("owner", OWNER)
                        .tag("entity", "location-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.shop-manager.kafka.location-events-topic:location.events.v1}",
            groupId = "${pos.shop-manager.kafka.location-events-consumer-group:pos-shop-manager-location-events}")
    @Transactional
    public void onLocationEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable location event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping location event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            switch (eventType == null ? "" : eventType) {
                case BayUpdatedV1.EVENT_TYPE -> applyBayUpdated(envelope);
                case BayDeletedV1.EVENT_TYPE -> applyBayDeleted(envelope);
                case MobileUnitUpdatedV1.EVENT_TYPE -> applyMobileUnitUpdated(envelope);
                case MobileUnitDeletedV1.EVENT_TYPE -> applyMobileUnitDeleted(envelope);
                default ->
                    // location.location.* and location.storage-location.* travel this topic too and
                    // are not this module's business; their ids are still recorded so the owner's
                    // manifest reconciles.
                    log.debug("Ignoring location event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed location event payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed location event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyBayUpdated(JsonNode envelope) {
        BayUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), BayUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtBayReplica existing =
                extBayReplicaRepository.findById(payload.bayId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extBayReplicaRepository.save(ExtBayReplica.builder()
                .bayId(payload.bayId())
                .locationId(payload.locationId())
                .name(payload.name())
                .active(isActiveStatus(payload.status()))
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }

    private void applyBayDeleted(JsonNode envelope) {
        BayDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), BayDeletedV1.class);
        extBayReplicaRepository.deleteById(payload.bayId());
    }

    private void applyMobileUnitUpdated(JsonNode envelope) {
        MobileUnitUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), MobileUnitUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtMobileUnitReplica existing =
                extMobileUnitReplicaRepository.findById(payload.mobileUnitId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extMobileUnitReplicaRepository.save(ExtMobileUnitReplica.builder()
                .mobileUnitId(payload.mobileUnitId())
                .baseLocationId(payload.baseLocationId())
                .name(payload.name())
                .active(isActiveStatus(payload.status()))
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }

    private void applyMobileUnitDeleted(JsonNode envelope) {
        MobileUnitDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), MobileUnitDeletedV1.class);
        extMobileUnitReplicaRepository.deleteById(payload.mobileUnitId());
    }

    /**
     * Whether an owner-published lifecycle status means "this unit can take work today" (#1658).
     *
     * <p>Neither {@code BayEntity} nor {@code MobileUnitEntity} carries a boolean active flag in
     * pos-location — the lifecycle lives entirely in {@code status}. So the replica's own
     * {@code active} column has to be <em>derived</em> here, and it is derived by allow-listing the
     * single value that means in service. A deny-list would be wrong for two different reasons at
     * once: a bay's status is a closed {@code ACTIVE} | {@code OUT_OF_SERVICE} pair today but is not
     * guaranteed to stay closed, and a mobile unit's status is a free-text column, so an unseen or
     * misspelled value would otherwise be read as "in service" and put a unit the shop cannot use
     * onto the dashboard's roster. Absent, blank and unknown all mean not active.
     *
     * <p>pos-workorder derives the same fact the same way (#1656); the two modules mirror one
     * upstream aggregate and must not disagree about which units are in service.
     *
     * @param status the owner's status string, possibly {@code null}
     * @return true only for the {@code ACTIVE} token, in any casing
     */
    private static boolean isActiveStatus(@Nullable String status) {
        return status != null && "ACTIVE".equalsIgnoreCase(status.strip());
    }
}

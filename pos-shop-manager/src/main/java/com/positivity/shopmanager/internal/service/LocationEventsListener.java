package com.positivity.shopmanager.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.location.BayDeletedV1;
import com.positivity.domainevents.location.BaySpecialtyMapUpdatedV1;
import com.positivity.domainevents.location.BayUpdatedV1;
import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.location.MobileUnitDeletedV1;
import com.positivity.domainevents.location.MobileUnitUpdatedV1;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtBaySpecialtyMapReplica;
import com.positivity.shopmanager.internal.entity.ExtBayTypeReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationParentReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.entity.ExtMobileUnitReplica;
import com.positivity.shopmanager.internal.entity.ProcessedEvent;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtBaySpecialtyMapReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtBayTypeReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code location.events.v1} into the {@code ext_bay} and {@code ext_mobile_unit}
 * replicas (ADR-0044 §6, #1658) — the bay/mobile-unit topology behind the shop dashboard's unit
 * roster — and, since #1872, into the {@code ext_location} / {@code ext_location_parent} replica
 * behind this module's location-scope check (ADR-0061 §2).
 *
 * <h2>Location facts and the scope ancestor sets (#1872)</h2>
 *
 * Each {@code location.location.updated} fact upserts the location row, replaces the child's typed
 * parent-edge set from the fact (a {@code null} list means the producer predates the field and the
 * stored edges are kept), then asks {@link LocationHierarchyService#recomputeAncestors} to rebuild
 * the materialised ancestor sets for the location and every replicated descendant — so a
 * re-parented node propagates, and a parent arriving after its children pushes its ancestry down
 * to them. Ingestion never fails closed on a parent the replica has not seen yet; the scope check
 * does. This mirrors pos-people's listener exactly.
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
 * <p><strong>Update (#2023 F5):</strong> the paragraph above once said pos-location did not publish
 * bay or mobile-unit facts and that {@code ext_bay} / {@code ext_mobile_unit} would start empty and
 * stay empty. That has been false since issue #1668: this listener has handled {@code
 * BayUpdatedV1}/{@code BayDeletedV1}/{@code MobileUnitUpdatedV1}/{@code MobileUnitDeletedV1} ever
 * since, and {@link ReplicaAndManifestListenerContractTest} exercises all four. The bay roster is
 * available to the dashboard today.
 *
 * <h2>Bay specialty map replica (#2261, DECISION-LOCATION-025)</h2>
 *
 * {@code location.bay-specialty-map.updated} carries a tenant's <em>whole</em> bay-type specialty
 * map — one entry per {@code BayType}, never a delta — and is applied by deleting every
 * {@link ExtBaySpecialtyMapReplica} / {@link ExtBayTypeReplica} row for the tenant and reinserting
 * one per entry, in the same handler transaction as the {@code processed_events} mark. The stale
 * guard is {@link ReplicaVersionGuard} on whichever {@code ext_bay_type} row happens to come back
 * first for the tenant (every row from one emission carries the same {@code aggregateVersion}), no
 * held row meaning version 0 so the very first map for a tenant is never treated as stale. An empty
 * replica (nothing has arrived yet) makes {@link ExtBaySpecialtyMapReplicaRepository#existsByOperationCode}
 * answer {@code false} for every operation code — no operation is specialty — which is exactly
 * today's pre-replica behaviour; wiring that read into eligibility enforcement is a later story.
 * {@code ext_bay.acceptsGeneralWork} (see {@link #applyBayUpdated}) is a separate, additive
 * {@code BayUpdatedV1} field and is merged independently of this map.
 *
 * <p>Consumer contract as per this module's other replica listeners: {@code processed_events}
 * idempotency, strictly-below {@code aggregateVersion} stale guard,
 * transient DB errors rethrown for container retry/DLQ, malformed payloads swallowed but recorded.
 *
 * <p><strong>Transaction shape (#2146).</strong> The listener method is not {@code @Transactional}:
 * the handler's work and the {@code processed_events} mark commit together in a
 * {@code REQUIRES_NEW} transaction of their own, so neither lands without the other. A permanent
 * failure rolls back only that work and is recorded in a separate transaction, instead of
 * poisoning a shared transaction whose commit then throws and sends the record through retry and
 * dead-lettering. Transient database errors still propagate, unrecorded, for container retry.
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
    private final ExtLocationReplicaRepository extLocationReplicaRepository;
    private final ExtLocationParentReplicaRepository extLocationParentReplicaRepository;
    private final ExtBaySpecialtyMapReplicaRepository extBaySpecialtyMapReplicaRepository;
    private final ExtBayTypeReplicaRepository extBayTypeReplicaRepository;
    private final LocationHierarchyService locationHierarchyService;
    private final Counter payloadRejectedCounter;

    /** Runs the handler with its processed mark, and records a failure; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public LocationEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtBayReplicaRepository extBayReplicaRepository,
            ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository,
            ExtLocationReplicaRepository extLocationReplicaRepository,
            ExtLocationParentReplicaRepository extLocationParentReplicaRepository,
            ExtBaySpecialtyMapReplicaRepository extBaySpecialtyMapReplicaRepository,
            ExtBayTypeReplicaRepository extBayTypeReplicaRepository,
            LocationHierarchyService locationHierarchyService,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extBayReplicaRepository = extBayReplicaRepository;
        this.extMobileUnitReplicaRepository = extMobileUnitReplicaRepository;
        this.extLocationReplicaRepository = extLocationReplicaRepository;
        this.extLocationParentReplicaRepository = extLocationParentReplicaRepository;
        this.extBaySpecialtyMapReplicaRepository = extBaySpecialtyMapReplicaRepository;
        this.extBayTypeReplicaRepository = extBayTypeReplicaRepository;
        this.locationHierarchyService = locationHierarchyService;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
            handlerTransaction.executeWithoutResult(_ -> {
                switch (eventType == null ? "" : eventType) {
                    case BayUpdatedV1.EVENT_TYPE -> applyBayUpdated(envelope);
                    case BayDeletedV1.EVENT_TYPE -> applyBayDeleted(envelope);
                    case MobileUnitUpdatedV1.EVENT_TYPE -> applyMobileUnitUpdated(envelope);
                    case MobileUnitDeletedV1.EVENT_TYPE -> applyMobileUnitDeleted(envelope);
                    case LocationUpdatedV1.EVENT_TYPE -> applyLocationUpdated(envelope);
                    case LocationDeletedV1.EVENT_TYPE -> applyLocationDeleted(envelope);
                    case BaySpecialtyMapUpdatedV1.EVENT_TYPE -> applyBaySpecialtyMapUpdated(envelope);
                    default ->
                        // location.storage-location.* travels this topic too and is not this module's
                        // business; its ids are still recorded so the owner's manifest reconciles.
                        log.debug("Ignoring location event type={} eventId={}", eventType, eventId);
                }
                processedEventRepository.save(processedMark(eventId));
            });
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed location event payload eventId={}: {}", eventId, e.getMessage(), e);
            recordFailed(eventId);
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // The container retries with backoff, then publishes to {topic}.dlq (ADR-0044 §4).
                throw e;
            }
            log.warn("Skipping malformed location event eventId={}", eventId, e);
            recordFailed(eventId);
        }
    }

    private ProcessedEvent processedMark(String eventId) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build();
    }

    /** Records a permanently failed event in a transaction of its own; see the class doc. */
    private void recordFailed(String eventId) {
        handlerTransaction.executeWithoutResult(_ -> processedEventRepository.save(processedMark(eventId)));
    }

    private void applyBayUpdated(JsonNode envelope) {
        JsonNode payloadNode = envelope.path("payload");
        BayUpdatedV1 payload = objectMapper.treeToValue(payloadNode, BayUpdatedV1.class);
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
                // bayType was added after this listener started running (#2023/#2021): a fact
                // serialized by a pre-change producer has no such field at all, which must not be
                // read as "clear the bayType already replicated" during a rolling deploy or replay
                // of an older stored event (#2023 F2).
                .bayType(mergeField(
                        payloadNode, "bayType", payload.bayType(), existing == null ? null : existing.getBayType()))
                .active(isActiveStatus(payload.status()))
                // Same rule for the CAP-325 eligibility fields, which arrived later still: absent from
                // the payload means "not published", so the replicated value stands.
                .serviceCapabilityCodes(mergeField(
                        payloadNode,
                        "serviceCapabilityCodes",
                        payload.serviceCapabilityCodes(),
                        existing == null ? null : existing.getServiceCapabilityCodes()))
                .maxConcurrentVehicles(mergeField(
                        payloadNode,
                        "maxConcurrentVehicles",
                        payload.maxConcurrentVehicles(),
                        existing == null ? null : existing.getMaxConcurrentVehicles()))
                .maxDutyClass(mergeField(
                        payloadNode,
                        "maxDutyClass",
                        payload.maxDutyClass(),
                        existing == null ? null : existing.getMaxDutyClass()))
                // displayOrder (DECISION-LOCATION-026, #2264) is additive the same way: absent from
                // the payload means "not published", so the replicated value stands.
                .displayOrder(mergeField(
                        payloadNode,
                        "displayOrder",
                        payload.displayOrder(),
                        existing == null ? null : existing.getDisplayOrder()))
                // acceptsGeneralWork (DECISION-LOCATION-025, #2261) is additive within schema v1:
                // null - whether absent or an explicit JSON null - always means "the publisher
                // predates the field", never "no" (BayUpdatedV1 javadoc). So this reads only
                // payload.acceptsGeneralWork() itself, not payloadNode.has(...): keep the
                // already-replicated value (or default true for a brand-new row) whenever the fact
                // carries no boolean here, exactly the gvwrClass guard style the vehicle listeners use.
                .acceptsGeneralWork(mergeAcceptsGeneralWork(payload.acceptsGeneralWork(), existing))
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }

    /**
     * A stray {@code location.bay.deleted}: pos-location no longer emits this fact
     * (DECISION-LOCATION-026, issue #2264) — retiring a bay now arrives as an ordinary {@code
     * BayUpdatedV1} with {@code status = RETIRED}, applied by {@link #applyBayUpdated} exactly like
     * any other status change. A replayed or long-delayed old event is handled safely rather than
     * ignored: the row, if still present, is marked inactive instead of removed, so an appointment
     * or workorder that already names this bay keeps resolving to a name.
     *
     * <p>Guarded by the same {@code aggregateVersion} comparison as {@link #applyBayUpdated} (HIGH
     * finding, PR #2278): a pre-#2264 delete can be delivered long after a newer {@code
     * BayUpdatedV1} has already reactivated the row (e.g. {@code RETIRED} → {@code ACTIVE}), and
     * applying it unconditionally would silently retire a bay that is back in service. A stale
     * delete is ignored; an accepted one marks the row inactive and stores its version so a still
     * older or equal-but-repeated delete cannot regress it further.
     */
    private void applyBayDeleted(JsonNode envelope) {
        BayDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), BayDeletedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        extBayReplicaRepository.findById(payload.bayId()).ifPresent(existing -> {
            if (ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
                log.debug(
                        "Ignoring stale bay delete bayId={} version={} held={}",
                        payload.bayId(),
                        aggregateVersion,
                        existing.getAggregateVersion());
                return;
            }
            existing.setActive(false);
            existing.setAggregateVersion(aggregateVersion);
            existing.setUpdatedAt(Instant.now(clock));
            extBayReplicaRepository.save(existing);
        });
    }

    /**
     * {@code acceptsGeneralWork} on a bay row: {@code null} (absent or explicit JSON null) always
     * means "the publisher predates the field" (BayUpdatedV1 javadoc), so the already-replicated
     * value is kept, or the column default {@code true} stands for a brand-new row. Never a
     * {@code payloadNode.has(...)} check like {@link #mergeField}: unlike the fields that helper
     * covers, an explicit null here carries no "clear this" meaning of its own.
     */
    private static boolean mergeAcceptsGeneralWork(@Nullable Boolean newValue, @Nullable ExtBayReplica existing) {
        if (newValue != null) {
            return newValue;
        }
        return existing == null || existing.isAcceptsGeneralWork();
    }

    /**
     * Full replace of the tenant's whole bay specialty map (#2261, DECISION-LOCATION-025): every
     * {@code ext_bay_specialty_map} / {@code ext_bay_type} row for the bound tenant is deleted and
     * one row per {@link BaySpecialtyMapUpdatedV1.Entry} is reinserted, inside the same handler
     * transaction as the {@code processed_events} mark. The stale guard reads whichever
     * {@code ext_bay_type} row happens to come back first for the tenant — every row from one
     * emission carries the same {@code aggregateVersion} — treating "no row held" as version 0 so
     * the tenant's first-ever map is never skipped as stale.
     */
    private void applyBaySpecialtyMapUpdated(JsonNode envelope) {
        BaySpecialtyMapUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), BaySpecialtyMapUpdatedV1.class);
        long incomingVersion = payload.aggregateVersion();
        long heldVersion = extBayTypeReplicaRepository
                .findFirstByOrderByBayTypeAsc()
                .map(ExtBayTypeReplica::getAggregateVersion)
                .orElse(0L);
        if (ReplicaVersionGuard.isStale(heldVersion, incomingVersion)) {
            log.debug(
                    "Ignoring stale bay specialty map tenantId={} version={} held={}",
                    payload.tenantId(),
                    incomingVersion,
                    heldVersion);
            return;
        }
        extBaySpecialtyMapReplicaRepository.deleteAll();
        extBayTypeReplicaRepository.deleteAll();
        Instant now = Instant.now(clock);
        for (BaySpecialtyMapUpdatedV1.Entry entry : payload.entries()) {
            extBayTypeReplicaRepository.save(ExtBayTypeReplica.builder()
                    .bayType(entry.bayType())
                    .acceptsGeneralWork(entry.acceptsGeneralWork())
                    .aggregateVersion(incomingVersion)
                    .updatedAt(now)
                    .build());
            for (String operationCode : entry.operationCodes()) {
                extBaySpecialtyMapReplicaRepository.save(ExtBaySpecialtyMapReplica.builder()
                        .bayType(entry.bayType())
                        .operationCode(operationCode)
                        .updatedAt(now)
                        .build());
            }
        }
        log.info(
                "Applied bay specialty map tenantId={} version={} bayTypes={}",
                payload.tenantId(),
                incomingVersion,
                payload.entries().size());
    }

    private void applyMobileUnitUpdated(JsonNode envelope) {
        JsonNode payloadNode = envelope.path("payload");
        MobileUnitUpdatedV1 payload = objectMapper.treeToValue(payloadNode, MobileUnitUpdatedV1.class);
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
                // maxDutyClass (DECISION-LOCATION-029, #2267) is additive within v4, the same guard
                // style as ext_bay.maxDutyClass in applyBayUpdated: absent from the payload means
                // "the publisher predates the field", so the already-replicated value stands, never
                // read as "unconstrained".
                .maxDutyClass(mergeField(
                        payloadNode,
                        "maxDutyClass",
                        payload.maxDutyClass(),
                        existing == null ? null : existing.getMaxDutyClass()))
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }

    /**
     * A stray {@code location.mobile-unit.deleted}: pos-location no longer emits this fact
     * (DECISION-LOCATION-026, issue #2264) — retiring a unit now arrives as an ordinary {@code
     * MobileUnitUpdatedV1} with {@code status = RETIRED}, applied by {@link #applyMobileUnitUpdated}
     * exactly like any other status change. A replayed or long-delayed old event is handled safely
     * rather than ignored: the row, if still present, is marked inactive instead of removed.
     *
     * <p>Guarded by the same {@code aggregateVersion} comparison as {@link #applyMobileUnitUpdated}
     * (HIGH finding, PR #2278): a pre-#2264 delete can be delivered long after a newer {@code
     * MobileUnitUpdatedV1} has already reactivated the row, and applying it unconditionally would
     * silently stand down a unit that is back in service. A stale delete is ignored; an accepted
     * one marks the row inactive and stores its version.
     */
    private void applyMobileUnitDeleted(JsonNode envelope) {
        MobileUnitDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), MobileUnitDeletedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        extMobileUnitReplicaRepository.findById(payload.mobileUnitId()).ifPresent(existing -> {
            if (ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
                log.debug(
                        "Ignoring stale mobile unit delete mobileUnitId={} version={} held={}",
                        payload.mobileUnitId(),
                        aggregateVersion,
                        existing.getAggregateVersion());
                return;
            }
            existing.setActive(false);
            existing.setAggregateVersion(aggregateVersion);
            existing.setUpdatedAt(Instant.now(clock));
            extMobileUnitReplicaRepository.save(existing);
        });
    }

    private void applyLocationUpdated(JsonNode envelope) {
        JsonNode payloadNode = envelope.path("payload");
        LocationUpdatedV1 payload = objectMapper.treeToValue(payloadNode, LocationUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtLocationReplica existing =
                extLocationReplicaRepository.findById(payload.locationId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        // timezone/operatingHours/holidayClosures/both buffers were added to this fact after this
        // listener started running (#2023/#2021). A pre-change producer's serialized event has no
        // such field at all, and rebuilding the row straight from the payload would read that
        // absence as an explicit null and overwrite an already-replicated value during a rolling
        // deploy or replay of an older stored event (#2023 F2). mergeField keeps the existing value
        // when the field is genuinely absent from the raw envelope, and only clears it when the
        // fact carries an explicit JSON null.
        extLocationReplicaRepository.save(ExtLocationReplica.builder()
                .locationId(payload.locationId())
                .code(payload.code())
                .name(payload.name())
                .active(payload.active())
                .aggregateVersion(aggregateVersion)
                .syncedAt(Instant.now(clock))
                .timezone(mergeField(
                        payloadNode, "timezone", payload.timezone(), existing == null ? null : existing.getTimezone()))
                .operatingHours(mergeField(
                        payloadNode,
                        "operatingHours",
                        serializeJson(payload.operatingHours()),
                        existing == null ? null : existing.getOperatingHours()))
                .holidayClosures(mergeField(
                        payloadNode,
                        "holidayClosures",
                        serializeJson(payload.holidayClosures()),
                        existing == null ? null : existing.getHolidayClosures()))
                .checkInBufferMinutes(mergeField(
                        payloadNode,
                        "checkInBufferMinutes",
                        payload.checkInBufferMinutes(),
                        existing == null ? null : existing.getCheckInBufferMinutes()))
                .cleanupBufferMinutes(mergeField(
                        payloadNode,
                        "cleanupBufferMinutes",
                        payload.cleanupBufferMinutes(),
                        existing == null ? null : existing.getCleanupBufferMinutes()))
                .build());

        // The fact carries the child's full typed parent-edge set — replace, don't merge.
        // A null list means the producer predates the field; leave existing edges untouched.
        List<LocationUpdatedV1.ParentRef> parents = payload.parents();
        if (parents != null) {
            extLocationParentReplicaRepository.deleteByChildId(payload.locationId());
            parents.forEach(edge -> extLocationParentReplicaRepository.save(ExtLocationParentReplica.builder()
                    .childId(payload.locationId())
                    .parentId(edge.parentId())
                    .parentType(edge.parentType())
                    .build()));
        }
        // Edges (or the row itself) may have changed: rebuild the scope ancestor sets for this
        // location and everything replicated beneath it (ADR-0061 §2, #1872).
        locationHierarchyService.recomputeAncestors(payload.locationId());
        log.info("Updated ext_location locationId={} version={}", payload.locationId(), aggregateVersion);
    }

    private void applyLocationDeleted(JsonNode envelope) {
        LocationDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), LocationDeletedV1.class);
        extLocationReplicaRepository.deleteById(payload.locationId());
        extLocationParentReplicaRepository.deleteByChildId(payload.locationId());
        log.info("Deleted ext_location locationId={}", payload.locationId());
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

    /**
     * Snapshots a fact's list field as raw JSON text, preserving the null-versus-empty distinction
     * load-bearing for {@code operatingHours} / {@code holidayClosures} (#2023,
     * DECISION-LOCATION-004/005): a {@code null} list stores {@code null} ("never configured"), an
     * empty list stores {@code "[]"} ("configured as empty"), and the two must never collapse into
     * one another.
     */
    private @Nullable String serializeJson(@Nullable List<?> list) {
        return list == null ? null : objectMapper.writeValueAsString(list);
    }

    /**
     * Distinguishes a field <em>absent</em> from the raw envelope (a pre-change producer that
     * predates the field entirely) from one carrying an <em>explicit</em> JSON {@code null} (#2023
     * F2). {@code JsonNode.has} is true for either a present non-null value or an explicit
     * {@code null} node, and false only when the field is missing outright — exactly the "was this
     * field ever serialized" question a rolling deploy or replay of an older stored event needs
     * answered before applying it. Absent keeps whatever this replica already holds; present
     * (including an explicit null) always takes {@code newValue}, clearing the column when that is
     * what the fact says.
     */
    private <T> @Nullable T mergeField(
            JsonNode payloadNode, String fieldName, @Nullable T newValue, @Nullable T existingValue) {
        return payloadNode.has(fieldName) ? newValue : existingValue;
    }
}

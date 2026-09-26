package com.positivity.workorder.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.location.BayDeletedV1;
import com.positivity.domainevents.location.BaySpecialtyMapUpdatedV1;
import com.positivity.domainevents.location.BayUpdatedV1;
import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.location.MobileUnitDeletedV1;
import com.positivity.domainevents.location.MobileUnitUpdatedV1;
import com.positivity.workorder.internal.entity.ExtBayReplica;
import com.positivity.workorder.internal.entity.ExtBaySpecialtyMapReplica;
import com.positivity.workorder.internal.entity.ExtBayTypeReplica;
import com.positivity.workorder.internal.entity.ExtLocationParentReplica;
import com.positivity.workorder.internal.entity.ExtLocationReplica;
import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ExtBayReplicaRepository;
import com.positivity.workorder.internal.repository.ExtBaySpecialtyMapReplicaRepository;
import com.positivity.workorder.internal.repository.ExtBayTypeReplicaRepository;
import com.positivity.workorder.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.workorder.internal.repository.ExtLocationReplicaRepository;
import com.positivity.workorder.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code location.events.v1} into this module's location-domain replicas — {@code ext_location}
 * (ADR-0044 §6, #892), replacing the retired synchronous {@code LocationClient} tax-address lookup, plus
 * {@code ext_bay} and {@code ext_mobile_unit} (#1656).
 *
 * <p>Same contract as {@link CustomerEventsListener}: {@code processed_events} idempotency in
 * the apply transaction, strictly-below stale guard on the emission-timestamp
 * {@code aggregateVersion}, transient DB errors rethrown for container retry/DLQ.
 *
 * <p>This module applies the location, bay and mobile-unit facts on the topic — storage-location
 * facts are ignored, but their eventIds are still recorded in {@code processed_events}: the owner's
 * manifest counts every fact in the window, so skipping the record would read as permanent
 * drift and trigger useless replays.
 *
 * <p>Bay and mobile-unit facts (#1656) feed {@code ext_bay} / {@code ext_mobile_unit}, which give
 * the dispatch board resource identity it previously had no lawful way to obtain. pos-location
 * publishes both families as of issue #1668 — see {@link com.positivity.domainevents.location}.
 * Handling stays non-fatal regardless: an unknown or absent event type falls through to the
 * {@code processed_events} insert like any other ignored fact, and the dashboard renders an empty
 * replica as "no units configured" rather than failing, which is what a consumer sees before the
 * owner's backfill reaches it.
 *
 * <p>Because a wrong-shaped payload would otherwise be indistinguishable from that silence, it
 * must fail <em>loudly</em> rather than merging into it. Both failure
 * modes are therefore loud and neither writes a row: a missing identifier throws out of the
 * record's compact constructor as a {@link DatabindException}, and a payload that binds but carries
 * no site scope is refused by {@link #requireSiteScope}. Both are counted on
 * {@code replica.payload.rejected} and logged at ERROR.
 *
 * <p>Each location fact also refreshes the materialised location-scope ancestor sets
 * (ADR-0061 §2, #1878): the child's typed parent edges are replaced from the fact, then
 * {@link LocationHierarchyService#recomputeAncestors} rebuilds the sets for the location and every
 * replicated descendant — so a re-parented node propagates, and a parent arriving after its
 * children pushes its ancestry down to them. Ingestion never fails closed on a parent the replica
 * has not seen yet; the scope check does.
 *
 * <p>Transaction shape (#2146): the handler and its {@code processed_events} mark commit together
 * in a transaction of their own ({@code REQUIRES_NEW}) rather than the listener's, so there is no
 * at-least-once window. A permanent failure rolls back only that work (row, edges and recompute)
 * instead of marking a shared transaction rollback-only through the {@code @Transactional}
 * recompute, and the failed record's mark is written in a separate transaction; transient failures
 * still propagate for container retry.
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
 * replica (nothing has arrived yet) makes
 * {@link ExtBaySpecialtyMapReplicaRepository#existsByOperationCode} answer {@code false} for every
 * operation code — no operation is specialty — which is exactly today's pre-replica behaviour;
 * wiring that read into workorder placement is a later story. {@code ext_bay.acceptsGeneralWork}
 * (see {@link #applyBayUpdated}) is a separate, additive {@code BayUpdatedV1} field and is merged
 * independently of this map.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class LocationEventsListener {

    static final String OWNER = "location";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtLocationReplicaRepository extLocationReplicaRepository;
    private final ExtLocationParentReplicaRepository extLocationParentReplicaRepository;
    private final LocationHierarchyService locationHierarchyService;
    private final ExtBayReplicaRepository extBayReplicaRepository;
    private final ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;
    private final ExtBaySpecialtyMapReplicaRepository extBaySpecialtyMapReplicaRepository;
    private final ExtBayTypeReplicaRepository extBayTypeReplicaRepository;
    private final Counter payloadRejectedCounter;

    /** One transaction for the handler and its mark, one for a failed record's mark; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public LocationEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtLocationReplicaRepository extLocationReplicaRepository,
            ExtLocationParentReplicaRepository extLocationParentReplicaRepository,
            LocationHierarchyService locationHierarchyService,
            ExtBayReplicaRepository extBayReplicaRepository,
            ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository,
            ExtBaySpecialtyMapReplicaRepository extBaySpecialtyMapReplicaRepository,
            ExtBayTypeReplicaRepository extBayTypeReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extLocationReplicaRepository = extLocationReplicaRepository;
        this.extLocationParentReplicaRepository = extLocationParentReplicaRepository;
        this.locationHierarchyService = locationHierarchyService;
        this.extBayReplicaRepository = extBayReplicaRepository;
        this.extMobileUnitReplicaRepository = extMobileUnitReplicaRepository;
        this.extBaySpecialtyMapReplicaRepository = extBaySpecialtyMapReplicaRepository;
        this.extBayTypeReplicaRepository = extBayTypeReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. a malformed identifier)")
                        .tag("owner", OWNER)
                        .tag("entity", "location-events")
                        .register(registry);
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${workorder.kafka.location-events-topic:location.events.v1}",
            groupId = "${workorder.kafka.location-events-consumer-group:pos-workorder-location-events}")
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
                    case LocationUpdatedV1.EVENT_TYPE -> applyLocationUpdated(envelope);
                    case LocationDeletedV1.EVENT_TYPE -> applyLocationDeleted(envelope);
                    case BayUpdatedV1.EVENT_TYPE -> applyBayUpdated(envelope);
                    case BayDeletedV1.EVENT_TYPE -> applyBayDeleted(envelope);
                    case MobileUnitUpdatedV1.EVENT_TYPE -> applyMobileUnitUpdated(envelope);
                    case MobileUnitDeletedV1.EVENT_TYPE -> applyMobileUnitDeleted(envelope);
                    case BaySpecialtyMapUpdatedV1.EVENT_TYPE -> applyBaySpecialtyMapUpdated(envelope);
                    // Ignored types (e.g. storage-location facts) still fall through to the
                    // processed_events insert below — see the class javadoc.
                    default -> log.debug("Ignoring location event type={} eventId={}", eventType, eventId);
                }
                processedEventRepository.save(processedMark(eventId));
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException | MalformedFactException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed location event payload eventId={}: {}", eventId, e.getMessage(), e);
            recordFailure(eventId);
        } catch (Exception e) {
            log.warn("Skipping malformed location event eventId={}", eventId, e);
            recordFailure(eventId);
        }
    }

    /** A permanently failed record's mark, in its own transaction: the handler's rolled back. */
    private void recordFailure(String eventId) {
        handlerTransaction.executeWithoutResult(_ -> processedEventRepository.save(processedMark(eventId)));
    }

    private ProcessedEvent processedMark(String eventId) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build();
    }

    private void applyLocationUpdated(JsonNode envelope) {
        LocationUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), LocationUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtLocationReplica existing =
                extLocationReplicaRepository.findById(payload.locationId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extLocationReplicaRepository.save(ExtLocationReplica.builder()
                .locationId(payload.locationId())
                .name(payload.name())
                .active(payload.active())
                .addressLine1(payload.addressLine1())
                .addressLine2(payload.addressLine2())
                .city(payload.city())
                .region(payload.region())
                .postalCode(payload.postalCode())
                .country(payload.country())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());

        // The fact carries the child's full typed parent-edge set — replace, don't merge.
        // A null list means the producer predates the field; leave existing edges untouched.
        // (Same contract as pos-inventory's and pos-people's ext_location_parent replicas.)
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
        // location and everything replicated beneath it (ADR-0061 §2, #1878).
        locationHierarchyService.recomputeAncestors(payload.locationId());
        log.info("Updated ext_location locationId={} version={}", payload.locationId(), aggregateVersion);
    }

    private void applyLocationDeleted(JsonNode envelope) {
        LocationDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), LocationDeletedV1.class);
        extLocationReplicaRepository.deleteById(payload.locationId());
        extLocationParentReplicaRepository.deleteByChildId(payload.locationId());
        log.info("Deleted ext_location locationId={}", payload.locationId());
    }

    private void applyBayUpdated(JsonNode envelope) {
        BayUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), BayUpdatedV1.class);
        requireSiteScope(payload.locationId(), BayUpdatedV1.EVENT_TYPE, "locationId");
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
                .bayType(payload.bayType())
                .serviceCapabilityCodes(payload.serviceCapabilityCodes())
                .maxConcurrentVehicles(payload.maxConcurrentVehicles())
                .maxDutyClass(payload.maxDutyClass())
                // acceptsGeneralWork (DECISION-LOCATION-025, #2261) is additive within schema v1:
                // null - whether absent or an explicit JSON null - always means "the publisher
                // predates the field", never "no" (BayUpdatedV1 javadoc). Keep the already-replicated
                // value (or default true for a brand-new row) whenever the fact carries no boolean
                // here, exactly the gvwrClass guard style VehicleEventsListener uses.
                .acceptsGeneralWork(mergeAcceptsGeneralWork(payload.acceptsGeneralWork(), existing))
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_bay bayId={} version={}", payload.bayId(), aggregateVersion);
    }

    /**
     * A stray {@code location.bay.deleted}: pos-location no longer emits this fact
     * (DECISION-LOCATION-026, issue #2264) — retiring a bay now arrives as an ordinary {@code
     * BayUpdatedV1} with {@code status = RETIRED}, applied by {@link #applyBayUpdated} exactly like
     * any other status change. A replayed or long-delayed old event is handled safely rather than
     * ignored: the row, if still present, is marked inactive instead of removed, so an assignment
     * that already names this bay can still be named on the board.
     */
    private void applyBayDeleted(JsonNode envelope) {
        BayDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), BayDeletedV1.class);
        extBayReplicaRepository.findById(payload.bayId()).ifPresent(existing -> {
            existing.setActive(false);
            existing.setUpdatedAt(Instant.now(clock));
            extBayReplicaRepository.save(existing);
        });
        log.info("Marked ext_bay inactive on stray delete bayId={}", payload.bayId());
    }

    /**
     * {@code acceptsGeneralWork} on a bay row: {@code null} (absent or explicit JSON null) always
     * means "the publisher predates the field" (BayUpdatedV1 javadoc), so the already-replicated
     * value is kept, or the column default {@code true} stands for a brand-new row.
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
        MobileUnitUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), MobileUnitUpdatedV1.class);
        requireSiteScope(payload.baseLocationId(), MobileUnitUpdatedV1.EVENT_TYPE, "baseLocationId");
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
        log.info("Updated ext_mobile_unit mobileUnitId={} version={}", payload.mobileUnitId(), aggregateVersion);
    }

    /**
     * A stray {@code location.mobile-unit.deleted}: pos-location no longer emits this fact
     * (DECISION-LOCATION-026, issue #2264) — retiring a unit now arrives as an ordinary {@code
     * MobileUnitUpdatedV1} with {@code status = RETIRED}, applied by {@link #applyMobileUnitUpdated}
     * exactly like any other status change. A replayed or long-delayed old event is handled safely
     * rather than ignored: the row, if still present, is marked inactive instead of removed.
     */
    private void applyMobileUnitDeleted(JsonNode envelope) {
        MobileUnitDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), MobileUnitDeletedV1.class);
        extMobileUnitReplicaRepository.findById(payload.mobileUnitId()).ifPresent(existing -> {
            existing.setActive(false);
            existing.setUpdatedAt(Instant.now(clock));
            extMobileUnitReplicaRepository.save(existing);
        });
        log.info("Marked ext_mobile_unit inactive on stray delete mobileUnitId={}", payload.mobileUnitId());
    }

    /**
     * Refuses a bay or mobile-unit fact that carries no site scope (#1657).
     *
     * <p>pos-location owns the bay and mobile-unit contracts in
     * {@link com.positivity.domainevents.location} and publishes them as of issue #1668. The guard
     * remains because the failure it catches is silent: if a future producer change named the field
     * {@code siteId} rather than {@code locationId} — the name the sibling
     * {@code StorageLocationUpdatedV1} fact uses — the record would bind a null site and, without
     * this guard, a perfectly well-formed-looking row would land in {@code ext_bay} that the roster
     * query, which scopes by {@code location_id}, can never return. The dispatch panel would go
     * quietly empty with no error anywhere, and stay that way for as long as it took someone to
     * notice.
     *
     * <p>A wrong {@code bayId}/{@code mobileUnitId} field name is already loud — the record's
     * compact constructor throws and Jackson reports it as a {@link DatabindException}. This makes
     * the site-scope field equally loud, so a mis-shaped payload is always counted on
     * {@code replica.payload.rejected} and logged at ERROR rather than half-written. A resource
     * with no site cannot be dispatched from anywhere, so nothing of value is being rejected.
     *
     * @param siteId the site scope the payload bound
     * @param eventType the fact type, for the log line
     * @param field the field the owner is expected to publish the site scope in
     * @throws MalformedFactException when the site scope is absent
     */
    private static void requireSiteScope(UUID siteId, String eventType, String field) {
        if (siteId == null) {
            throw new MalformedFactException(eventType + " payload has no " + field
                    + "; the replica row would be invisible to the dispatch board. Check the producer's "
                    + "field names against BayUpdatedV1/MobileUnitUpdatedV1 in pos-domain-events.");
        }
    }

    /**
     * A fact whose payload bound without error but does not carry what the replica needs to be
     * usable (#1657). Handled exactly like a Jackson databind failure: counted on
     * {@code replica.payload.rejected}, logged at ERROR, and never written.
     */
    static final class MalformedFactException extends RuntimeException {
        MalformedFactException(String message) {
            super(message);
        }
    }

    /**
     * Whether an owner-published lifecycle status means "this resource can take work today" (#1656).
     *
     * <p>Neither {@code BayEntity} nor {@code MobileUnitEntity} carries a boolean active flag in
     * pos-location — the lifecycle lives entirely in {@code status}. So the replica's own
     * {@code active} column has to be <em>derived</em> here, and it is derived by allow-listing the
     * single value that means in service. A deny-list would be wrong for two different reasons at
     * once: a bay's status is a closed {@code ACTIVE} | {@code OUT_OF_SERVICE} pair today but is not
     * guaranteed to stay closed, and a mobile unit's status is a free-text column, so an unseen or
     * misspelled value would otherwise be read as "available" and put a van the shop cannot dispatch
     * onto the board. Absent, blank and unknown all mean not active.
     *
     * @param status the owner's status string, possibly {@code null}
     * @return true only for the {@code ACTIVE} token, in any casing
     */
    private static boolean isActiveStatus(String status) {
        return status != null && "ACTIVE".equalsIgnoreCase(status.strip());
    }
}

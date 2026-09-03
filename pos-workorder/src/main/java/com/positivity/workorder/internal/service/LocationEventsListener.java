package com.positivity.workorder.internal.service;

import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.workorder.internal.dto.location.BayDeletedV1;
import com.positivity.workorder.internal.dto.location.BayUpdatedV1;
import com.positivity.workorder.internal.dto.location.MobileUnitDeletedV1;
import com.positivity.workorder.internal.dto.location.MobileUnitUpdatedV1;
import com.positivity.workorder.internal.entity.ExtBayReplica;
import com.positivity.workorder.internal.entity.ExtLocationReplica;
import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ExtBayReplicaRepository;
import com.positivity.workorder.internal.repository.ExtLocationReplicaRepository;
import com.positivity.workorder.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
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
 * the dispatch board resource identity it previously had no lawful way to obtain. pos-location does
 * not publish those two fact families yet — see
 * {@link com.positivity.workorder.internal.dto.location} — so in production these branches are
 * simply never taken today. That is deliberate and must stay non-fatal: an unknown or absent event
 * type falls through to the {@code processed_events} insert like any other ignored fact, and the
 * dashboard renders an empty replica as "no units configured" rather than failing.
 *
 * <p>Because those two contracts are the consumer's provisional guess at a shape pos-location has
 * not published yet (issue #1668), a payload that arrives in the wrong shape must be
 * <em>distinguishable</em> from that expected silence rather than merging into it. Both failure
 * modes are therefore loud and neither writes a row: a missing identifier throws out of the
 * record's compact constructor as a {@link DatabindException}, and a payload that binds but carries
 * no site scope is refused by {@link #requireSiteScope}. Both are counted on
 * {@code replica.payload.rejected} and logged at ERROR.
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
    private final ExtBayReplicaRepository extBayReplicaRepository;
    private final ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;
    private final Counter payloadRejectedCounter;

    public LocationEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtLocationReplicaRepository extLocationReplicaRepository,
            ExtBayReplicaRepository extBayReplicaRepository,
            ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extLocationReplicaRepository = extLocationReplicaRepository;
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
            topics = "${workorder.kafka.location-events-topic:location.events.v1}",
            groupId = "${workorder.kafka.location-events-consumer-group:pos-workorder-location-events}")
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
                case LocationUpdatedV1.EVENT_TYPE -> applyLocationUpdated(envelope);
                case LocationDeletedV1.EVENT_TYPE -> applyLocationDeleted(envelope);
                case BayUpdatedV1.EVENT_TYPE -> applyBayUpdated(envelope);
                case BayDeletedV1.EVENT_TYPE -> applyBayDeleted(envelope);
                case MobileUnitUpdatedV1.EVENT_TYPE -> applyMobileUnitUpdated(envelope);
                case MobileUnitDeletedV1.EVENT_TYPE -> applyMobileUnitDeleted(envelope);
                // Ignored types (e.g. storage-location facts) still fall through to the
                // processed_events insert below — see the class javadoc.
                default -> log.debug("Ignoring location event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException | MalformedFactException e) {
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
        log.info("Updated ext_location locationId={} version={}", payload.locationId(), aggregateVersion);
    }

    private void applyLocationDeleted(JsonNode envelope) {
        LocationDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), LocationDeletedV1.class);
        extLocationReplicaRepository.deleteById(payload.locationId());
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
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_bay bayId={} version={}", payload.bayId(), aggregateVersion);
    }

    private void applyBayDeleted(JsonNode envelope) {
        BayDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), BayDeletedV1.class);
        extBayReplicaRepository.deleteById(payload.bayId());
        log.info("Deleted ext_bay bayId={}", payload.bayId());
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

    private void applyMobileUnitDeleted(JsonNode envelope) {
        MobileUnitDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), MobileUnitDeletedV1.class);
        extMobileUnitReplicaRepository.deleteById(payload.mobileUnitId());
        log.info("Deleted ext_mobile_unit mobileUnitId={}", payload.mobileUnitId());
    }

    /**
     * Refuses a bay or mobile-unit fact that carries no site scope (#1657).
     *
     * <p>The bay and mobile-unit contracts in {@link com.positivity.workorder.internal.dto.location}
     * are the consumer's provisional statement of a contract pos-location does not publish yet
     * (issue #1668). If the real producer names the field {@code siteId} rather than
     * {@code locationId}, the record binds a null site and — without this guard — a perfectly
     * well-formed-looking row lands in {@code ext_bay} that the roster query, which scopes by
     * {@code location_id}, can never return. The dispatch panel would then be empty with no error
     * anywhere: indistinguishable from "pos-location has not started publishing", which is the
     * expected state today, and therefore invisible for as long as it takes someone to notice.
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
                    + "; the replica row would be invisible to the dispatch board. The bay and mobile-unit "
                    + "fact contracts are provisional until pos-location publishes them (issue #1668) — "
                    + "check the producer's field names against BayUpdatedV1/MobileUnitUpdatedV1.");
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

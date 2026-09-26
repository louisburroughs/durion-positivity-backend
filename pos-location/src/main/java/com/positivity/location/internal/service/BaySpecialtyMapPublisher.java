package com.positivity.location.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.location.BaySpecialtyMapUpdatedV1;
import com.positivity.location.internal.config.OutboxEventWriter;
import com.positivity.location.internal.entity.BaySpecialtyMapVersionEntity;
import com.positivity.location.internal.entity.BaySpecialtyOperationEntity;
import com.positivity.location.internal.enums.BayType;
import com.positivity.location.internal.repository.BaySpecialtyMapVersionRepository;
import com.positivity.location.internal.repository.BaySpecialtyOperationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes a tenant's whole bay specialty map on {@code location.events.v1} through the same
 * transactional outbox {@link LocationFactPublisher} uses (DECISION-LOCATION-025, ADR-0044 §4).
 *
 * <p>Every call runs with a tenant already bound ({@link com.positivity.tenancy.TenantContext}) —
 * from {@code TenantEventsListener}/{@code BaySpecialtyMapProvisioningService} on
 * {@code tenant.created}, or from {@code BaySpecialtyMapStartupPublisher}'s
 * {@code TenantIterator} sweep at boot — and reads {@code bay_specialty_operation} for that bound
 * tenant only.
 *
 * <p>{@link #publishChanged} is for an actual change to the map: it bumps the tenant's
 * {@link BaySpecialtyMapVersionEntity} row and publishes at the new version. {@link
 * #publishCurrent} is for the once-per-tenant startup republish: it never bumps, since republishing
 * an unchanged map is not itself a change, and reads whatever version is already on record (1 if
 * the tenant has never had a version row, which a tenant provisioned before this fact existed could
 * legitimately not have). Both publish the complete map — one entry per {@link BayType}, including
 * a type with no specialty rows, whose {@code operationCodes} is then empty — never a delta.
 */
@Slf4j
@Component
public class BaySpecialtyMapPublisher {

    private static final String SOURCE = "pos-location";

    private final BaySpecialtyOperationRepository operationRepository;
    private final BaySpecialtyMapVersionRepository versionRepository;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final Clock clock;
    private final String eventsTopic;

    public BaySpecialtyMapPublisher(
            BaySpecialtyOperationRepository operationRepository,
            BaySpecialtyMapVersionRepository versionRepository,
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            Clock clock,
            @Value("${pos.location.kafka.events-topic:location.events.v1}") String eventsTopic) {
        this.operationRepository = operationRepository;
        this.versionRepository = versionRepository;
        this.outboxEventWriter = outboxEventWriter;
        this.clock = clock;
        this.eventsTopic = eventsTopic;
    }

    /**
     * Bump the bound tenant's map version and publish the full map at the new version. Callers use
     * this only when the map actually changed for the tenant (today: first-time provisioning from
     * the platform template).
     */
    @Transactional
    public void publishChanged(@NonNull UUID tenantId) {
        if (outboxEventWriter.getIfAvailable() == null) {
            return;
        }
        publish(tenantId, nextVersion());
    }

    /**
     * Publish the full map at whatever version is already on record, without bumping it — the
     * once-per-tenant startup sweep so a replica that missed live traffic, or is standing up for the
     * first time, still converges.
     */
    @Transactional
    public void publishCurrent(@NonNull UUID tenantId) {
        if (outboxEventWriter.getIfAvailable() == null) {
            return;
        }
        publish(tenantId, currentVersion());
    }

    private long nextVersion() {
        BaySpecialtyMapVersionEntity existing =
                versionRepository.findFirstByOrderByIdAsc().orElse(null);
        long next = existing == null ? 1L : existing.getVersion() + 1;
        BaySpecialtyMapVersionEntity row = existing == null
                ? BaySpecialtyMapVersionEntity.builder().version(next).build()
                : existing;
        row.setVersion(next);
        row.setUpdatedAt(Instant.now(clock));
        versionRepository.save(row);
        return next;
    }

    private long currentVersion() {
        return versionRepository
                .findFirstByOrderByIdAsc()
                .map(BaySpecialtyMapVersionEntity::getVersion)
                .orElse(1L);
    }

    private void publish(UUID tenantId, long version) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        BaySpecialtyMapUpdatedV1 payload = new BaySpecialtyMapUpdatedV1(tenantId, buildEntries(), version);
        DomainEventEnvelope<Object> envelope = DomainEventEnvelope.of(
                BaySpecialtyMapUpdatedV1.EVENT_TYPE,
                BaySpecialtyMapUpdatedV1.SCHEMA_VERSION,
                tenantId,
                version,
                SOURCE,
                null,
                null,
                payload,
                clock);
        writer.publish(eventsTopic, envelope);
        log.info(
                "Queued {} for tenant {} version={} entries={}",
                BaySpecialtyMapUpdatedV1.EVENT_TYPE,
                tenantId,
                version,
                payload.entries().size());
    }

    /**
     * One entry per {@link BayType}, in enum order, so the fact never omits a type that simply has
     * no specialty rows (D14 rule 3: a general bay declares nothing, which is exactly what an empty
     * {@code operationCodes} says here too).
     */
    private List<BaySpecialtyMapUpdatedV1.Entry> buildEntries() {
        Map<String, List<String>> codesByType = new TreeMap<>();
        for (BaySpecialtyOperationEntity row : operationRepository.findAll()) {
            codesByType
                    .computeIfAbsent(row.getBayType(), key -> new ArrayList<>())
                    .add(row.getOperationCode());
        }
        List<BaySpecialtyMapUpdatedV1.Entry> entries = new ArrayList<>(BayType.values().length);
        for (BayType bayType : BayType.values()) {
            List<String> codes = codesByType.getOrDefault(bayType.name(), List.of());
            entries.add(new BaySpecialtyMapUpdatedV1.Entry(
                    bayType.name(), List.copyOf(codes), bayType.acceptsGeneralWork()));
        }
        return List.copyOf(entries);
    }
}

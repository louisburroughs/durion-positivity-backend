package com.positivity.workorder.internal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.workorder.internal.config.OutboxEventWriter;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes {@code workorder.workorder.updated} facts to the transactional outbox (ADR-0044 §6,
 * issue #897 Phase 5.1).
 *
 * <p>Mutation sites call {@link #markChanged(UUID)} after saving; the publisher deduplicates ids
 * per transaction and emits ONE snapshot fact per touched workorder at {@code beforeCommit},
 * built from the final persisted state (workorder row + full part-line set). When Kafka
 * publishing is disabled the outbox writer bean is absent and every call is a no-op, so callers
 * never need their own guard.
 *
 * <p>The fact also carries the assignment block — location, resource id and type, mechanic ids,
 * promise time and scheduled date (#1658) — so pos-shop-manager's shop dashboard can render every
 * bay and mobile unit at a location from a local {@code ext_workorder} replica. Without it a
 * consumer knows a workorder changed but not what it occupies or who is on it, and would have to
 * call back into this module synchronously, which ADR-0044 R1 forbids.
 *
 * <p>{@code Workorder} carries a JPA {@code @Version}, and the envelope's {@code aggregateVersion}
 * is that counter (#1486): it strictly increments on every committed mutation, so — unlike the
 * retired {@code Instant.now(clock)}-stamped emission timestamp — two mutations landing in the same
 * millisecond can never tie. Migration V21 seeded it from wall-clock millis at migration time, so
 * the published sequence continues above every version consumers already hold. The persistence
 * context is flushed before reading it, so the increment Hibernate is about to apply is already
 * reflected in the emitted fact. A mutation that only touches {@code workorder_part} /
 * {@code workorder_service_line} rows without dirtying the {@code workorder} row itself must dirty
 * it first (bumping {@code updatedAt}) before calling {@link #markChanged(UUID)}, or the flush here
 * has no pending {@code @Version} increment to pick up.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkorderFactPublisher {

    private static final Object TX_RESOURCE_KEY = WorkorderFactPublisher.class;

    /**
     * Reads the owner's {@code mechanic_ids} JSON array column. Declared static so widening the
     * fact (#1658) did not change this component's constructor, which several tests build by hand.
     */
    private static final ObjectMapper MECHANIC_IDS_MAPPER = JsonMapper.builder().build();

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final WorkorderRepository workorderRepository;
    private final WorkorderPartRepository workorderPartRepository;
    private final WorkorderServiceRepository workorderServiceRepository;
    private final EntityManager entityManager;

    /** Mark a workorder as changed in the current transaction; one fact is emitted at commit. */
    public void markChanged(@NonNull UUID workorderId) {
        if (outboxEventWriter.getIfAvailable() == null
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        @SuppressWarnings("unchecked")
        Set<UUID> pending = (Set<UUID>) TransactionSynchronizationManager.getResource(TX_RESOURCE_KEY);
        if (pending == null) {
            pending = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(TX_RESOURCE_KEY, pending);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    if (!readOnly) {
                        publishPending();
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(TX_RESOURCE_KEY);
                }
            });
        }
        pending.add(workorderId);
    }

    private void publishPending() {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        @SuppressWarnings("unchecked")
        Set<UUID> pending = (Set<UUID>) TransactionSynchronizationManager.getResource(TX_RESOURCE_KEY);
        if (writer == null || pending == null) {
            return;
        }
        // Flushed before reading aggregateVersion: mutations from this transaction are still
        // pending in the persistence context, and flushing here forces Hibernate to apply every
        // pending @Version increment so each emitted fact carries the version its row is about to
        // commit as, not the one it held before this write (#1486).
        entityManager.flush();
        for (UUID workorderId : pending) {
            Workorder workorder = workorderRepository.findById(workorderId).orElse(null);
            if (workorder == null) {
                log.warn("Skipping workorder fact for {}: row not found at commit", workorderId);
                continue;
            }
            List<WorkorderUpdatedV1.PartLine> parts = workorderPartRepository.findByWorkorderId(workorderId).stream()
                    .map(WorkorderFactPublisher::toPartLine)
                    .toList();
            List<WorkorderUpdatedV1.ServiceLine> services =
                    workorderServiceRepository.findByWorkOrder_Id(workorderId).stream()
                            .map(WorkorderFactPublisher::toServiceLine)
                            .toList();
            WorkorderUpdatedV1 payload = new WorkorderUpdatedV1(
                    workorder.getId(),
                    workorder.getWorkorderNumber(),
                    workorder.getStatus() != null ? workorder.getStatus().name() : null,
                    workorder.getShopId(),
                    workorder.getCustomerId(),
                    workorder.getVehicleId(),
                    workorder.getInvoiceId(),
                    parts,
                    services,
                    workorder.getCreatedAt(),
                    workorder.getUpdatedAt(),
                    workorder.getLocationId(),
                    workorder.getResourceId(),
                    workorder.getResourceType() != null
                            ? workorder.getResourceType().name()
                            : null,
                    parseMechanicIds(workorder.getMechanicIds()),
                    // The owner has no promise-time field yet (#1658); the contract carries the
                    // slot so consumers can sort on it the day the column exists.
                    null,
                    workorder.getScheduledDate());
            writer.publish(
                    WorkorderUpdatedV1.EVENT_TYPE,
                    WorkorderUpdatedV1.SCHEMA_VERSION,
                    workorderId,
                    workorder.getVersion(),
                    payload);
        }
    }

    /**
     * Parses the owner's {@code mechanic_ids} JSON array into typed ids (#1658). A malformed or
     * non-UUID entry is dropped rather than failing the commit: this runs at {@code beforeCommit},
     * so throwing here would roll back the business transaction that produced the fact.
     */
    private static List<UUID> parseMechanicIds(String mechanicIdsJson) {
        if (mechanicIdsJson == null || mechanicIdsJson.isBlank()) {
            return List.of();
        }
        List<String> raw;
        try {
            raw = MECHANIC_IDS_MAPPER.readValue(mechanicIdsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Unreadable mechanic_ids JSON on workorder fact: {}", mechanicIdsJson, e);
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(raw.size());
        for (String candidate : raw) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(candidate.trim()));
            } catch (IllegalArgumentException e) {
                log.warn("Skipping non-UUID mechanic id '{}' on workorder fact", candidate);
            }
        }
        return List.copyOf(ids);
    }

    private static WorkorderUpdatedV1.PartLine toPartLine(@NonNull WorkorderPart part) {
        return new WorkorderUpdatedV1.PartLine(
                part.getId(),
                part.getProductEntityId(),
                part.getDescription(),
                part.getQuantity(),
                part.getUnitPrice(),
                part.getLineTotal(),
                part.getPhotoEvidenceUrl(),
                part.getDeclined(),
                part.getReturnable());
    }

    private static WorkorderUpdatedV1.ServiceLine toServiceLine(@NonNull WorkorderServiceLine service) {
        return new WorkorderUpdatedV1.ServiceLine(
                service.getId(),
                service.getDescription(),
                service.getQuantity(),
                service.getUnitPrice(),
                service.getLineTotal(),
                service.getPhotoEvidenceUrl(),
                service.getDeclined());
    }
}

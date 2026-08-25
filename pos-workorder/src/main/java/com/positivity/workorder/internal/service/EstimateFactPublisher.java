package com.positivity.workorder.internal.service;

import com.positivity.domainevents.workorder.EstimateUpdatedV1;
import com.positivity.workorder.internal.config.OutboxEventWriter;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import jakarta.persistence.EntityManager;
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
 * Publishes {@code workorder.estimate.updated} snapshot facts to the transactional outbox (order
 * parity story E1, #1077), mirroring {@link WorkorderFactPublisher}: mutation sites call
 * {@link #markChanged(UUID)} after saving; ids are deduplicated per transaction and ONE snapshot
 * per touched estimate is emitted at {@code beforeCommit} from the final persisted state. First
 * consumer: pos-order's {@code ext_estimate} replica feeding source-document import (spec R7.1).
 * No-op when Kafka publishing is disabled.
 *
 * <p>{@code Estimate} carries a JPA {@code @Version} named {@code aggregateVersion} (#1486) — not
 * the plain {@code version} field, which is an unrelated business revision counter — and the
 * envelope's {@code aggregateVersion} is that counter: it strictly increments on every committed
 * mutation, so — unlike the retired {@code Instant.now(clock)}-stamped emission timestamp — two
 * mutations landing in the same millisecond can never tie. Migration V21 seeded it from wall-clock
 * millis at migration time, so the published sequence continues above every version consumers
 * already hold. The persistence context is flushed before reading it, so the increment Hibernate
 * is about to apply is already reflected in the emitted fact. A mutation that only touches
 * {@code estimate_item} rows without dirtying the {@code estimate} row itself must dirty it first
 * (bumping {@code updatedAt}) before calling {@link #markChanged(UUID)}, or the flush here has no
 * pending {@code @Version} increment to pick up.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EstimateFactPublisher {

    private static final Object TX_RESOURCE_KEY = EstimateFactPublisher.class;

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository estimateItemRepository;
    private final EntityManager entityManager;

    /** Mark an estimate as changed in the current transaction; one fact is emitted at commit. */
    public void markChanged(@NonNull UUID estimateId) {
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
        pending.add(estimateId);
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
        for (UUID estimateId : pending) {
            Estimate estimate = estimateRepository.findById(estimateId).orElse(null);
            if (estimate == null) {
                log.warn("Skipping estimate fact for {}: row not found at commit", estimateId);
                continue;
            }
            List<EstimateUpdatedV1.Item> items =
                    estimateItemRepository.findByEstimate_IdAndDeletedFalse(estimateId).stream()
                            .map(EstimateFactPublisher::toItem)
                            .toList();
            EstimateUpdatedV1 payload = new EstimateUpdatedV1(
                    estimate.getId(),
                    estimate.getEstimateNumber(),
                    estimate.getStatus() != null ? estimate.getStatus().name() : null,
                    estimate.getLocationId(),
                    estimate.getCustomerId(),
                    estimate.getVehicleId(),
                    estimate.getSubtotal(),
                    estimate.getTaxAmount(),
                    estimate.getTotal(),
                    items,
                    estimate.getCreatedAt(),
                    estimate.getUpdatedAt());
            writer.publish(
                    EstimateUpdatedV1.EVENT_TYPE,
                    EstimateUpdatedV1.SCHEMA_VERSION,
                    estimateId,
                    estimate.getAggregateVersion(),
                    payload);
        }
    }

    private static EstimateUpdatedV1.Item toItem(@NonNull EstimateItem item) {
        return new EstimateUpdatedV1.Item(
                item.getId(),
                item.getItemType() != null ? item.getItemType().name() : null,
                item.getDescription(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getLineTotal(),
                item.getProductId(),
                item.getServiceId(),
                item.getApprovalStatus() != null ? item.getApprovalStatus().name() : null,
                item.getDeleted());
    }
}

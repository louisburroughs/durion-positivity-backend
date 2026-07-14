package com.positivity.workorder.internal.service;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.workorder.internal.config.OutboxEventWriter;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkorderFactPublisher {

    private static final Object TX_RESOURCE_KEY = WorkorderFactPublisher.class;

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final WorkorderRepository workorderRepository;
    private final WorkorderPartRepository workorderPartRepository;

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
        for (UUID workorderId : pending) {
            Workorder workorder = workorderRepository.findById(workorderId).orElse(null);
            if (workorder == null) {
                log.warn("Skipping workorder fact for {}: row not found at commit", workorderId);
                continue;
            }
            List<WorkorderUpdatedV1.PartLine> parts = workorderPartRepository.findByWorkorderId(workorderId).stream()
                    .map(WorkorderFactPublisher::toPartLine)
                    .toList();
            WorkorderUpdatedV1 payload = new WorkorderUpdatedV1(
                    workorder.getId(),
                    workorder.getWorkorderNumber(),
                    workorder.getStatus() != null ? workorder.getStatus().name() : null,
                    workorder.getShopId(),
                    workorder.getCustomerId(),
                    workorder.getInvoiceId(),
                    parts,
                    workorder.getCreatedAt(),
                    workorder.getUpdatedAt());
            writer.publish(WorkorderUpdatedV1.EVENT_TYPE, workorderId.toString(), payload);
        }
    }

    private static WorkorderUpdatedV1.PartLine toPartLine(@NonNull WorkorderPart part) {
        return new WorkorderUpdatedV1.PartLine(part.getId(), part.getProductEntityId(), part.getQuantity());
    }
}

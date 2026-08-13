package com.positivity.workorder.internal.service;

import com.positivity.domainevents.workorder.WorkorderServiceCompletedV1;
import com.positivity.domainevents.workorder.WorkorderServiceLineDeclinedV1;
import com.positivity.workorder.internal.config.OutboxEventWriter;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
 * Publishes the CRM service-history facts (FI-3, issue #1133) to the transactional outbox at
 * workorder completion: one {@link WorkorderServiceCompletedV1} for the workorder, plus one
 * {@link WorkorderServiceLineDeclinedV1} per declined service line.
 *
 * <p>The completion path calls {@link #markCompleted(UUID)} after saving; this publisher emits the
 * facts once at {@code beforeCommit}, built from the final persisted state (COMPLETED status,
 * completedAt, the full service/part line set). When Kafka publishing is disabled the outbox
 * writer bean is absent and every call is a no-op, mirroring {@link WorkorderFactPublisher}, so
 * callers never need their own guard.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceCompletionFactPublisher {

    private static final Object TX_RESOURCE_KEY = ServiceCompletionFactPublisher.class;

    private final Clock clock;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final WorkorderRepository workorderRepository;
    private final WorkorderPartRepository workorderPartRepository;
    private final WorkorderServiceRepository workorderServiceRepository;

    /** Mark a workorder as completed in the current transaction; facts are emitted at commit. */
    public void markCompleted(@NonNull UUID workorderId) {
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
                log.warn("Skipping service-completion fact for {}: row not found at commit", workorderId);
                continue;
            }
            List<WorkorderServiceLine> services = workorderServiceRepository.findByWorkOrder_Id(workorderId);
            List<WorkorderPart> parts = loadParts(workorderId);
            Instant completedAt = workorder.getCompletedAt() != null ? workorder.getCompletedAt() : Instant.now(clock);

            publishCompleted(writer, workorder, services, parts, completedAt);
            publishDeclines(writer, workorder, services, completedAt);
        }
    }

    private void publishCompleted(
            OutboxEventWriter writer,
            Workorder workorder,
            List<WorkorderServiceLine> services,
            List<WorkorderPart> parts,
            Instant completedAt) {
        List<WorkorderServiceCompletedV1.PerformedService> performed = services.stream()
                .filter(line -> isPerformed(line.getDeclined(), line.getStatus()))
                .map(line -> new WorkorderServiceCompletedV1.PerformedService(
                        line.getId(),
                        line.getServiceEntityId(),
                        line.getDescription(),
                        line.getQuantity(),
                        line.getUnitPrice(),
                        line.getLineTotal()))
                .toList();

        BigDecimal serviceTotal = services.stream()
                .filter(line -> isPerformed(line.getDeclined(), line.getStatus()))
                .map(WorkorderServiceLine::getLineTotal)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal partTotal = parts.stream()
                .filter(part -> isPerformed(part.getDeclined(), part.getStatus()))
                .map(WorkorderPart::getLineTotal)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        WorkorderServiceCompletedV1 payload = new WorkorderServiceCompletedV1(
                workorder.getId(),
                workorder.getWorkorderNumber(),
                workorder.getCustomerId(),
                workorder.getVehicleId(),
                workorder.getShopId(),
                workorder.getInvoiceId(),
                completedAt,
                serviceTotal.add(partTotal),
                performed);
        writer.publish(
                WorkorderServiceCompletedV1.EVENT_TYPE,
                WorkorderServiceCompletedV1.SCHEMA_VERSION,
                workorder.getId().toString(),
                payload);
    }

    private void publishDeclines(
            OutboxEventWriter writer, Workorder workorder, List<WorkorderServiceLine> services, Instant completedAt) {
        for (WorkorderServiceLine line : services) {
            if (!isDeclined(line.getDeclined())) {
                continue;
            }
            WorkorderServiceLineDeclinedV1 payload = new WorkorderServiceLineDeclinedV1(
                    workorder.getId(),
                    workorder.getWorkorderNumber(),
                    line.getId(),
                    workorder.getCustomerId(),
                    workorder.getVehicleId(),
                    line.getServiceEntityId(),
                    line.getDescription(),
                    line.getDeclineReason(),
                    completedAt);
            // Keyed by the workorder id so a workorder's facts stay ordered on one partition.
            writer.publish(
                    WorkorderServiceLineDeclinedV1.EVENT_TYPE,
                    WorkorderServiceLineDeclinedV1.SCHEMA_VERSION,
                    workorder.getId().toString(),
                    payload);
        }
    }

    /**
     * The full part set for the workorder, deduped by id — parts hang off either the workorder
     * directly or a service line, and completion totals must count both to stay consistent with
     * invoice / billable-scope totals (mirrors WorkorderInvoiceServiceImpl).
     */
    private List<WorkorderPart> loadParts(UUID workorderId) {
        List<WorkorderPart> parts = new ArrayList<>();
        parts.addAll(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId));
        parts.addAll(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(workorderId));
        Set<UUID> seen = new HashSet<>();
        return parts.stream()
                .filter(part -> part.getId() == null || seen.add(part.getId()))
                .toList();
    }

    /**
     * A line counts toward the completed-service snapshot only when it was actually performed:
     * not declined and not CANCELLED. Workorder billable totals exclude CANCELLED (see
     * WorkorderInvoiceServiceImpl), so the emitted completion facts must too, or CRM's amount
     * would diverge from the invoice.
     */
    private static boolean isPerformed(Boolean declined, WorkorderItemStatus status) {
        return !isDeclined(declined) && status != WorkorderItemStatus.CANCELLED;
    }

    private static boolean isDeclined(Boolean declined) {
        return Boolean.TRUE.equals(declined);
    }
}

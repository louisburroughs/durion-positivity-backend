package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Detects interfering stock movements in a cycle-count window (odoo-parity I2,
 * issue #1026 — Odoo's {@code is_outdated} conflict flag).
 *
 * <p>The task's {@code expectedQuantity} is snapshotted at task creation
 * (tasks are seeded with the system quantity of the moment; there is no
 * task-creation service that derives it from the ledger), so a like-for-like
 * absolute comparison of snapshot vs current on-hand is not possible. The
 * honest equivalent is the <em>window delta</em>: the net sum of
 * on-hand-affecting ledger entries for the task's SKU (location-scoped when
 * the task's {@code binLocation} is a location UUID) recorded after
 * {@code createdAt}. A non-zero delta means movements occurred while the count
 * was in flight and the snapshot no longer matches what the auditor counted
 * against.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CycleCountConflictDetector {

    private final InventoryLedgerEntryRepository ledgerRepository;
    private final CycleCountTaskRepository taskRepository;

    /**
     * Net on-hand delta from ledger movements recorded after the task snapshot
     * was taken. Non-zero ⇒ the count window was interfered with.
     */
    @Transactional(readOnly = true)
    public @NonNull BigDecimal movementDeltaSinceSnapshot(@NonNull CycleCountTask task) {
        Optional<UUID> locationId = locationIdOf(task);
        BigDecimal delta = locationId
                .map(location -> ledgerRepository.sumChangeForStockItemAtLocationSince(
                        task.getItemSku(),
                        location,
                        InventoryLedgerEventType.onHandAffectingTypes(),
                        task.getCreatedAt()))
                .orElseGet(() -> ledgerRepository.sumChangeForStockItemSince(
                        task.getItemSku(), InventoryLedgerEventType.onHandAffectingTypes(), task.getCreatedAt()));
        return Quantities.nz(delta);
    }

    /**
     * The ledger entries interfering with the task's count window, oldest
     * first: on-hand-affecting entries for the task's SKU/location between
     * task creation and now.
     */
    @Transactional(readOnly = true)
    public @NonNull List<InventoryLedgerEntry> interferingMovements(@NonNull CycleCountTask task) {
        return locationIdOf(task)
                .map(location ->
                        ledgerRepository
                                .findByStockItemIdAndLocationIdAndEventTypeInAndTimestampGreaterThanOrderByTimestampAsc(
                                        task.getItemSku(),
                                        location,
                                        InventoryLedgerEventType.onHandAffectingTypes(),
                                        task.getCreatedAt()))
                .orElseGet(() ->
                        ledgerRepository.findByStockItemIdAndEventTypeInAndTimestampGreaterThanOrderByTimestampAsc(
                                task.getItemSku(),
                                InventoryLedgerEventType.onHandAffectingTypes(),
                                task.getCreatedAt()));
    }

    /**
     * Flags a task {@code CONFLICT} in its own transaction so the flag survives
     * the rollback of a rejected approval attempt.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void flagConflict(@NonNull UUID taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(TaskStatus.CONFLICT);
            taskRepository.save(task);
            log.warn("Cycle count task {} flagged CONFLICT: interfering movements detected in count window", taskId);
        });
    }

    /**
     * Tasks store the bin as free text; when it holds a storage-location UUID
     * the window queries are location-scoped, otherwise they fall back to the
     * SKU across all locations. {@code CycleCountAdjustmentServiceImpl} uses
     * the same resolution so variance recomputation and ledger posting scope
     * to exactly the location this detector scopes to.
     */
    public static Optional<UUID> locationIdOf(@NonNull CycleCountTask task) {
        try {
            return Optional.of(UUID.fromString(task.getBinLocation()));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}

package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.entity.ReplenishmentTask;
import com.positivity.inventory.internal.enums.ReplenishmentDecisionReason;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import com.positivity.inventory.internal.enums.ReplenishmentTriggerType;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.ReplenishmentTaskRepository;
import com.positivity.inventory.service.ReplenishmentService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReplenishmentServiceImpl implements ReplenishmentService {

    private static final List<ReplenishmentStatus> OPEN_STATUSES =
            List.of(ReplenishmentStatus.PENDING, ReplenishmentStatus.IN_PROGRESS);

    private final ReplenishmentTaskRepository replenishmentTaskRepository;
    private final ReplenishmentPolicyRepository replenishmentPolicyRepository;
    private final InventoryStockSummaryRepository stockSummaryRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ReplenishmentTaskResponse> getReplenishmentTasks() {
        return replenishmentTaskRepository.findByStatusIn(OPEN_STATUSES).stream()
                .map(this::toTaskResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Page<ReplenishmentPolicyResponse> getReplenishmentPolicies(
            @Nullable UUID locationId, @NonNull Pageable pageable) {
        List<ReplenishmentPolicy> policies = locationId == null
                ? replenishmentPolicyRepository.findAll()
                : replenishmentPolicyRepository.findByLocationId(locationId);

        List<ReplenishmentPolicyResponse> mapped =
                policies.stream().map(this::toPolicyResponse).toList();
        if (pageable.isUnpaged()) {
            return new PageImpl<>(mapped);
        }
        int fromIndex = Math.min((int) pageable.getOffset(), mapped.size());
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), mapped.size());
        return new PageImpl<>(mapped.subList(fromIndex, toIndex), pageable, mapped.size());
    }

    @Override
    @Transactional
    public @NonNull ReplenishmentPolicyResponse createReplenishmentPolicy(
            @NonNull CreateReplenishmentPolicyRequest request) {
        ReplenishmentPolicy policy = ReplenishmentPolicy.builder()
                .locationId(request.getLocationId())
                .itemSKU(request.getItemSKU())
                .minimumQuantity(request.getMinimumQuantity())
                .maximumQuantity(request.getMaximumQuantity())
                .build();

        ReplenishmentPolicy saved = replenishmentPolicyRepository.save(policy);
        return toPolicyResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull ReplenishmentTaskResponse evaluatePickFaceForReplenishment(
            @NonNull String productId, @NonNull UUID pickFaceLocationId) {
        boolean policyExists = replenishmentPolicyRepository.findByLocationId(pickFaceLocationId).stream()
                .findFirst()
                .isPresent();

        if (policyExists && hasOpenTask(productId, pickFaceLocationId)) {
            return ReplenishmentTaskResponse.builder()
                    .taskId(null)
                    .status("TASK_ALREADY_QUEUED")
                    .build();
        }

        return ReplenishmentTaskResponse.builder()
                .taskId(null)
                .status("NO_ACTION")
                .build();
    }

    /**
     * Batch replenishment scan (CAP-217 / odoo-parity F1, issue #1025).
     *
     * <p>Iterates every {@link ReplenishmentPolicy} and applies the same
     * below-minimum trigger evaluation the event path uses ({@link
     * #isBelowMinimum(ReplenishmentPolicy, long)} / {@link #hasOpenTask(String,
     * UUID)}), against the current on-hand from the {@code
     * inventory_stock_summary} read model (issue #1024). F2 upgrades this to
     * forecast-aware math.
     *
     * <p>Idempotency per (policy, day): a still-open task for the policy's
     * SKU/location is refreshed (quantity re-derived) instead of duplicated, and
     * if a batch-triggered task was already created today (UTC) no new task is
     * created even if the earlier one has since closed.
     */
    @Override
    @Transactional
    public @NonNull ReplenishmentScanResultResponse runBatchReplenishmentScan() {
        Instant now = Instant.now(clock);
        Instant startOfDayUtc = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        int policiesEvaluated = 0;
        int belowMinimum = 0;
        int tasksCreated = 0;
        int tasksRefreshed = 0;

        for (ReplenishmentPolicy policy : replenishmentPolicyRepository.findAll()) {
            policiesEvaluated++;
            long onHand = currentOnHand(policy.getItemSKU(), policy.getLocationId());
            if (!isBelowMinimum(policy, onHand)) {
                continue;
            }
            belowMinimum++;

            int quantityNeeded = (int) Math.max(1, policy.getMaximumQuantity() - onHand);
            Optional<ReplenishmentTask> openTask =
                    replenishmentTaskRepository
                            .findFirstByItemSKUAndDestinationLocationIdAndStatusInOrderByCreatedAtAsc(
                                    policy.getItemSKU(), policy.getLocationId(), OPEN_STATUSES);
            if (openTask.isPresent()) {
                if (refreshOpenTask(openTask.get(), quantityNeeded)) {
                    tasksRefreshed++;
                }
                continue;
            }

            boolean alreadyScannedToday =
                    replenishmentTaskRepository
                            .existsByItemSKUAndDestinationLocationIdAndTriggerTypeAndCreatedAtGreaterThanEqual(
                                    policy.getItemSKU(),
                                    policy.getLocationId(),
                                    ReplenishmentTriggerType.BATCH,
                                    startOfDayUtc);
            if (alreadyScannedToday) {
                log.debug(
                        "Batch scan skip (already created today): sku={} locationId={}",
                        policy.getItemSKU(),
                        policy.getLocationId());
                continue;
            }

            createBatchTask(policy, quantityNeeded);
            tasksCreated++;
        }

        log.info(
                "Batch replenishment scan complete: policiesEvaluated={} belowMinimum={} tasksCreated={}"
                        + " tasksRefreshed={}",
                policiesEvaluated,
                belowMinimum,
                tasksCreated,
                tasksRefreshed);

        return ReplenishmentScanResultResponse.builder()
                .policiesEvaluated(policiesEvaluated)
                .policiesBelowMinimum(belowMinimum)
                .tasksCreated(tasksCreated)
                .tasksRefreshed(tasksRefreshed)
                .scanAt(now.toString())
                .build();
    }

    /**
     * Shared below-minimum trigger evaluation (event path and batch scan must
     * not diverge — odoo-parity F1). Current math: plain on-hand vs policy
     * minimum; forecast-aware projection replaces this in F2.
     */
    private boolean isBelowMinimum(ReplenishmentPolicy policy, long onHand) {
        return onHand < policy.getMinimumQuantity();
    }

    /** Duplicate-open-task guard shared by the event path and the batch scan. */
    private boolean hasOpenTask(String itemSKU, UUID destinationLocationId) {
        return replenishmentTaskRepository.existsByItemSKUAndDestinationLocationIdAndStatusIn(
                itemSKU, destinationLocationId, OPEN_STATUSES);
    }

    /**
     * Current on-hand for one SKU/location from the stock summary read model
     * (single sanctioned read path since issue #1024); absent row means no
     * postings yet, i.e. zero on-hand.
     */
    private long currentOnHand(String itemSKU, UUID locationId) {
        return stockSummaryRepository
                .findByStockItemIdAndLocationId(itemSKU, locationId)
                .map(summary -> summary.getOnHand())
                .orElse(0L);
    }

    /** Refreshes a still-open PENDING task's quantity to the currently computed need. */
    private boolean refreshOpenTask(ReplenishmentTask task, int quantityNeeded) {
        if (task.getStatus() != ReplenishmentStatus.PENDING
                || (task.getQuantity() != null && task.getQuantity() == quantityNeeded)) {
            return false;
        }
        task.setQuantity(quantityNeeded);
        replenishmentTaskRepository.save(task);
        return true;
    }

    private void createBatchTask(ReplenishmentPolicy policy, int quantityNeeded) {
        // Source selection is a placeholder until odoo-parity F5 (internal
        // sourcing): the policy's own location stands in for the backstock
        // source because the column is non-null and no sourcing engine exists yet.
        ReplenishmentTask task = ReplenishmentTask.builder()
                .itemSKU(policy.getItemSKU())
                .quantity(quantityNeeded)
                .sourceLocationId(policy.getLocationId())
                .destinationLocationId(policy.getLocationId())
                .status(ReplenishmentStatus.PENDING)
                .triggerType(ReplenishmentTriggerType.BATCH)
                .decisionReason(ReplenishmentDecisionReason.BELOW_MIN)
                .build();
        replenishmentTaskRepository.save(task);
    }

    private ReplenishmentTaskResponse toTaskResponse(ReplenishmentTask task) {
        return ReplenishmentTaskResponse.builder()
                .taskId(task.getTaskId() != null ? task.getTaskId().toString() : null)
                .itemSKU(task.getItemSKU())
                .quantity(task.getQuantity() != null ? task.getQuantity() : 0)
                .sourceLocationId(task.getSourceLocationId())
                .destinationLocationId(task.getDestinationLocationId())
                .status(task.getStatus() != null ? task.getStatus().name() : null)
                .triggerType(
                        task.getTriggerType() != null ? task.getTriggerType().name() : null)
                .decisionReason(
                        task.getDecisionReason() != null
                                ? task.getDecisionReason().name()
                                : null)
                .sourcingReason(
                        task.getSourcingReason() != null
                                ? task.getSourcingReason().name()
                                : null)
                .assignedTo(task.getAssignedTo())
                .createdAt(task.getCreatedAt() != null ? task.getCreatedAt().toString() : null)
                .build();
    }

    private ReplenishmentPolicyResponse toPolicyResponse(ReplenishmentPolicy policy) {
        return ReplenishmentPolicyResponse.builder()
                .policyId(policy.getPolicyId() != null ? policy.getPolicyId().toString() : null)
                .locationId(policy.getLocationId())
                .itemSKU(policy.getItemSKU())
                .minimumQuantity(policy.getMinimumQuantity() != null ? policy.getMinimumQuantity() : 0)
                .maximumQuantity(policy.getMaximumQuantity() != null ? policy.getMaximumQuantity() : 0)
                .createdAt(policy.getCreatedAt() != null ? policy.getCreatedAt().toString() : null)
                .build();
    }
}

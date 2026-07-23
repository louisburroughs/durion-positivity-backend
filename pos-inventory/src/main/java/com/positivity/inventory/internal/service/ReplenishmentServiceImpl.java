package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.internal.dto.replenishment.UpdateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.entity.ReplenishmentTask;
import com.positivity.inventory.internal.enums.ReplenishmentDecisionReason;
import com.positivity.inventory.internal.enums.ReplenishmentSourceType;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import com.positivity.inventory.internal.enums.ReplenishmentTriggerType;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.exception.SnoozeUntilNotInFutureException;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.NormalizedAvailabilityRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.ReplenishmentTaskRepository;
import com.positivity.inventory.service.ReplenishmentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
    private final NormalizedAvailabilityRepository normalizedAvailabilityRepository;
    private final ForecastQuantityService forecastQuantityService;
    private final ForecastSiteResolver forecastSiteResolver;
    private final LeadTimeResolver leadTimeResolver;
    private final StockoutDeadlineCalculator stockoutDeadlineCalculator;
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

    /**
     * Creates a policy with the F3 tuning fields (odoo-parity F3, issue #1041).
     *
     * <p><b>Order-multiple seed:</b> when the caller supplies no {@code orderMultiple}, it
     * defaults to the vendor-feed pack size for the SKU (spec F3: "pack-size default from
     * feed data") — the most recent {@code NormalizedAvailability} snapshot's
     * {@code packSize}, when the SKU is a product UUID with feed rows and the pack size is
     * greater than 1 (a pack size of 1 is equivalent to no rounding and is not stored).
     * The seed is a creation-time default, not a live link: later feed updates do not
     * rewrite existing policies.
     */
    @Override
    @Transactional
    public @NonNull ReplenishmentPolicyResponse createReplenishmentPolicy(
            @NonNull CreateReplenishmentPolicyRequest request) {
        Integer orderMultiple = request.getOrderMultiple() != null
                ? request.getOrderMultiple()
                : feedPackSizeSeed(request.getItemSKU());
        ReplenishmentPolicy policy = ReplenishmentPolicy.builder()
                .locationId(request.getLocationId())
                .itemSKU(request.getItemSKU())
                .minimumQuantity(request.getMinimumQuantity())
                .maximumQuantity(request.getMaximumQuantity())
                .orderMultiple(orderMultiple)
                .leadTimeDaysOverride(request.getLeadTimeDaysOverride())
                .preferredSourceType(
                        request.getPreferredSourceType() != null
                                ? request.getPreferredSourceType()
                                : ReplenishmentSourceType.EITHER)
                .active(request.getActive() != null ? request.getActive() : Boolean.TRUE)
                .build();

        ReplenishmentPolicy saved = replenishmentPolicyRepository.save(policy);
        return toPolicyResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull ReplenishmentPolicyResponse updateReplenishmentPolicy(
            @NonNull UUID policyId, @NonNull UpdateReplenishmentPolicyRequest request) {
        ReplenishmentPolicy policy = requirePolicy(policyId);
        // Full-replace (PUT) semantics: omitted optional fields clear / reset to defaults.
        policy.setMinimumQuantity(request.getMinimumQuantity());
        policy.setMaximumQuantity(request.getMaximumQuantity());
        policy.setOrderMultiple(request.getOrderMultiple());
        policy.setLeadTimeDaysOverride(request.getLeadTimeDaysOverride());
        policy.setPreferredSourceType(
                request.getPreferredSourceType() != null
                        ? request.getPreferredSourceType()
                        : ReplenishmentSourceType.EITHER);
        policy.setActive(request.getActive() != null ? request.getActive() : Boolean.TRUE);
        return toPolicyResponse(replenishmentPolicyRepository.save(policy));
    }

    @Override
    @Transactional
    public @NonNull ReplenishmentPolicyResponse snoozeReplenishmentPolicy(
            @NonNull UUID policyId, @Nullable Instant snoozedUntil) {
        ReplenishmentPolicy policy = requirePolicy(policyId);
        if (snoozedUntil != null && !snoozedUntil.isAfter(Instant.now(clock))) {
            throw new SnoozeUntilNotInFutureException(snoozedUntil);
        }
        policy.setSnoozedUntil(snoozedUntil);
        log.info(
                "Replenishment policy {}: policyId={} sku={} locationId={} snoozedUntil={}",
                snoozedUntil != null ? "snoozed" : "unsnoozed",
                policy.getPolicyId(),
                policy.getItemSKU(),
                policy.getLocationId(),
                snoozedUntil);
        return toPolicyResponse(replenishmentPolicyRepository.save(policy));
    }

    private ReplenishmentPolicy requirePolicy(UUID policyId) {
        return replenishmentPolicyRepository
                .findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("ReplenishmentPolicy", policyId.toString()));
    }

    /**
     * Vendor-feed pack size used as the {@code orderMultiple} creation default (spec F3);
     * {@code null} when the SKU is not a product UUID, has no feed rows, or the pack size
     * does not imply rounding.
     */
    private @Nullable Integer feedPackSizeSeed(String itemSKU) {
        UUID productId;
        try {
            productId = UUID.fromString(itemSKU);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return normalizedAvailabilityRepository
                .findFirstByProductIdOrderByAsOfDesc(productId)
                .map(snapshot -> snapshot.getPackSize())
                .filter(packSize -> packSize > 1)
                .orElse(null);
    }

    /**
     * Event-path orderpoint evaluation (odoo-parity F2, issue #1040). Uses the SAME
     * forecast-aware trigger and quantity math as the batch scan ({@link
     * #project(ReplenishmentPolicy)} / {@link #quantityToReplenish(ReplenishmentPolicy,
     * Projection, UUID)}) — the two paths must not diverge.
     *
     * <p>An already-open task for the SKU/pick-face IS the in-progress supply for this
     * breach: the evaluation short-circuits to {@code TASK_ALREADY_QUEUED} without adding
     * quantity, which is what prevents double-ordering across the scan and event paths.
     */
    @Override
    @Transactional
    public @NonNull ReplenishmentTaskResponse evaluatePickFaceForReplenishment(
            @NonNull String productId, @NonNull UUID pickFaceLocationId) {
        Optional<ReplenishmentPolicy> policy =
                replenishmentPolicyRepository.findByLocationId(pickFaceLocationId).stream()
                        .filter(candidate -> productId.equals(candidate.getItemSKU()))
                        .findFirst();
        if (policy.isEmpty()) {
            return ReplenishmentTaskResponse.builder()
                    .taskId(null)
                    .status("NO_ACTION")
                    .build();
        }

        String suspensionReason = suspensionReason(policy.get(), Instant.now(clock));
        if (suspensionReason != null) {
            logSkip("Event evaluation", policy.get(), suspensionReason);
            return ReplenishmentTaskResponse.builder()
                    .taskId(null)
                    .status("NO_ACTION")
                    .build();
        }

        if (hasOpenTask(productId, pickFaceLocationId)) {
            return ReplenishmentTaskResponse.builder()
                    .taskId(null)
                    .status("TASK_ALREADY_QUEUED")
                    .build();
        }

        Projection projection = project(policy.get());
        if (!projection.triggered()) {
            return ReplenishmentTaskResponse.builder()
                    .taskId(null)
                    .status("NO_ACTION")
                    .build();
        }

        int quantityNeeded = quantityToReplenish(policy.get(), projection, null);
        if (quantityNeeded == 0) {
            return ReplenishmentTaskResponse.builder()
                    .taskId(null)
                    .status("NO_ACTION")
                    .build();
        }

        ReplenishmentTask saved = replenishmentTaskRepository.save(
                newTask(policy.get(), quantityNeeded, ReplenishmentTriggerType.EVENT, deadlineFor(projection)));
        logDecision("created", ReplenishmentTriggerType.EVENT, policy.get(), projection, quantityNeeded);
        return toTaskResponse(saved);
    }

    /**
     * Batch replenishment scan (CAP-217 / odoo-parity F1, issue #1025; forecast-aware
     * orderpoint math since F2, issue #1040).
     *
     * <p>Iterates every {@link ReplenishmentPolicy} and applies the same forecast-aware
     * trigger evaluation the event path uses ({@link #project(ReplenishmentPolicy)} /
     * {@link #quantityToReplenish(ReplenishmentPolicy, Projection, UUID)}): trigger when
     * {@code projectedAvailable(leadHorizon) < minimumQuantity}, replenish {@code max(0,
     * maximumQuantity - projectedAvailable(leadHorizon) - inProgress)}.
     *
     * <p>Idempotency per (policy, day): a still-open task for the policy's
     * SKU/location is refreshed (quantity re-derived, netting OTHER open tasks) instead of
     * duplicated, and if a batch-triggered task was already created today (UTC) no new task
     * is created even if the earlier one has since closed. A triggered policy whose
     * computed quantity is zero (in-progress supply already covers to max) creates nothing.
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
        int policiesSkipped = 0;

        for (ReplenishmentPolicy policy : replenishmentPolicyRepository.findAll()) {
            String suspensionReason = suspensionReason(policy, now);
            if (suspensionReason != null) {
                logSkip("Batch scan", policy, suspensionReason);
                policiesSkipped++;
                continue;
            }
            policiesEvaluated++;
            Projection projection = project(policy);
            if (!projection.triggered()) {
                continue;
            }
            belowMinimum++;

            Optional<ReplenishmentTask> openTask =
                    replenishmentTaskRepository
                            .findFirstByItemSKUAndDestinationLocationIdAndStatusInOrderByCreatedAtAsc(
                                    policy.getItemSKU(), policy.getLocationId(), OPEN_STATUSES);
            if (openTask.isPresent()) {
                // Re-derive the open task's quantity, netting any OTHER open tasks for the
                // same SKU/location (the refreshed task itself is excluded from in-progress
                // so its own quantity is replaced, not subtracted).
                int quantityNeeded =
                        quantityToReplenish(policy, projection, openTask.get().getTaskId());
                if (quantityNeeded > 0 && refreshOpenTask(openTask.get(), quantityNeeded, deadlineFor(projection))) {
                    logDecision("refreshed", ReplenishmentTriggerType.BATCH, policy, projection, quantityNeeded);
                    tasksRefreshed++;
                }
                continue;
            }

            int quantityNeeded = quantityToReplenish(policy, projection, null);
            if (quantityNeeded == 0) {
                log.debug(
                        "Batch scan skip (in-progress supply covers to max): sku={} locationId={}",
                        policy.getItemSKU(),
                        policy.getLocationId());
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

            replenishmentTaskRepository.save(
                    newTask(policy, quantityNeeded, ReplenishmentTriggerType.BATCH, deadlineFor(projection)));
            logDecision("created", ReplenishmentTriggerType.BATCH, policy, projection, quantityNeeded);
            tasksCreated++;
        }

        log.info(
                "Batch replenishment scan complete: policiesEvaluated={} belowMinimum={} tasksCreated={}"
                        + " tasksRefreshed={} policiesSkipped={}",
                policiesEvaluated,
                belowMinimum,
                tasksCreated,
                tasksRefreshed,
                policiesSkipped);

        return ReplenishmentScanResultResponse.builder()
                .policiesEvaluated(policiesEvaluated)
                .policiesBelowMinimum(belowMinimum)
                .tasksCreated(tasksCreated)
                .tasksRefreshed(tasksRefreshed)
                .policiesSkipped(policiesSkipped)
                .scanAt(now.toString())
                .build();
    }

    /**
     * Side-effect-free needs report (odoo-parity F3/F6, issue #1041): evaluates every
     * ACTIVE, non-snoozed policy with the SAME shared math the batch scan uses ({@link
     * #project(ReplenishmentPolicy)} / {@link #quantityToReplenish(ReplenishmentPolicy,
     * Projection, UUID)} / {@link #deadlineFor(Projection)}) so the report always matches
     * what a scan run at the same instant would do — but never creates or refreshes tasks.
     */
    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ReplenishmentNeedResponse> getReplenishmentNeeds() {
        Instant now = Instant.now(clock);
        List<ReplenishmentNeedResponse> needs = new ArrayList<>();
        for (ReplenishmentPolicy policy : replenishmentPolicyRepository.findAll()) {
            if (suspensionReason(policy, now) != null) {
                continue;
            }
            Projection projection = project(policy);
            int suggestedQuantity = projection.triggered() ? quantityToReplenish(policy, projection, null) : 0;
            LocalDate deadline = projection.triggered() ? deadlineFor(projection) : null;
            needs.add(ReplenishmentNeedResponse.builder()
                    .policyId(
                            policy.getPolicyId() != null ? policy.getPolicyId().toString() : null)
                    .itemSKU(policy.getItemSKU())
                    .locationId(policy.getLocationId())
                    .onHand(projection.onHand())
                    .projectedAvailable(projection.projectedAvailable())
                    .leadHorizonDate(LocalDate.ofInstant(projection.leadHorizon(), ZoneOffset.UTC)
                            .toString())
                    .leadTimeSource(projection.leadTime().source().name())
                    .minimumQuantity(policy.getMinimumQuantity() != null ? policy.getMinimumQuantity() : 0)
                    .maximumQuantity(policy.getMaximumQuantity() != null ? policy.getMaximumQuantity() : 0)
                    .wouldTrigger(projection.triggered())
                    .suggestedQuantity(suggestedQuantity)
                    .deadlineDate(deadline != null ? deadline.toString() : null)
                    .preferredSourceType(
                            policy.getPreferredSourceType() != null
                                    ? policy.getPreferredSourceType().name()
                                    : ReplenishmentSourceType.EITHER.name())
                    .build());
        }
        return needs;
    }

    /**
     * Forecast projection for one policy at its lead-time horizon (odoo-parity F2, issue
     * #1040 — Odoo orderpoint semantics; event path and batch scan must not diverge).
     *
     * <p>{@code leadHorizon = now + leadTimeDays} where lead time resolves per D-4
     * ({@link LeadTimeResolver}: policy override [F3] → vendor feed MAX estimate →
     * configured default). On-hand is read at the policy's own (possibly bin-level)
     * location — the stock the pick face actually holds — while forecast supply/demand is
     * scoped to the location's parent SITE via {@link ForecastSiteResolver}, because open
     * POs/ASNs/transfers are keyed by ship-to site, never by bin.
     *
     * <p>Trigger: {@code projectedAvailable(leadHorizon) < minimumQuantity}. With no open
     * supply/demand documents and no feed lead time, {@code projectedAvailable == onHand}
     * at any horizon, so the trigger degrades exactly to the pre-F2 on-hand comparison.
     */
    private Projection project(ReplenishmentPolicy policy) {
        LeadTimeResolver.ResolvedLeadTime leadTime = leadTimeResolver.resolve(policy);
        Instant evaluatedAt = Instant.now(clock);
        Instant leadHorizon = evaluatedAt.plus(Duration.ofDays(leadTime.days()));
        long onHand = currentOnHand(policy.getItemSKU(), policy.getLocationId());
        UUID forecastSiteId = forecastSiteResolver.resolveForecastSite(policy.getLocationId());
        long projectedAvailable = forecastQuantityService
                .forecast(policy.getItemSKU(), forecastSiteId, leadHorizon, onHand)
                .projectedAvailable();
        boolean triggered = projectedAvailable < policy.getMinimumQuantity();
        return new Projection(
                policy.getItemSKU(),
                forecastSiteId,
                onHand,
                projectedAvailable,
                evaluatedAt,
                leadHorizon,
                leadTime,
                triggered);
    }

    /**
     * Odoo's replenish-to-max with in-progress netting ({@code qty_in_progress}):
     * {@code max(0, maximumQuantity - projectedAvailable(leadHorizon) - inProgress)},
     * rounded UP to the policy's {@code orderMultiple} when one is set (odoo-parity F3,
     * issue #1041 — Odoo {@code qty_multiple}; e.g. need 7 with multiple 6 orders 12).
     * Rounding applies AFTER netting — an exact multiple stays as-is and zero stays zero
     * (in-progress supply already covering the need never rounds up into a phantom order).
     *
     * @param excludeTaskId open task being refreshed, excluded from the in-progress sum so
     *     its own quantity is replaced rather than double-counted; {@code null} otherwise
     */
    private int quantityToReplenish(ReplenishmentPolicy policy, Projection projection, @Nullable UUID excludeTaskId) {
        long inProgress = inProgressQuantity(policy.getItemSKU(), policy.getLocationId(), excludeTaskId);
        long raw = Math.max(0L, policy.getMaximumQuantity() - projection.projectedAvailable() - inProgress);
        return (int) roundUpToMultiple(raw, policy.getOrderMultiple());
    }

    /** Rounds a positive quantity UP to the nearest multiple; zero and no-multiple pass through. */
    private static long roundUpToMultiple(long quantity, @Nullable Integer multiple) {
        if (quantity <= 0 || multiple == null || multiple <= 1) {
            return quantity;
        }
        return ((quantity + multiple - 1) / multiple) * multiple;
    }

    /**
     * Skip reason for a policy excluded from evaluation (odoo-parity F3, issue #1041):
     * {@code INACTIVE} when the active flag is off, {@code SNOOZED} while
     * {@code snoozedUntil} is in the future (a policy resumes automatically once the
     * snooze instant passes — no unsnooze write is needed); {@code null} when the policy
     * must be evaluated.
     */
    private @Nullable String suspensionReason(ReplenishmentPolicy policy, Instant now) {
        if (Boolean.FALSE.equals(policy.getActive())) {
            return "INACTIVE";
        }
        if (policy.getSnoozedUntil() != null && policy.getSnoozedUntil().isAfter(now)) {
            return "SNOOZED";
        }
        return null;
    }

    private void logSkip(String path, ReplenishmentPolicy policy, String reason) {
        log.info(
                "{} skip: reason={} policyId={} sku={} locationId={} snoozedUntil={} active={}",
                path,
                reason,
                policy.getPolicyId(),
                policy.getItemSKU(),
                policy.getLocationId(),
                policy.getSnoozedUntil(),
                policy.getActive());
    }

    /**
     * Stock-out deadline for a triggered projection (odoo-parity F3, issue #1041): the
     * earliest date the horizon-bounded projection goes negative — see
     * {@link StockoutDeadlineCalculator} for the documented approximation.
     */
    private @Nullable LocalDate deadlineFor(Projection projection) {
        return stockoutDeadlineCalculator.stockoutDeadline(
                projection.stockItemId(),
                projection.forecastSiteId(),
                projection.onHand(),
                projection.evaluatedAt(),
                projection.leadHorizon());
    }

    /**
     * Open replenishment-task quantity already in flight for one SKU/destination — the
     * in-progress supply netted out of {@link #quantityToReplenish}.
     *
     * <p>F4 seam: open {@code PurchaseSuggestion} quantities (SUGGESTED/ACCEPTED, not yet
     * CONVERTED) join this sum once purchase suggestions exist, so a suggested-but-unordered
     * buy also prevents double-ordering.
     */
    private long inProgressQuantity(String itemSKU, UUID destinationLocationId, @Nullable UUID excludeTaskId) {
        return replenishmentTaskRepository
                .findByItemSKUAndDestinationLocationIdAndStatusIn(itemSKU, destinationLocationId, OPEN_STATUSES)
                .stream()
                .filter(task -> excludeTaskId == null || !excludeTaskId.equals(task.getTaskId()))
                .mapToLong(task -> task.getQuantity() != null ? task.getQuantity() : 0L)
                .sum();
    }

    /**
     * Decision-input recording (F2 scope 3): {@link ReplenishmentTask} deliberately gains no
     * columns — {@code decisionReason=BELOW_MIN} goes on the entity and the numeric inputs
     * are logged at INFO with structured fields (projected, horizon, lead-time source,
     * min/max) so every created/refreshed task's math is reconstructable from logs.
     */
    private void logDecision(
            String action,
            ReplenishmentTriggerType triggerType,
            ReplenishmentPolicy policy,
            Projection projection,
            int quantityNeeded) {
        log.info(
                "Replenishment task {}: triggerType={} decisionReason={} sku={} locationId={} onHand={}"
                        + " projectedAvailable={} leadHorizon={} leadTimeDays={} leadTimeSource={} min={} max={}"
                        + " quantity={}",
                action,
                triggerType,
                ReplenishmentDecisionReason.BELOW_MIN,
                policy.getItemSKU(),
                policy.getLocationId(),
                projection.onHand(),
                projection.projectedAvailable(),
                projection.leadHorizon(),
                projection.leadTime().days(),
                projection.leadTime().source(),
                policy.getMinimumQuantity(),
                policy.getMaximumQuantity(),
                quantityNeeded);
    }

    /** Forecast evaluation of one policy at its lead-time horizon (F2; F3 added the deadline inputs). */
    private record Projection(
            String stockItemId,
            @Nullable UUID forecastSiteId,
            long onHand,
            long projectedAvailable,
            Instant evaluatedAt,
            Instant leadHorizon,
            LeadTimeResolver.ResolvedLeadTime leadTime,
            boolean triggered) {}

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

    /** Refreshes a still-open PENDING task's quantity (and stock-out deadline) to the currently computed need. */
    private boolean refreshOpenTask(ReplenishmentTask task, int quantityNeeded, @Nullable LocalDate deadlineDate) {
        if (task.getStatus() != ReplenishmentStatus.PENDING
                || (task.getQuantity() != null && task.getQuantity() == quantityNeeded)) {
            return false;
        }
        task.setQuantity(quantityNeeded);
        task.setDeadlineDate(deadlineDate);
        replenishmentTaskRepository.save(task);
        return true;
    }

    private ReplenishmentTask newTask(
            ReplenishmentPolicy policy,
            int quantityNeeded,
            ReplenishmentTriggerType triggerType,
            @Nullable LocalDate deadlineDate) {
        // Source selection is a placeholder until odoo-parity F5 (internal
        // sourcing): the policy's own location stands in for the backstock
        // source because the column is non-null and no sourcing engine exists yet.
        return ReplenishmentTask.builder()
                .itemSKU(policy.getItemSKU())
                .quantity(quantityNeeded)
                .sourceLocationId(policy.getLocationId())
                .destinationLocationId(policy.getLocationId())
                .status(ReplenishmentStatus.PENDING)
                .triggerType(triggerType)
                .decisionReason(ReplenishmentDecisionReason.BELOW_MIN)
                .deadlineDate(deadlineDate)
                .build();
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
                .deadlineDate(
                        task.getDeadlineDate() != null ? task.getDeadlineDate().toString() : null)
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
                .orderMultiple(policy.getOrderMultiple())
                .leadTimeDaysOverride(policy.getLeadTimeDaysOverride())
                .preferredSourceType(
                        policy.getPreferredSourceType() != null
                                ? policy.getPreferredSourceType().name()
                                : ReplenishmentSourceType.EITHER.name())
                .snoozedUntil(
                        policy.getSnoozedUntil() != null
                                ? policy.getSnoozedUntil().toString()
                                : null)
                .active(!Boolean.FALSE.equals(policy.getActive()))
                .createdAt(policy.getCreatedAt() != null ? policy.getCreatedAt().toString() : null)
                .build();
    }
}

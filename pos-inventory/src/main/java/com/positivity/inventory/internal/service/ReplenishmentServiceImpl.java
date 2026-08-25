package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.internal.dto.replenishment.UpdateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.PurchaseSuggestion;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private static final String NO_ACTION = "NO_ACTION";

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
    private final PurchaseSuggestionCreationService purchaseSuggestionCreationService;
    private final ReplenishmentSourcingService replenishmentSourcingService;
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
                    .status(NO_ACTION)
                    .build();
        }

        String suspensionReason = suspensionReason(policy.get(), Instant.now(clock));
        if (suspensionReason != null) {
            logSkip("Event evaluation", policy.get(), suspensionReason);
            return ReplenishmentTaskResponse.builder()
                    .taskId(null)
                    .status(NO_ACTION)
                    .build();
        }

        // F4 (#1044): PURCHASE-preferring policies produce a purchase suggestion, never a
        // task — same routing as the batch scan (the two paths must not diverge).
        if (policy.get().getPreferredSourceType() == ReplenishmentSourceType.PURCHASE) {
            Projection purchaseProjection = project(policy.get());
            if (!purchaseProjection.triggered()) {
                return ReplenishmentTaskResponse.builder()
                        .taskId(null)
                        .status(NO_ACTION)
                        .build();
            }
            SuggestionOutcome outcome = evaluatePurchaseSuggestion(policy.get(), purchaseProjection, null);
            return ReplenishmentTaskResponse.builder()
                    .taskId(null)
                    .status(outcome == SuggestionOutcome.NONE ? NO_ACTION : "PURCHASE_SUGGESTED")
                    .build();
        }

        // F5 (#1045): an open source TransferOrder already covers this SKU→site need — do not
        // re-source (a DRAFT transfer is not yet netted by the forecast).
        if (replenishmentSourcingService.hasOpenSourceTransfer(policy.get())) {
            return ReplenishmentTaskResponse.builder()
                    .taskId(null)
                    .status("SOURCING_IN_PROGRESS")
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
                    .status(NO_ACTION)
                    .build();
        }

        int quantityNeeded = quantityToReplenish(policy.get(), projection, null);
        if (quantityNeeded == 0) {
            return ReplenishmentTaskResponse.builder()
                    .taskId(null)
                    .status(NO_ACTION)
                    .build();
        }

        // F5 (#1045): resolve real sourcing. EITHER with no internal surplus but a selectable
        // vendor routes to a purchase suggestion instead of a task.
        ReplenishmentSourcingService.SourcingResolution resolution =
                replenishmentSourcingService.resolve(policy.get(), quantityNeeded);
        if (resolution.kind() == ReplenishmentSourcingService.Kind.PURCHASE_FALLBACK) {
            SuggestionOutcome outcome = evaluatePurchaseSuggestion(policy.get(), projection, null);
            return ReplenishmentTaskResponse.builder()
                    .taskId(null)
                    .status(outcome == SuggestionOutcome.NONE ? NO_ACTION : "PURCHASE_SUGGESTED")
                    .build();
        }

        ReplenishmentTask saved = replenishmentTaskRepository.save(newTask(
                policy.get(), quantityNeeded, ReplenishmentTriggerType.EVENT, deadlineFor(projection), resolution));
        logDecision("created", ReplenishmentTriggerType.EVENT, policy.get(), projection, quantityNeeded, resolution);
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

        ScanTally tally = new ScanTally();
        for (ReplenishmentPolicy policy : replenishmentPolicyRepository.findAll()) {
            scanPolicy(policy, now, startOfDayUtc, tally);
        }

        log.info(
                "Batch replenishment scan complete: policiesEvaluated={} belowMinimum={} tasksCreated={}"
                        + " tasksRefreshed={} suggestionsCreated={} suggestionsRefreshed={} policiesSkipped={}",
                tally.policiesEvaluated,
                tally.belowMinimum,
                tally.tasksCreated,
                tally.tasksRefreshed,
                tally.suggestionsCreated,
                tally.suggestionsRefreshed,
                tally.policiesSkipped);

        return ReplenishmentScanResultResponse.builder()
                .policiesEvaluated(tally.policiesEvaluated)
                .policiesBelowMinimum(tally.belowMinimum)
                .tasksCreated(tally.tasksCreated)
                .tasksRefreshed(tally.tasksRefreshed)
                .suggestionsCreated(tally.suggestionsCreated)
                .suggestionsRefreshed(tally.suggestionsRefreshed)
                .policiesSkipped(tally.policiesSkipped)
                .scanAt(now.toString())
                .build();
    }

    /** What one batch scan has decided so far. Mutable on purpose: one instance per scan. */
    private static final class ScanTally {
        private int policiesEvaluated;
        private int belowMinimum;
        private int tasksCreated;
        private int tasksRefreshed;
        private int policiesSkipped;
        private int suggestionsCreated;
        private int suggestionsRefreshed;
    }

    /**
     * Decides what one policy needs, and records it against the scan's tally.
     *
     * <p>The order of the guards is the policy: suspension, then trigger, then the sourcing
     * decisions in descending preference. Each `return` below is a deliberate "nothing to do for
     * this policy", and the comment on it says which rule made that call.
     */
    private void scanPolicy(
            @NonNull ReplenishmentPolicy policy,
            @NonNull Instant now,
            @NonNull Instant startOfDayUtc,
            @NonNull ScanTally tally) {
        String suspensionReason = suspensionReason(policy, now);
        if (suspensionReason != null) {
            logSkip("Batch scan", policy, suspensionReason);
            tally.policiesSkipped++;
            return;
        }
        tally.policiesEvaluated++;
        Projection projection = project(policy);
        if (!projection.triggered()) {
            return;
        }
        tally.belowMinimum++;

        // F4 (#1044): PURCHASE-preferring policies produce a purchase suggestion, not a
        // ReplenishmentTask — see evaluatePurchaseSuggestion for guards and netting.
        if (policy.getPreferredSourceType() == ReplenishmentSourceType.PURCHASE) {
            tallyPurchaseSuggestion(policy, projection, startOfDayUtc, tally);
            return;
        }

        // F5 (#1045): an open source TransferOrder already covers this SKU→site need — do
        // not re-source it (a DRAFT transfer is not yet netted by the forecast).
        if (replenishmentSourcingService.hasOpenSourceTransfer(policy)) {
            log.debug(
                    "Batch scan skip (open source transfer covers the need): sku={} locationId={}",
                    policy.getItemSKU(),
                    policy.getLocationId());
            return;
        }

        Optional<ReplenishmentTask> openTask =
                replenishmentTaskRepository.findFirstByItemSKUAndDestinationLocationIdAndStatusInOrderByCreatedAtAsc(
                        policy.getItemSKU(), policy.getLocationId(), OPEN_STATUSES);
        if (openTask.isPresent()) {
            // Re-derive the open task's quantity, netting any OTHER open tasks for the
            // same SKU/location (the refreshed task itself is excluded from in-progress
            // so its own quantity is replaced, not subtracted).
            int quantityNeeded =
                    quantityToReplenish(policy, projection, openTask.get().getTaskId());
            if (quantityNeeded > 0 && refreshOpenTask(openTask.get(), quantityNeeded, deadlineFor(projection))) {
                logRefresh(policy, projection, quantityNeeded, openTask.get());
                tally.tasksRefreshed++;
            }
            return;
        }

        int quantityNeeded = quantityToReplenish(policy, projection, null);
        if (quantityNeeded == 0) {
            log.debug(
                    "Batch scan skip (in-progress supply covers to max): sku={} locationId={}",
                    policy.getItemSKU(),
                    policy.getLocationId());
            return;
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
            return;
        }

        // F5 (#1045): resolve real sourcing. A triggered EITHER policy with no internal
        // surplus but a selectable vendor routes to a purchase suggestion (F4) instead of
        // a task; everything else materializes a task (same-site bin move, cross-site
        // transfer, or BACKSTOCK_UNAVAILABLE).
        ReplenishmentSourcingService.SourcingResolution resolution =
                replenishmentSourcingService.resolve(policy, quantityNeeded);
        if (resolution.kind() == ReplenishmentSourcingService.Kind.PURCHASE_FALLBACK) {
            tallyPurchaseSuggestion(policy, projection, startOfDayUtc, tally);
            return;
        }

        replenishmentTaskRepository.save(
                newTask(policy, quantityNeeded, ReplenishmentTriggerType.BATCH, deadlineFor(projection), resolution));
        logDecision("created", ReplenishmentTriggerType.BATCH, policy, projection, quantityNeeded, resolution);
        tally.tasksCreated++;
    }

    /**
     * Runs the F4 suggestion evaluation and records its outcome.
     *
     * <p>Shared by both routes into it — a PURCHASE-preferring policy and an EITHER policy that
     * fell back to purchase — so the two cannot drift in how they count.
     */
    private void tallyPurchaseSuggestion(
            @NonNull ReplenishmentPolicy policy,
            @NonNull Projection projection,
            @NonNull Instant startOfDayUtc,
            @NonNull ScanTally tally) {
        switch (evaluatePurchaseSuggestion(policy, projection, startOfDayUtc)) {
            case CREATED -> tally.suggestionsCreated++;
            case REFRESHED -> tally.suggestionsRefreshed++;
            case NONE -> {
                /* covered by in-progress supply or same-day guard */
            }
        }
    }

    /** Outcome of one PURCHASE-policy suggestion evaluation (F4, #1044). */
    private enum SuggestionOutcome {
        CREATED,
        REFRESHED,
        NONE
    }

    /**
     * Suggestion path for a triggered PURCHASE-preferring policy (odoo-parity F4, issue
     * #1044; plan D-3): creates or refreshes the AT MOST ONE open (SUGGESTED/ACCEPTED)
     * suggestion for the policy, mirroring the task guards — duplicate-open guard, quantity
     * refresh netting other in-progress supply, and (scan only) the per-(policy, day)
     * created-today guard. The scan never accepts or converts (D-3: human ACCEPT is
     * mandatory; the existing PO approval workflow is the second spend gate).
     *
     * <p><b>EITHER seam (F5):</b> policies with {@code preferredSourceType == EITHER} are
     * deliberately NOT routed here and keep producing ReplenishmentTasks (current
     * behavior). Parity-F5's sourcing engine will call this method for an EITHER policy
     * after internal sourcing finds no surplus — the method is already source-agnostic, so
     * F5 only needs to add that routing decision.
     *
     * @param startOfDayUtc UTC day boundary for the created-today guard; {@code null} on
     *     the event path (which, like the event task path, has no per-day guard)
     */
    private SuggestionOutcome evaluatePurchaseSuggestion(
            ReplenishmentPolicy policy, Projection projection, @Nullable Instant startOfDayUtc) {
        Optional<PurchaseSuggestion> openSuggestion =
                purchaseSuggestionCreationService.findOpenForPolicy(policy.getPolicyId());
        if (openSuggestion.isPresent()) {
            // Re-derive the open suggestion's quantity, netting any OTHER in-progress
            // supply (its own quantity is replaced, not subtracted).
            long rawNeed = rawQuantityToReplenish(
                    policy, projection, null, openSuggestion.get().getSuggestionId());
            boolean refreshed = purchaseSuggestionCreationService.refreshSuggestion(
                    openSuggestion.get(), policy, rawNeed, projection.leadTime().days());
            return refreshed ? SuggestionOutcome.REFRESHED : SuggestionOutcome.NONE;
        }

        long rawNeed = rawQuantityToReplenish(policy, projection, null, null);
        if (rawNeed <= 0) {
            log.debug(
                    "Purchase suggestion skip (in-progress supply covers to max): sku={} locationId={}",
                    policy.getItemSKU(),
                    policy.getLocationId());
            return SuggestionOutcome.NONE;
        }
        if (startOfDayUtc != null
                && purchaseSuggestionCreationService.suggestionAlreadyCreatedToday(
                        policy.getPolicyId(), startOfDayUtc)) {
            log.debug(
                    "Purchase suggestion skip (already created today): sku={} locationId={}",
                    policy.getItemSKU(),
                    policy.getLocationId());
            return SuggestionOutcome.NONE;
        }

        purchaseSuggestionCreationService.createSuggestion(
                policy, rawNeed, projection.leadTime().days());
        return SuggestionOutcome.CREATED;
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
            needs.add(needResponse(policy, project(policy)));
        }
        return needs;
    }

    /** One report row: the policy's current projection plus what a scan would do with it right now. */
    private ReplenishmentNeedResponse needResponse(ReplenishmentPolicy policy, Projection projection) {
        int suggestedQuantity = projection.triggered() ? quantityToReplenish(policy, projection, null) : 0;
        LocalDate deadline = projection.triggered() ? deadlineFor(projection) : null;
        return ReplenishmentNeedResponse.builder()
                // No null fallback: policyId is the repository-loaded @Id, and the response
                // declares it REQUIRED — emitting null would violate the schema, not honour it.
                .policyId(policy.getPolicyId().toString())
                .itemSKU(policy.getItemSKU())
                .locationId(policy.getLocationId())
                .onHand(projection.onHand())
                .projectedAvailable(projection.projectedAvailable())
                .leadHorizonDate(LocalDate.ofInstant(projection.leadHorizon(), ZoneOffset.UTC)
                        .toString())
                .leadTimeSource(projection.leadTime().source().name())
                // No null fallback: project() (called for every policy this method reaches)
                // already unboxed minimumQuantity, so it cannot be null by this line.
                .minimumQuantity(policy.getMinimumQuantity())
                .maximumQuantity(policy.getMaximumQuantity() != null ? policy.getMaximumQuantity() : 0)
                .wouldTrigger(projection.triggered())
                .suggestedQuantity(suggestedQuantity)
                .deadlineDate(deadline != null ? deadline.toString() : null)
                .preferredSourceType(
                        policy.getPreferredSourceType() != null
                                ? policy.getPreferredSourceType().name()
                                : ReplenishmentSourceType.EITHER.name())
                .build();
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
        BigDecimal onHand = currentOnHand(policy.getItemSKU(), policy.getLocationId());
        UUID forecastSiteId = forecastSiteResolver.resolveForecastSite(policy.getLocationId());
        BigDecimal projectedAvailable = forecastQuantityService
                .forecast(policy.getItemSKU(), forecastSiteId, leadHorizon, onHand)
                .projectedAvailable();
        // Replenishment policy min/max stay integral (they are order-planning parameters, not
        // stock measurements), so the comparison widens the parameter rather than narrowing the
        // measurement — a projected 0.5 correctly reads as below a minimum of 1.
        boolean triggered = Quantities.lt(projectedAvailable, BigDecimal.valueOf(policy.getMinimumQuantity()));
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
        long raw = rawQuantityToReplenish(policy, projection, excludeTaskId, null);
        return (int) roundUpToMultiple(raw, policy.getOrderMultiple());
    }

    /**
     * The un-rounded F2 need: {@code max(0, maximumQuantity − projectedAvailable(leadHorizon)
     * − inProgress)}. The task path rounds it with the policy's {@code orderMultiple}
     * ({@link #quantityToReplenish}); the purchase-suggestion path (F4, #1044) rounds it with
     * the combined MOQ/pack/order-multiple interplay in
     * {@link PurchaseSuggestionCreationService} instead — the raw need is shared so the two
     * paths never diverge on the netting math.
     *
     * @param excludeSuggestionId open purchase suggestion being refreshed, excluded from the
     *     in-progress sum so its own quantity is replaced rather than double-counted
     */
    private long rawQuantityToReplenish(
            ReplenishmentPolicy policy,
            Projection projection,
            @Nullable UUID excludeTaskId,
            @Nullable UUID excludeSuggestionId) {
        long inProgress =
                inProgressQuantity(policy.getItemSKU(), policy.getLocationId(), excludeTaskId, excludeSuggestionId);
        // The replenishment need is a whole number of units to order — a purchase order for
        // 3.5 units of a divisible product still buys 4 containers unless the supplier sells it
        // by measure, which the F4 MOQ/pack rounding decides. Rounding the need UP is the safe
        // direction: it never orders less than the shortfall.
        BigDecimal need = BigDecimal.valueOf(policy.getMaximumQuantity())
                .subtract(projection.projectedAvailable())
                .subtract(BigDecimal.valueOf(inProgress));
        return Math.max(0L, need.setScale(0, RoundingMode.CEILING).longValue());
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
     * In-progress supply already in flight for one SKU/destination, netted out of
     * {@link #rawQuantityToReplenish}: open replenishment-task quantities PLUS open
     * purchase-suggestion quantities (the F4 seam, filled in #1044) — a
     * suggested-but-unordered buy also prevents double-ordering across both paths.
     *
     * <p>Suggestion netting hand-off (no double-count with A2 expected supply): SUGGESTED
     * and ACCEPTED always net; a CONVERTED suggestion nets only while its purchase order is
     * still DRAFT, and stops the moment approval moves the PO's open quantity into
     * {@code ExpectedSupplyServiceImpl}'s sum (which counts APPROVED/PARTIALLY_RECEIVED
     * only) — exactly one of the two is ever counted. See
     * {@link PurchaseSuggestionCreationService#openSuggestionQuantity}.
     */
    private long inProgressQuantity(
            String itemSKU,
            UUID destinationLocationId,
            @Nullable UUID excludeTaskId,
            @Nullable UUID excludeSuggestionId) {
        long openTasks = replenishmentTaskRepository
                .findByItemSKUAndDestinationLocationIdAndStatusIn(itemSKU, destinationLocationId, OPEN_STATUSES)
                .stream()
                .filter(task -> excludeTaskId == null || !excludeTaskId.equals(task.getTaskId()))
                .mapToLong(task -> task.getQuantity() != null ? task.getQuantity() : 0L)
                .sum();
        long openSuggestions = purchaseSuggestionCreationService.openSuggestionQuantity(
                itemSKU, destinationLocationId, excludeSuggestionId);
        return openTasks + openSuggestions;
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
            int quantityNeeded,
            ReplenishmentSourcingService.SourcingResolution resolution) {
        log.info(
                "Replenishment task {}: triggerType={} decisionReason={} sourcingReason={} sourceTransferOrderId={}"
                        + " sku={} locationId={} onHand={} projectedAvailable={} leadHorizon={} leadTimeDays={}"
                        + " leadTimeSource={} min={} max={} quantity={}",
                action,
                triggerType,
                resolution.decisionReason() != null
                        ? resolution.decisionReason()
                        : ReplenishmentDecisionReason.BELOW_MIN,
                resolution.sourcingReason(),
                resolution.transferOrderId(),
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

    /** Refresh-path logging: the task keeps its original sourcing decision, only quantity moves. */
    private void logRefresh(
            ReplenishmentPolicy policy, Projection projection, int quantityNeeded, ReplenishmentTask task) {
        log.info(
                "Replenishment task refreshed: triggerType={} decisionReason={} sourcingReason={} sku={}"
                        + " locationId={} onHand={} projectedAvailable={} leadHorizon={} min={} max={} quantity={}",
                ReplenishmentTriggerType.BATCH,
                task.getDecisionReason(),
                task.getSourcingReason(),
                policy.getItemSKU(),
                policy.getLocationId(),
                projection.onHand(),
                projection.projectedAvailable(),
                projection.leadHorizon(),
                policy.getMinimumQuantity(),
                policy.getMaximumQuantity(),
                quantityNeeded);
    }

    /** Forecast evaluation of one policy at its lead-time horizon (F2; F3 added the deadline inputs). */
    private record Projection(
            String stockItemId,
            @Nullable UUID forecastSiteId,
            BigDecimal onHand,
            BigDecimal projectedAvailable,
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
    private BigDecimal currentOnHand(String itemSKU, UUID locationId) {
        return stockSummaryRepository
                .findByStockItemIdAndLocationId(itemSKU, locationId)
                .map(InventoryStockSummary::getOnHand)
                .map(Quantities::nz)
                .orElse(BigDecimal.ZERO);
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

    /**
     * Materializes a task from the F5 sourcing resolution (odoo-parity F5, issue #1045). The
     * source location, decision reason, sourcing reason, and cross-site transfer link all come
     * from {@link ReplenishmentSourcingService}; the task's destination is always the policy's
     * pick face.
     */
    private ReplenishmentTask newTask(
            ReplenishmentPolicy policy,
            int quantityNeeded,
            ReplenishmentTriggerType triggerType,
            @Nullable LocalDate deadlineDate,
            ReplenishmentSourcingService.SourcingResolution resolution) {
        UUID sourceLocationId =
                resolution.sourceLocationId() != null ? resolution.sourceLocationId() : policy.getLocationId();
        ReplenishmentDecisionReason decisionReason = resolution.decisionReason() != null
                ? resolution.decisionReason()
                : ReplenishmentDecisionReason.BELOW_MIN;
        return ReplenishmentTask.builder()
                .itemSKU(policy.getItemSKU())
                .quantity(quantityNeeded)
                .sourceLocationId(sourceLocationId)
                .destinationLocationId(policy.getLocationId())
                .status(ReplenishmentStatus.PENDING)
                .triggerType(triggerType)
                .decisionReason(decisionReason)
                .sourcingReason(resolution.sourcingReason())
                .sourceTransferOrderId(resolution.transferOrderId())
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

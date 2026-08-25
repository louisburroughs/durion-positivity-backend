package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.transfer.CreateTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderLineRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderResponse;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.enums.ReplenishmentDecisionReason;
import com.positivity.inventory.internal.enums.ReplenishmentSourceType;
import com.positivity.inventory.internal.enums.ReplenishmentSourcingReason;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.enums.TransferOrderStatus;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.TransferOrderRepository;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourceSelection;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves a triggered replenishment need to a concrete sourcing action (odoo-parity F5,
 * issue #1045; spec F5). This fills the F1/F2 placeholder that always created a same-site
 * bin-move {@code ReplenishmentTask} with the policy's own location standing in for the
 * source.
 *
 * <p><b>Three source-type branches</b> (policy {@code preferredSourceType}, default EITHER):
 * <ul>
 *   <li><b>PURCHASE</b> — never reaches this service; {@code ReplenishmentServiceImpl} routes
 *       PURCHASE policies straight to {@code PurchaseSuggestionCreationService} (F4).</li>
 *   <li><b>INTERNAL_TRANSFER</b> — attempt internal sourcing (below). If no qualifying
 *       internal source has enough surplus, the need is NOT dropped: a same-site
 *       {@code ReplenishmentTask} is materialized with {@code decisionReason =
 *       BACKSTOCK_UNAVAILABLE} so ops can see an unfulfillable internal-only need.</li>
 *   <li><b>EITHER</b> — attempt internal sourcing first; if none qualifies, fall back to a
 *       PURCHASE suggestion (F4) — but only when a vendor is actually selectable from the
 *       feeds. With no internal surplus AND no selectable vendor the need degrades to a
 *       BACKSTOCK_UNAVAILABLE task rather than a phantom vendor-less suggestion (see
 *       "default-policy degradation" below).</li>
 * </ul>
 *
 * <p><b>Internal sourcing</b> (H1 {@link SourcingStrategyService#selectSource}): a source's
 * offerable surplus is {@code onHand − allocated − the SOURCE location's OWN policy minimum}
 * (never strip a source below its own min; a source with no policy contributes min 0).
 * Candidates are built from the lot-agnostic stock-summary rows for the SKU:
 * <ul>
 *   <li>Same-site backstock (rows whose resolved site equals the destination site, excluding
 *       the pick face itself) is preferred first — a qualifying pick covers the need with a
 *       same-site bin-move {@code ReplenishmentTask} (no transfer).</li>
 *   <li>Cross-site surplus (rows at OTHER sites) is used next — a qualifying pick materializes
 *       a WS-C {@code TransferOrder} (DRAFT, source = selected site, destination = policy
 *       site, one line SKU+qty) and links its id onto the created task.</li>
 * </ul>
 * {@code selectSource} requires a SINGLE candidate to cover the whole need (no split
 * sourcing v1); a source whose surplus is below the need is skipped.
 *
 * <p><b>Default-policy degradation.</b> The default {@code preferredSourceType} is EITHER.
 * A default policy with no internal surplus and no vendor feed would otherwise create a
 * vendor-less purchase suggestion; instead it degrades to a BACKSTOCK_UNAVAILABLE task —
 * honest, actionable, and it keeps pre-F5 fixtures (default EITHER policies, no sibling
 * surplus, no feed) producing a task. An explicit PURCHASE policy still creates the
 * vendor-less suggestion (F4 behaviour) because it explicitly asked to buy.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReplenishmentSourcingService {

    /**
     * Open transfer statuses that suppress re-sourcing the same SKU→site need (odoo-parity F5
     * §2, issue #1045). A DRAFT transfer is not yet in-transit, so the forecast does not net
     * it — this explicit guard prevents re-sourcing until the transfer terminates.
     */
    static final List<TransferOrderStatus> OPEN_TRANSFER_STATUSES = List.of(
            TransferOrderStatus.DRAFT,
            TransferOrderStatus.APPROVED,
            TransferOrderStatus.DISPATCHED,
            TransferOrderStatus.PARTIALLY_RECEIVED);

    private final InventoryStockSummaryRepository stockSummaryRepository;
    private final ReplenishmentPolicyRepository replenishmentPolicyRepository;
    private final TransferOrderRepository transferOrderRepository;
    private final SourcingStrategyService sourcingStrategyService;
    private final ForecastSiteResolver forecastSiteResolver;
    private final VendorSelectionService vendorSelectionService;
    private final com.positivity.inventory.service.TransferOrderService transferOrderService;

    /** What a triggered non-PURCHASE need resolved to. */
    public enum Kind {
        /** Persist a {@code ReplenishmentTask} from the resolution fields. */
        MATERIALIZE_TASK,
        /** Route to the F4 purchase-suggestion path instead of a task. */
        PURCHASE_FALLBACK
    }

    /**
     * Sourcing decision for one triggered need. For {@link Kind#MATERIALIZE_TASK} the caller
     * persists a task with the given source, reasons, and optional transfer link (the task's
     * destination is always the policy's pick face). For {@link Kind#PURCHASE_FALLBACK} all
     * other fields are null and the caller routes to the suggestion path.
     */
    public record SourcingResolution(
            @NonNull Kind kind,
            @Nullable UUID sourceLocationId,
            @Nullable UUID transferOrderId,
            @Nullable ReplenishmentSourcingReason sourcingReason,
            @Nullable ReplenishmentDecisionReason decisionReason) {

        static SourcingResolution task(
                UUID sourceLocationId,
                @Nullable UUID transferOrderId,
                @Nullable ReplenishmentSourcingReason sourcingReason,
                ReplenishmentDecisionReason decisionReason) {
            return new SourcingResolution(
                    Kind.MATERIALIZE_TASK, sourceLocationId, transferOrderId, sourcingReason, decisionReason);
        }

        static SourcingResolution purchaseFallback() {
            return new SourcingResolution(Kind.PURCHASE_FALLBACK, null, null, null, null);
        }
    }

    /**
     * Re-sourcing idempotency guard (spec F5 §2): true when an open source TransferOrder
     * already covers this policy's SKU→destination-site need, so the scan/event path must not
     * re-source it.
     */
    public boolean hasOpenSourceTransfer(@NonNull ReplenishmentPolicy policy) {
        UUID destinationSite = forecastSiteResolver.resolveForecastSite(policy.getLocationId());
        if (destinationSite == null) {
            return false;
        }
        return transferOrderRepository.existsOpenSourceTransferForSku(
                destinationSite, policy.getItemSKU(), OPEN_TRANSFER_STATUSES);
    }

    /**
     * Resolves a triggered non-PURCHASE need to a sourcing action, creating a cross-site
     * TransferOrder as a side effect when internal sourcing selects an off-site source.
     */
    public @NonNull SourcingResolution resolve(@NonNull ReplenishmentPolicy policy, int neededQuantity) {
        String sku = policy.getItemSKU();
        UUID destinationSite = forecastSiteResolver.resolveForecastSite(policy.getLocationId());

        CandidatePartition candidates = partitionCandidates(policy, sku, destinationSite);

        Optional<SourcingResolution> sameSite =
                trySameSiteSourcing(sku, destinationSite, policy, candidates.sameSite(), neededQuantity);
        if (sameSite.isPresent()) {
            return sameSite.get();
        }

        Optional<SourcingResolution> crossSite = tryCrossSiteSourcing(
                policy, sku, destinationSite, candidates.crossSite(), candidates.siteByLocation(), neededQuantity);
        if (crossSite.isPresent()) {
            return crossSite.get();
        }

        return noInternalSourceResolution(policy, sku, destinationSite, neededQuantity);
    }

    /** Same-site and cross-site surplus candidates for one SKU, keyed by resolved site. */
    private record CandidatePartition(
            @NonNull List<SourcingCandidate> sameSite,
            @NonNull List<SourcingCandidate> crossSite,
            @NonNull Map<UUID, UUID> siteByLocation) {}

    /**
     * Splits every stock-summary row for the SKU into same-site backstock and cross-site surplus,
     * dropping rows with no location, no offerable surplus, or (same-site only) the pick face
     * itself — sourcing a location from itself is never a candidate.
     */
    private @NonNull CandidatePartition partitionCandidates(
            @NonNull ReplenishmentPolicy policy, @NonNull String sku, @Nullable UUID destinationSite) {
        List<SourcingCandidate> sameSite = new ArrayList<>();
        List<SourcingCandidate> crossSite = new ArrayList<>();
        Map<UUID, UUID> siteByLocation = new HashMap<>();
        for (InventoryStockSummary row : stockSummaryRepository.findByStockItemId(sku)) {
            UUID loc = row.getLocationId();
            if (loc == null) {
                continue;
            }
            BigDecimal surplus = Quantities.nz(row.getOnHand())
                    .subtract(Quantities.nz(row.getAllocated()))
                    .subtract(BigDecimal.valueOf(ownMinimum(sku, loc)));
            if (!Quantities.isPositive(surplus)) {
                continue;
            }
            UUID rowSite = forecastSiteResolver.resolveForecastSite(loc);
            if (Objects.equals(rowSite, destinationSite)) {
                if (loc.equals(policy.getLocationId())) {
                    continue; // never source the pick face from itself
                }
                sameSite.add(new SourcingCandidate(loc, surplus));
            } else {
                crossSite.add(new SourcingCandidate(loc, surplus));
                siteByLocation.put(loc, rowSite);
            }
        }
        return new CandidatePartition(sameSite, crossSite, siteByLocation);
    }

    /** Same-site backstock surplus → bin-move task (no transfer); empty when none qualifies. */
    private @NonNull Optional<SourcingResolution> trySameSiteSourcing(
            @NonNull String sku,
            @Nullable UUID destinationSite,
            @NonNull ReplenishmentPolicy policy,
            @NonNull List<SourcingCandidate> sameSite,
            int neededQuantity) {
        Optional<SourceSelection> pick = sameSite.isEmpty()
                ? Optional.empty()
                : sourcingStrategyService.selectSource(
                        new SourcingSelection(sku, destinationSite, policy.getLocationId(), sameSite),
                        BigDecimal.valueOf(neededQuantity));
        if (pick.isEmpty()) {
            return Optional.empty();
        }
        SourceSelection selection = pick.get();
        log.info(
                "F5 internal sourcing (same-site bin move): sku={} destinationSite={} source={} qty={}"
                        + " strategy={}",
                sku,
                destinationSite,
                selection.candidate().locationId(),
                neededQuantity,
                selection.decision().effectiveStrategy());
        return Optional.of(SourcingResolution.task(
                selection.candidate().locationId(),
                null,
                sourcingReasonOf(selection.decision().effectiveStrategy()),
                ReplenishmentDecisionReason.BELOW_MIN));
    }

    /**
     * Cross-site surplus → materializes a WS-C TransferOrder (DRAFT) and links it; empty when no
     * candidate qualifies OR the selected transfer's creation fails (the caller falls through to
     * purchase / backstock-unavailable so the need is never silently dropped).
     */
    private @NonNull Optional<SourcingResolution> tryCrossSiteSourcing(
            @NonNull ReplenishmentPolicy policy,
            @NonNull String sku,
            @Nullable UUID destinationSite,
            @NonNull List<SourcingCandidate> crossSite,
            @NonNull Map<UUID, UUID> siteByLocation,
            int neededQuantity) {
        Optional<SourceSelection> pick = crossSite.isEmpty()
                ? Optional.empty()
                : sourcingStrategyService.selectSource(
                        new SourcingSelection(sku, null, null, crossSite), BigDecimal.valueOf(neededQuantity));
        if (pick.isEmpty()) {
            return Optional.empty();
        }
        SourceSelection selection = pick.get();
        UUID sourceLocation = selection.candidate().locationId();
        // get-then-resolve rather than getOrDefault: getOrDefault evaluates its default eagerly,
        // which would call the resolver on every selection even when the partition already
        // recorded the site.
        UUID sourceSite = siteByLocation.get(sourceLocation);
        if (sourceSite == null) {
            sourceSite = forecastSiteResolver.resolveForecastSite(sourceLocation);
        }
        Optional<UUID> transferOrderId =
                createCrossSiteTransfer(policy, sku, neededQuantity, sourceSite, sourceLocation, destinationSite);
        if (transferOrderId.isEmpty()) {
            return Optional.empty();
        }
        log.info(
                "F5 internal sourcing (cross-site transfer): sku={} sourceSite={} destinationSite={}"
                        + " qty={} strategy={} transferOrderId={}",
                sku,
                sourceSite,
                destinationSite,
                neededQuantity,
                selection.decision().effectiveStrategy(),
                transferOrderId.get());
        return Optional.of(SourcingResolution.task(
                sourceSite,
                transferOrderId.get(),
                sourcingReasonOf(selection.decision().effectiveStrategy()),
                ReplenishmentDecisionReason.BELOW_MIN));
    }

    /**
     * No internal source qualified: an EITHER policy with a selectable vendor routes to the F4
     * purchase-suggestion path; everything else (including default-policy degradation) becomes an
     * actionable BACKSTOCK_UNAVAILABLE task rather than a silently-dropped need.
     */
    private @NonNull SourcingResolution noInternalSourceResolution(
            @NonNull ReplenishmentPolicy policy,
            @NonNull String sku,
            @Nullable UUID destinationSite,
            int neededQuantity) {
        if (policy.getPreferredSourceType() == ReplenishmentSourceType.EITHER
                && hasSelectableVendor(sku, neededQuantity)) {
            log.info(
                    "F5 sourcing: no internal surplus for EITHER policy sku={} destinationSite={} — purchase"
                            + " fallback",
                    sku,
                    destinationSite);
            return SourcingResolution.purchaseFallback();
        }

        log.info(
                "F5 sourcing: no internal surplus and no purchase fallback (sourceType={}) sku={}"
                        + " destinationSite={} — BACKSTOCK_UNAVAILABLE task",
                policy.getPreferredSourceType(),
                sku,
                destinationSite);
        return SourcingResolution.task(
                policy.getLocationId(), null, null, ReplenishmentDecisionReason.BACKSTOCK_UNAVAILABLE);
    }

    /**
     * Offerable surplus never dips a source below its OWN policy minimum: the source
     * location's replenishment-policy min for the SKU, or 0 when it has no policy.
     */
    private int ownMinimum(String sku, UUID sourceLocationId) {
        return replenishmentPolicyRepository.findByLocationId(sourceLocationId).stream()
                .filter(candidate -> sku.equals(candidate.getItemSKU()))
                .map(candidate -> candidate.getMinimumQuantity() != null ? candidate.getMinimumQuantity() : 0)
                .findFirst()
                .orElse(0);
    }

    private Optional<UUID> createCrossSiteTransfer(
            ReplenishmentPolicy policy,
            String sku,
            int neededQuantity,
            @Nullable UUID sourceSite,
            UUID sourceLocation,
            @Nullable UUID destinationSite) {
        if (sourceSite == null || destinationSite == null || sourceSite.equals(destinationSite)) {
            return Optional.empty();
        }
        try {
            CreateTransferOrderRequest request = CreateTransferOrderRequest.builder()
                    .sourceLocationId(sourceSite)
                    .sourceStorageLocationId(sourceLocation.equals(sourceSite) ? null : sourceLocation)
                    .destinationLocationId(destinationSite)
                    .destinationStorageLocationId(
                            policy.getLocationId().equals(destinationSite) ? null : policy.getLocationId())
                    .lines(List.of(TransferOrderLineRequest.builder()
                            .sku(sku)
                            .requestedQty(neededQuantity)
                            .build()))
                    .notes("Auto-sourced by replenishment (odoo-parity F5) for policy " + policy.getPolicyId())
                    .build();
            TransferOrderResponse response = transferOrderService.createTransferOrder(request);
            return Optional.ofNullable(response.getTransferOrderId());
        } catch (RuntimeException e) {
            log.warn(
                    "F5 cross-site transfer creation failed sku={} sourceSite={} destinationSite={}: {}",
                    sku,
                    sourceSite,
                    destinationSite,
                    e.toString());
            return Optional.empty();
        }
    }

    private boolean hasSelectableVendor(String sku, int neededQuantity) {
        UUID productId = parseUuid(sku);
        if (productId == null) {
            return false;
        }
        return vendorSelectionService.selectVendor(productId, neededQuantity).chosen() != null;
    }

    private static ReplenishmentSourcingReason sourcingReasonOf(SourcingStrategy strategy) {
        return switch (strategy) {
            case FIFO -> ReplenishmentSourcingReason.FIFO;
            case FEFO -> ReplenishmentSourcingReason.FEFO;
            case PROXIMITY -> ReplenishmentSourcingReason.PROXIMITY;
            case HIGHEST_STOCK -> ReplenishmentSourcingReason.HIGHEST_STOCK;
        };
    }

    private static @Nullable UUID parseUuid(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.PurchaseSuggestion;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
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
import org.springframework.stereotype.Component;

/**
 * Scan-side purchase-suggestion creation, refresh, and netting (odoo-parity F4, issue
 * #1044; spec F4, plan D-3). Invoked by {@code ReplenishmentServiceImpl} when a triggered
 * policy's preferred source type is PURCHASE — the scan creates {@code SUGGESTED} rows
 * only and NEVER accepts or converts them (D-3: human ACCEPT is mandatory).
 *
 * <p><b>Rounding interplay (MOQ / pack / order multiple):</b> the vendor's MOQ is a FLOOR
 * (order at least this much) while the pack size and the policy's {@code orderMultiple}
 * are MULTIPLES (order in units of this). The suggested quantity is
 * {@code max(minOrderQty, roundUpToMultiple(need, max(orderMultiple, packSize)))}: the
 * need is rounded UP to the larger of the two multiple constraints (LCM of both is
 * deliberately not used — a suggestion is advice and the larger multiple dominates in
 * practice), then lifted to the MOQ floor verbatim — the MOQ is the vendor's own stated
 * minimum and therefore trusted to be orderable as-is even when it is not itself a pack
 * multiple. An exact multiple stays as-is and a zero need never rounds up into a phantom
 * order (the caller skips zero-need policies).
 *
 * <p><b>Netting handoff (no double-count):</b> open (SUGGESTED/ACCEPTED) suggestion
 * quantities join the replenishment in-progress sum via
 * {@link #openSuggestionQuantity} — filling the F2 seam in
 * {@code ReplenishmentServiceImpl#inProgressQuantity}. A CONVERTED suggestion keeps
 * netting only while its purchase order is still DRAFT, because
 * {@code ExpectedSupplyServiceImpl} counts PO supply only from APPROVED /
 * PARTIALLY_RECEIVED: the suggestion stops netting at the exact moment PO approval moves
 * the quantity into the forecast's expected-supply sum, so exactly one of the two counts
 * at any instant. DISMISSED suggestions never net.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PurchaseSuggestionCreationService {

    /** Statuses blocking a duplicate suggestion for the same policy (mirrors the task guard). */
    static final List<PurchaseSuggestionStatus> OPEN_STATUSES =
            List.of(PurchaseSuggestionStatus.SUGGESTED, PurchaseSuggestionStatus.ACCEPTED);

    /** Statuses potentially contributing to the in-progress netting sum (CONVERTED is PO-status-gated). */
    private static final List<PurchaseSuggestionStatus> NETTING_STATUSES = List.of(
            PurchaseSuggestionStatus.SUGGESTED, PurchaseSuggestionStatus.ACCEPTED, PurchaseSuggestionStatus.CONVERTED);

    private final PurchaseSuggestionRepository purchaseSuggestionRepository;
    private final ExtPurchaseOrderRepository purchaseOrderRepository;
    private final VendorSelectionService vendorSelectionService;
    private final Clock clock;

    /** The at-most-one open (SUGGESTED/ACCEPTED) suggestion for a policy. */
    public Optional<PurchaseSuggestion> findOpenForPolicy(@NonNull UUID policyId) {
        return purchaseSuggestionRepository.findFirstByPolicyIdAndStatusInOrderByCreatedAtAsc(policyId, OPEN_STATUSES);
    }

    /**
     * Per-(policy, day) idempotency guard mirroring the batch-task guard: true when any
     * suggestion for the policy was created on or after the UTC day boundary — a same-day
     * dismissal or conversion does not immediately re-suggest (suppression beyond the day
     * is the policy snooze's job, F3).
     */
    public boolean suggestionAlreadyCreatedToday(@NonNull UUID policyId, @NonNull Instant startOfDayUtc) {
        return purchaseSuggestionRepository.existsByPolicyIdAndCreatedAtGreaterThanEqual(policyId, startOfDayUtc);
    }

    /**
     * Creates the SUGGESTED row for a triggered PURCHASE-preferring policy: selects the
     * vendor deterministically across both normalized feeds, applies the class-level
     * rounding interplay, and derives lead time / earliest expected date from the chosen
     * vendor (falling back to the policy's resolved lead time when the vendor carries no
     * estimate).
     *
     * @param rawNeed the F2 result BEFORE rounding: {@code max(0, max − projected −
     *     inProgress)} with open suggestions already netted; must be positive
     * @param fallbackLeadTimeDays the policy's D-4-resolved lead time, used when the chosen
     *     vendor has no lead estimate (or no vendor is selectable)
     */
    public @NonNull PurchaseSuggestion createSuggestion(
            @NonNull ReplenishmentPolicy policy, long rawNeed, int fallbackLeadTimeDays) {
        PurchaseSuggestion suggestion = populate(
                PurchaseSuggestion.builder()
                        .policyId(policy.getPolicyId())
                        .itemSKU(policy.getItemSKU())
                        .locationId(policy.getLocationId())
                        .status(PurchaseSuggestionStatus.SUGGESTED)
                        .build(),
                policy,
                rawNeed,
                fallbackLeadTimeDays);
        PurchaseSuggestion saved = purchaseSuggestionRepository.save(suggestion);
        logOutcome("created", saved, rawNeed);
        return saved;
    }

    /**
     * Refreshes a still-open suggestion to the currently computed need: quantity, vendor
     * selection, cost, and lead data are all recomputed (feed data may have moved).
     * ACCEPTED suggestions are human-touched and never rewritten — they still block
     * duplicates and net in-progress supply, mirroring how the task refresh only touches
     * PENDING tasks. A non-positive need also leaves the suggestion untouched.
     *
     * @return true when the suggestion was actually rewritten
     */
    public boolean refreshSuggestion(
            @NonNull PurchaseSuggestion suggestion,
            @NonNull ReplenishmentPolicy policy,
            long rawNeed,
            int fallbackLeadTimeDays) {
        if (suggestion.getStatus() != PurchaseSuggestionStatus.SUGGESTED || rawNeed <= 0) {
            return false;
        }
        int previousQuantity = suggestion.getSuggestedQuantity() != null ? suggestion.getSuggestedQuantity() : 0;
        populate(suggestion, policy, rawNeed, fallbackLeadTimeDays);
        if (suggestion.getSuggestedQuantity() == previousQuantity) {
            return false;
        }
        purchaseSuggestionRepository.save(suggestion);
        logOutcome("refreshed", suggestion, rawNeed);
        return true;
    }

    /**
     * Open suggestion quantity joining the replenishment in-progress sum for one
     * SKU/destination — the F2 seam fill. See the class javadoc for the CONVERTED/DRAFT-PO
     * netting handoff that prevents double-counting against A2 expected supply.
     *
     * @param excludeSuggestionId suggestion being refreshed, excluded so its own quantity
     *     is replaced rather than double-counted; {@code null} otherwise
     */
    public long openSuggestionQuantity(
            @NonNull String itemSKU, @NonNull UUID locationId, @Nullable UUID excludeSuggestionId) {
        return purchaseSuggestionRepository
                .findByItemSKUAndLocationIdAndStatusIn(itemSKU, locationId, NETTING_STATUSES)
                .stream()
                .filter(suggestion ->
                        excludeSuggestionId == null || !excludeSuggestionId.equals(suggestion.getSuggestionId()))
                .filter(this::stillNetting)
                .mapToLong(suggestion ->
                        suggestion.getSuggestedQuantity() != null ? suggestion.getSuggestedQuantity() : 0L)
                .sum();
    }

    /**
     * Rounding interplay (see class javadoc): {@code max(minOrderQty,
     * roundUpToMultiple(need, max(orderMultiple, packSize)))}; MOQ is a floor, pack and
     * order multiple are multiples, the larger multiple wins.
     */
    static int roundedSuggestionQuantity(
            long need, @Nullable Integer orderMultiple, @Nullable Integer minOrderQty, @Nullable Integer packSize) {
        long multiple = Math.max(
                orderMultiple != null && orderMultiple > 1 ? orderMultiple : 1,
                packSize != null && packSize > 1 ? packSize : 1);
        long rounded = need <= 0 || multiple <= 1 ? need : ((need + multiple - 1) / multiple) * multiple;
        long floored = Math.max(rounded, minOrderQty != null ? minOrderQty : 0L);
        return Math.toIntExact(floored);
    }

    private PurchaseSuggestion populate(
            PurchaseSuggestion suggestion, ReplenishmentPolicy policy, long rawNeed, int fallbackLeadTimeDays) {
        VendorSelectionService.VendorSelection selection =
                vendorSelectionService.selectVendor(parseUuid(policy.getItemSKU()), rawNeed);
        VendorSelectionService.VendorCandidate chosen = selection.chosen();

        Integer leadTimeDays = chosen != null && chosen.leadTimeDays() != null
                ? chosen.leadTimeDays()
                : Integer.valueOf(fallbackLeadTimeDays);
        suggestion.setSuggestedQuantity(roundedSuggestionQuantity(
                rawNeed,
                policy.getOrderMultiple(),
                chosen != null ? chosen.minOrderQty() : null,
                chosen != null ? chosen.packSize() : null));
        suggestion.setSuggestedVendorType(chosen != null ? chosen.sourceType() : null);
        suggestion.setVendorRefId(chosen != null ? chosen.vendorRefId() : null);
        suggestion.setUnitCostMinor(chosen != null ? chosen.unitCostMinor() : null);
        suggestion.setUnitCostCurrency(chosen != null ? chosen.unitCostCurrency() : null);
        suggestion.setVendorPackSize(chosen != null ? chosen.packSize() : null);
        suggestion.setLeadTimeDays(leadTimeDays);
        suggestion.setEarliestExpectedDate(
                LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC).plusDays(leadTimeDays));
        suggestion.setSelectionReason(selection.selectionReason());
        return suggestion;
    }

    /** CONVERTED nets only while its PO is DRAFT (pre-A2-supply window); open statuses always net. */
    private boolean stillNetting(PurchaseSuggestion suggestion) {
        if (suggestion.getStatus() != PurchaseSuggestionStatus.CONVERTED) {
            return true;
        }
        UUID poId = suggestion.getConvertedPurchaseOrderId();
        if (poId == null) {
            return false;
        }
        // Read from the projection: the order is pos-order's now (CAP-320 #1334). An order the
        // replica has not seen yet is treated as not-draft, which is the safe direction — it stops
        // the suggestion netting against an order that may already have been approved.
        return purchaseOrderRepository
                .findById(poId)
                .map(po -> "DRAFT".equals(po.getStatus()))
                .orElse(false);
    }

    private void logOutcome(String action, PurchaseSuggestion suggestion, long rawNeed) {
        log.info(
                "Purchase suggestion {}: suggestionId={} policyId={} sku={} locationId={} rawNeed={}"
                        + " suggestedQuantity={} vendorType={} vendorRefId={} unitCostMinor={} leadTimeDays={}"
                        + " earliestExpectedDate={} selectionReason=[{}]",
                action,
                suggestion.getSuggestionId(),
                suggestion.getPolicyId(),
                suggestion.getItemSKU(),
                suggestion.getLocationId(),
                rawNeed,
                suggestion.getSuggestedQuantity(),
                suggestion.getSuggestedVendorType(),
                suggestion.getVendorRefId(),
                suggestion.getUnitCostMinor(),
                suggestion.getLeadTimeDays(),
                suggestion.getEarliestExpectedDate(),
                suggestion.getSelectionReason());
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

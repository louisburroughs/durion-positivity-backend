package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.DistributorNormalizedInventory;
import com.positivity.inventory.internal.entity.NormalizedAvailability;
import com.positivity.inventory.internal.enums.SuggestedVendorType;
import com.positivity.inventory.internal.repository.DistributorNormalizedInventoryRepository;
import com.positivity.inventory.internal.repository.NormalizedAvailabilityRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Deterministic, explainable vendor selection for purchase suggestions (odoo-parity F4,
 * issue #1044; spec F4).
 *
 * <p><b>Unified vendor model:</b> a "vendor" is either a manufacturer (latest
 * {@code normalized_availability} snapshot per {@code manufacturerId}) or a distributor
 * (freshest {@code distributor_normalized_inventory} row per {@code distributorId}); both
 * are normalized into one {@link VendorCandidate} that records which source it came from.
 * Distributor ids are free-text in the feed schema — rows whose {@code distributorId} does
 * not parse as a UUID are excluded from candidacy (and logged), because a suggestion's
 * vendor must be placeable on a purchase order whose {@code vendorId} is a UUID.
 *
 * <p><b>Ranking (deterministic):</b>
 * <ol>
 *   <li>candidates whose available quantity covers the needed quantity first;</li>
 *   <li>shortest lead time — the MAX-day estimate, consistent with the F2 horizon math
 *       (falling back to the MIN estimate; unknown lead time ranks last);</li>
 *   <li>lowest per-unit price (distributor feeds carry no price and rank after any priced
 *       candidate);</li>
 *   <li>stable tiebreak: vendor UUID ascending ({@link UUID#compareTo}).</li>
 * </ol>
 *
 * <p>When no candidate covers the need, the best-lead-time candidate is still selected and
 * the {@link VendorSelection#selectionReason()} records the shortfall — a suggestion is
 * advice, not a commitment, and surfacing the best partial option beats silence.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VendorSelectionService {

    /** Runner-up entries recorded in the selection reason (keeps the column bounded). */
    private static final int MAX_RUNNERS_UP_IN_REASON = 3;

    private final NormalizedAvailabilityRepository normalizedAvailabilityRepository;
    private final DistributorNormalizedInventoryRepository distributorNormalizedInventoryRepository;

    /**
     * One unified vendor candidate from either normalized feed.
     *
     * @param unitCostMinor per-base-unit price in minor units (2-decimal assumption on the
     *     feed's decimal price); {@code null} for distributor rows (no feed price)
     * @param leadTimeDays MAX-day estimate (F2-consistent), MIN fallback; {@code null} unknown
     */
    public record VendorCandidate(
            @NonNull SuggestedVendorType sourceType,
            @NonNull UUID vendorRefId,
            int availableQty,
            @Nullable Integer leadTimeDays,
            @Nullable Long unitCostMinor,
            @Nullable String unitCostCurrency,
            @Nullable Integer minOrderQty,
            @Nullable Integer packSize) {}

    /**
     * Selection outcome: the chosen candidate ({@code null} when the SKU has no usable feed
     * rows at all) plus the human-readable ranked-inputs record persisted as
     * {@code selectionReason}.
     */
    public record VendorSelection(
            @Nullable VendorCandidate chosen, @NonNull String selectionReason) {}

    /**
     * Selects the vendor for {@code neededQuantity} units of {@code productId} across both
     * normalized feeds, per the class-level ranking. Never throws on missing data — an
     * empty candidate set yields a {@code null} chosen vendor with an explanatory reason.
     */
    public @NonNull VendorSelection selectVendor(@Nullable UUID productId, long neededQuantity) {
        List<VendorCandidate> candidates = productId == null ? List.of() : candidatesFor(productId);
        if (candidates.isEmpty()) {
            String reason = productId == null
                    ? "no vendor candidates: SKU is not a catalog product UUID, so no normalized feed rows apply"
                    : "no vendor candidates: no normalized manufacturer or distributor feed rows for product "
                            + productId;
            return new VendorSelection(null, reason);
        }

        List<VendorCandidate> ranked =
                candidates.stream().sorted(rankingFor(neededQuantity)).toList();
        VendorCandidate chosen = ranked.getFirst();
        return new VendorSelection(chosen, buildReason(ranked, neededQuantity));
    }

    /**
     * Latest normalized row per vendor for the product across BOTH feed tables:
     * manufacturers keep their newest {@code asOf} snapshot, distributors their freshest
     * {@code updatedAt} row (distributor rows are upserted in place).
     */
    private List<VendorCandidate> candidatesFor(UUID productId) {
        List<VendorCandidate> candidates = new ArrayList<>();

        // Latest snapshot per manufacturer: rows arrive newest-first, keep the first seen.
        Map<UUID, NormalizedAvailability> latestPerManufacturer = new LinkedHashMap<>();
        for (NormalizedAvailability row : normalizedAvailabilityRepository.findByProductIdOrderByAsOfDesc(productId)) {
            latestPerManufacturer.putIfAbsent(row.getManufacturerId(), row);
        }
        latestPerManufacturer
                .values()
                .forEach(row -> candidates.add(new VendorCandidate(
                        SuggestedVendorType.MANUFACTURER,
                        row.getManufacturerId(),
                        row.getAvailableQty() != null ? row.getAvailableQty() : 0,
                        maxLeadDays(row.getLeadTimeDaysMax(), row.getLeadTimeDaysMin()),
                        toMinorUnits(row.getUnitPriceAmount()),
                        row.getUnitPriceCurrency(),
                        row.getMinOrderQty(),
                        row.getPackSize())));

        // Freshest row per distributor; non-UUID distributor ids cannot be PO vendors.
        Map<UUID, DistributorNormalizedInventory> freshestPerDistributor = new LinkedHashMap<>();
        for (DistributorNormalizedInventory row : distributorNormalizedInventoryRepository.findByProductId(productId)) {
            UUID distributorUuid = parseUuid(row.getDistributorId());
            if (distributorUuid == null) {
                log.debug(
                        "Vendor selection skip (non-UUID distributor id cannot be a PO vendor):"
                                + " distributorId={} productId={}",
                        row.getDistributorId(),
                        productId);
                continue;
            }
            freshestPerDistributor.merge(distributorUuid, row, (existing, candidate) -> {
                Instant existingAt = existing.getUpdatedAt();
                Instant candidateAt = candidate.getUpdatedAt();
                if (existingAt == null) {
                    return candidate;
                }
                return candidateAt != null && candidateAt.isAfter(existingAt) ? candidate : existing;
            });
        }
        freshestPerDistributor.forEach((distributorUuid, row) -> candidates.add(new VendorCandidate(
                SuggestedVendorType.DISTRIBUTOR,
                distributorUuid,
                row.getQuantityAvailable() != null ? row.getQuantityAvailable() : 0,
                maxLeadDays(row.getLeadTimeDaysMax(), row.getLeadTimeDaysMin()),
                null,
                null,
                null,
                null)));

        return candidates;
    }

    /** The class-level ranking: sufficiency, lead time, price, then vendor UUID (stable). */
    private static Comparator<VendorCandidate> rankingFor(long neededQuantity) {
        return Comparator.<VendorCandidate>comparingInt(candidate -> candidate.availableQty() >= neededQuantity ? 0 : 1)
                .thenComparingInt(
                        candidate -> candidate.leadTimeDays() != null ? candidate.leadTimeDays() : Integer.MAX_VALUE)
                .thenComparingLong(
                        candidate -> candidate.unitCostMinor() != null ? candidate.unitCostMinor() : Long.MAX_VALUE)
                .thenComparing(VendorCandidate::vendorRefId);
    }

    /** Explainable ranked-inputs record, e.g. {@code "MANUFACTURER 0196…: avail 40>=12, lead 5d, …; beat …"}. */
    private static String buildReason(List<VendorCandidate> ranked, long neededQuantity) {
        VendorCandidate chosen = ranked.getFirst();
        StringBuilder reason = new StringBuilder(describe(chosen, neededQuantity));
        int runners = Math.min(ranked.size() - 1, MAX_RUNNERS_UP_IN_REASON);
        for (int i = 1; i <= runners; i++) {
            reason.append(i == 1 ? "; beat " : "; ").append(describe(ranked.get(i), neededQuantity));
        }
        if (ranked.size() - 1 > runners) {
            reason.append("; +").append(ranked.size() - 1 - runners).append(" more");
        }
        if (chosen.availableQty() < neededQuantity) {
            reason.append("; no candidate covers need ")
                    .append(neededQuantity)
                    .append(" — best lead time chosen despite shortfall");
        }
        return reason.toString();
    }

    private static String describe(VendorCandidate candidate, long neededQuantity) {
        String comparator = candidate.availableQty() >= neededQuantity ? ">=" : "<";
        StringBuilder text = new StringBuilder()
                .append(candidate.sourceType())
                .append(' ')
                .append(candidate.vendorRefId())
                .append(": avail ")
                .append(candidate.availableQty())
                .append(comparator)
                .append(neededQuantity)
                .append(", lead ")
                .append(candidate.leadTimeDays() != null ? candidate.leadTimeDays() + "d" : "n/a")
                .append(", price ");
        if (candidate.unitCostMinor() != null) {
            text.append(candidate.unitCostMinor())
                    .append(' ')
                    .append(candidate.unitCostCurrency() != null ? candidate.unitCostCurrency() : "?")
                    .append("-minor/unit");
        } else {
            text.append("n/a");
        }
        return text.toString();
    }

    /** MAX-day lead estimate (F2-consistent conservatism), MIN fallback, else unknown. */
    private static @Nullable Integer maxLeadDays(@Nullable Integer maxDays, @Nullable Integer minDays) {
        return maxDays != null ? maxDays : minDays;
    }

    /**
     * Feed decimal price → minor units, assuming a 2-decimal currency (all current feed
     * currencies are 2-decimal; revisit if a 0/3-decimal currency ever appears in feeds).
     */
    private static @Nullable Long toMinorUnits(@Nullable BigDecimal unitPriceAmount) {
        if (unitPriceAmount == null) {
            return null;
        }
        return unitPriceAmount
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
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

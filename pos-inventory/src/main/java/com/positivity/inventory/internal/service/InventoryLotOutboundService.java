package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.enums.ProductTrackingLevel;
import com.positivity.inventory.internal.exception.LotNotAvailableException;
import com.positivity.inventory.internal.exception.LotNumberRequiredException;
import com.positivity.inventory.internal.exception.LotUnknownException;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Outbound lot resolution and FIFO lot suggestion (odoo-parity E2, issue #1042; spec §6 E2).
 *
 * <p>Counterpart of the inbound {@link InventoryLotCaptureService}: where inbound receipt
 * find-or-creates lots, outbound flows (pick confirmation, consumption, transfers, scrap) may
 * only draw from lots that already exist and are {@code ACTIVE}:
 * <ul>
 *   <li>untracked products (tracking level {@code NONE}, incl. non-UUID SKUs, no-replica
 *       products, and {@code SERIAL} until E4) — resolution returns null and never validates;
 *       a keyed lot number stays document-only free text, byte-identical to pre-E2</li>
 *   <li>LOT-tracked, no lot number keyed — 422 {@code LOT_NUMBER_REQUIRED} (the E1 code,
 *       reused: a tracked movement must never degrade to an untracked posting)</li>
 *   <li>LOT-tracked, unknown lot number — 422 {@code LOT_UNKNOWN} (outbound never creates
 *       lots)</li>
 *   <li>LOT-tracked, lot exists but is not {@code ACTIVE} — 422 {@code LOT_NOT_AVAILABLE}
 *       ({@code QUARANTINED}/{@code RECALLED} block outbound movement; {@code CONSUMED} lots
 *       hold no stock to move)</li>
 * </ul>
 *
 * <p>Returns are the one deliberate exception ({@link #resolveReturnLot}): a return
 * legitimately references a lot that was fully consumed, so only existence is validated —
 * the lot's status is left untouched here and the funnel-level
 * {@link InventoryLotStatusReconciler} flips {@code CONSUMED → ACTIVE} once the returned
 * quantity lands on the per-lot rows.
 *
 * <p>Lot suggestion ({@link #suggestFifoLotNumber}) is FIFO by lot {@code receivedAt} over the
 * ACTIVE lots with positive per-lot on-hand at the pick location (spec E2: "FEFO when expiry
 * present, else FIFO by receivedAt" — expiry data does not exist until E3, so this is plain
 * FIFO for now; E3 upgrades the ordering to FEFO automatically through the
 * {@link LotExpiryProvider} SPI without touching the callers). The suggestion is advisory:
 * pick confirmation may override it with any valid ACTIVE lot, and the lot actually keyed at
 * confirm is what the postings carry.
 */
@Component
@RequiredArgsConstructor
public class InventoryLotOutboundService {

    private final InventoryLotRepository lotRepository;
    private final InventoryStockSummaryRepository summaryRepository;
    private final ProductTrackingLevelService trackingLevelService;

    /** Whether the stock item is LOT-tracked per the catalog replica gate. */
    public boolean isLotTracked(@NonNull String stockItemId) {
        return trackingLevelService.trackingLevelFor(stockItemId) == ProductTrackingLevel.LOT;
    }

    /**
     * Resolves the lot an outbound posting draws from; null for untracked stock items.
     *
     * @param stockItemId the ledger stock-item string of the product leaving stock
     * @param lotNumber the lot number keyed on the outbound document line, if any
     * @return the lot id to stamp on the outbound ledger entries, or null for untracked items
     * @throws LotNumberRequiredException LOT-tracked and no lot number keyed
     * @throws LotUnknownException LOT-tracked and the lot number is not in the lot master
     * @throws LotNotAvailableException LOT-tracked and the lot is not ACTIVE
     */
    public @Nullable UUID resolveOutboundLot(@NonNull String stockItemId, @Nullable String lotNumber) {
        if (!isLotTracked(stockItemId)) {
            return null;
        }
        InventoryLot lot = requireExistingLot(stockItemId, lotNumber);
        if (lot.getStatus() != InventoryLotStatus.ACTIVE) {
            throw new LotNotAvailableException(
                    stockItemId, lot.getLotNumber(), lot.getStatus().name());
        }
        return lot.getLotId();
    }

    /**
     * Resolves the lot a return re-increments; null for untracked stock items. Unlike
     * {@link #resolveOutboundLot} the lot's status is NOT gated — returns may reference a
     * {@code CONSUMED} lot (the reconciler flips it back to ACTIVE once stock lands) and even
     * a {@code QUARANTINED}/{@code RECALLED} one (returned units of a blocked lot belong on
     * that lot's balance, where the block keeps applying). Existence is still mandatory:
     * returns never create lots.
     */
    public @Nullable UUID resolveReturnLot(@NonNull String stockItemId, @Nullable String lotNumber) {
        if (!isLotTracked(stockItemId)) {
            return null;
        }
        return requireExistingLot(stockItemId, lotNumber).getLotId();
    }

    /**
     * FIFO lot suggestion for a pick task (spec E2): among the per-lot summary rows with
     * positive on-hand at the given location, the ACTIVE lot with the earliest
     * {@code receivedAt} (lot id as deterministic tie-breaker). Null when the product is
     * untracked, the location is unknown, or no ACTIVE lot has stock there.
     */
    public @Nullable String suggestFifoLotNumber(@NonNull String stockItemId, @Nullable UUID locationId) {
        if (locationId == null || !isLotTracked(stockItemId)) {
            return null;
        }
        Map<UUID, InventoryStockSummary> rowsByLotId =
                summaryRepository.findPositivePerLotRows(stockItemId, locationId).stream()
                        .collect(Collectors.toMap(InventoryStockSummary::getLotId, Function.identity()));
        if (rowsByLotId.isEmpty()) {
            return null;
        }
        return lotRepository.findAllById(rowsByLotId.keySet()).stream()
                .filter(lot -> lot.getStatus() == InventoryLotStatus.ACTIVE)
                .min(Comparator.comparing(InventoryLot::getReceivedAt).thenComparing(InventoryLot::getLotId))
                .map(InventoryLot::getLotNumber)
                .orElse(null);
    }

    private @NonNull InventoryLot requireExistingLot(@NonNull String stockItemId, @Nullable String lotNumber) {
        if (lotNumber == null || lotNumber.isBlank()) {
            throw new LotNumberRequiredException(stockItemId);
        }
        String normalized = lotNumber.trim();
        return lotRepository
                .findByStockItemIdAndLotNumber(stockItemId, normalized)
                .orElseThrow(() -> new LotUnknownException(stockItemId, normalized));
    }
}

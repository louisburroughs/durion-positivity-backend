package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.enums.ProductTrackingLevel;
import com.positivity.inventory.internal.exception.LotNumberRequiredException;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Inbound lot capture for receipt postings (odoo-parity E1, issue #1038; spec §6 E1).
 *
 * <p>Applies the tracking-level gate to a receipt line and returns the {@code lotId} to stamp
 * on the resulting ledger entries:
 * <ul>
 *   <li>{@code NONE}-tracked (including non-UUID SKUs and products with no catalog replica) —
 *       returns null, never validates: zero behavior change for untracked products. A free-text
 *       lot number keyed on such a line stays document-only, as before E1.</li>
 *   <li>{@code LOT}-tracked — a blank/missing lot number is a deterministic 422
 *       ({@code LOT_NUMBER_REQUIRED}); otherwise the {@link InventoryLot} is found or created
 *       per (stockItemId, lotNumber), stamping {@code receivedAt} and the document's vendor on
 *       first sight, and its id is returned.</li>
 *   <li>{@code SERIAL}-tracked — treated as {@code NONE} for now: serial enumeration is the E4
 *       story; demanding a lot number here would mismodel serialized stock.</li>
 * </ul>
 *
 * <p>Lot creation runs in its own {@code REQUIRES_NEW} transaction ({@link InventoryLotCreator},
 * mirroring the A1 summary-row initializer): a concurrent first receipt of the same new
 * (stockItemId, lotNumber) loses the unique-constraint race in the inner transaction only, and
 * this service re-reads the winner's row deterministically instead of surfacing a 500. The
 * trade-off is that a created lot row survives a rolled-back posting — harmless, the master row
 * is idempotent and carries no quantity.
 */
@Component
@Slf4j
public class InventoryLotCaptureService {

    private final InventoryLotRepository lotRepository;
    private final ProductTrackingLevelService trackingLevelService;
    private final InventoryLotCreator lotCreator;

    public InventoryLotCaptureService(
            InventoryLotRepository lotRepository,
            ProductTrackingLevelService trackingLevelService,
            InventoryLotCreator lotCreator) {
        this.lotRepository = lotRepository;
        this.trackingLevelService = trackingLevelService;
        this.lotCreator = lotCreator;
    }

    /**
     * Resolves the lot for one receipt line per the tracking-level gate; null for untracked
     * (and, for now, SERIAL-tracked) stock items.
     *
     * @param stockItemId the ledger stock-item string of the received product
     * @param lotNumber the lot number keyed on the receiving document line, if any
     * @param vendorId the vendor on the receiving document, stamped on first lot creation
     * @return the lot id to stamp on the receipt's ledger entries, or null
     * @throws LotNumberRequiredException when the product is LOT-tracked and no lot number was keyed
     */
    public @Nullable UUID resolveReceiptLot(
            @NonNull String stockItemId, @Nullable String lotNumber, @Nullable UUID vendorId) {
        if (trackingLevelService.trackingLevelFor(stockItemId) != ProductTrackingLevel.LOT) {
            return null;
        }
        if (lotNumber == null || lotNumber.isBlank()) {
            throw new LotNumberRequiredException(stockItemId);
        }
        String normalizedLotNumber = lotNumber.trim();
        InventoryLot existing = lotRepository
                .findByStockItemIdAndLotNumber(stockItemId, normalizedLotNumber)
                .orElse(null);
        if (existing != null) {
            return existing.getLotId();
        }
        try {
            return lotCreator.create(stockItemId, normalizedLotNumber, vendorId).getLotId();
        } catch (DataIntegrityViolationException raced) {
            // Concurrent first receipt of the same (stockItemId, lotNumber): the creator's
            // REQUIRES_NEW transaction lost the unique-constraint race and rolled back alone —
            // the winner's committed row is the lot.
            return lotRepository
                    .findByStockItemIdAndLotNumber(stockItemId, normalizedLotNumber)
                    .orElseThrow(() -> raced)
                    .getLotId();
        }
    }
}

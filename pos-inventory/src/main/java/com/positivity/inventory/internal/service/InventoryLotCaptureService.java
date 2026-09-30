package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.enums.ProductTrackingLevel;
import com.positivity.inventory.internal.exception.LotNumberRequiredException;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * <p>Lot creation is an in-transaction {@code INSERT ... ON CONFLICT DO NOTHING}
 * ({@link InventoryLotRepository#insertIfAbsent}) followed by a re-read: one connection per request, no
 * constraint violation, no rollback-only mark. On Postgres a concurrent first receipt of the same new
 * (stockItemId, lotNumber) waits for the in-flight inserter and then reads the winner's row. The lot row now
 * rolls back with the receipt transaction that created it.
 */
@Component
@Slf4j
public class InventoryLotCaptureService {

    private final InventoryLotRepository lotRepository;
    private final ProductTrackingLevelService trackingLevelService;
    private final TenantResolver tenantResolver;
    private final Clock clock;

    public InventoryLotCaptureService(
            InventoryLotRepository lotRepository,
            ProductTrackingLevelService trackingLevelService,
            TenantResolver tenantResolver,
            Clock clock) {
        this.lotRepository = lotRepository;
        this.trackingLevelService = trackingLevelService;
        this.tenantResolver = tenantResolver;
        this.clock = clock;
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
        return resolveReceiptLot(stockItemId, lotNumber, vendorId, null);
    }

    /**
     * As {@link #resolveReceiptLot(String, String, UUID)} but with an optional expiration date
     * (odoo-parity E3, issue #1047) stamped on first creation of the lot. An existing lot is not
     * re-dated here — expiration corrections go through the lot-management endpoint.
     *
     * @param expirationDate expiration date keyed on the receiving/goods-receipt line, if any
     */
    public @Nullable UUID resolveReceiptLot(
            @NonNull String stockItemId,
            @Nullable String lotNumber,
            @Nullable UUID vendorId,
            @Nullable LocalDate expirationDate) {
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
        // Insert-if-absent inside the caller's transaction (ON CONFLICT DO NOTHING: one connection, no
        // violation, no rollback-only mark). On Postgres a concurrent first receipt of the same lot waits for
        // the in-flight inserter; the re-read then returns the winner's row.
        Instant now = Instant.now(clock);
        int inserted = lotRepository.insertIfAbsent(
                tenantResolver.require(),
                UUIDv7Generator.generate(),
                stockItemId,
                normalizedLotNumber,
                vendorId,
                expirationDate,
                now);
        UUID lotId = lotRepository
                .findByStockItemIdAndLotNumber(stockItemId, normalizedLotNumber)
                .orElseThrow(() -> new IllegalStateException("Inventory lot missing after insert for stockItemId="
                        + stockItemId + " lotNumber=" + normalizedLotNumber))
                .getLotId();
        if (inserted == 1) {
            log.info(
                    "Created inventory lot {} for stockItemId={} lotNumber={}",
                    lotId,
                    stockItemId,
                    normalizedLotNumber);
        }
        return lotId;
    }
}

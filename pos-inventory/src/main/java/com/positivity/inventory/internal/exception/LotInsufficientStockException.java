package com.positivity.inventory.internal.exception;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Thrown when a lot-tagged posting would drive a per-lot summary row negative (odoo-parity E2,
 * issue #1042; spec §6 E2 — "negative lot balances rejected").
 *
 * <p>Maps to a deterministic 422 with code {@code LOT_INSUFFICIENT_STOCK}. The per-lot floor is
 * absolute: unlike the lot-agnostic {@code NegativeStockPolicy} matrix it has no override —
 * a physical lot can never hold negative stock, and an "override" would silently misattribute
 * stock between lots.
 */
public class LotInsufficientStockException extends RuntimeException {

    public static final String ERROR_CODE = "LOT_INSUFFICIENT_STOCK";

    private final String stockItemId;
    private final UUID lotId;

    public LotInsufficientStockException(
            String stockItemId, UUID lotId, @Nullable UUID locationId, long projectedOnHand) {
        super("LOT_INSUFFICIENT_STOCK: posting would take lot " + lotId + " of stock item " + stockItemId
                + " at location " + locationId + " to " + projectedOnHand
                + "; a lot cannot be drawn below its per-lot on-hand");
        this.stockItemId = stockItemId;
        this.lotId = lotId;
    }

    public String getStockItemId() {
        return stockItemId;
    }

    public UUID getLotId() {
        return lotId;
    }
}

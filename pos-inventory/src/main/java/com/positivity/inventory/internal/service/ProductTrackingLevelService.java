package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.ProductTrackingLevel;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Answers a stock item's tracking level from the catalog-fed {@code ext_product} replica
 * (odoo-parity E1, issue #1038; contract X1, #1023 — catalog owns the flag, OQ-2).
 *
 * <p>Resolution rule (spec §6 E1, zero-change guarantee for untracked/unknown products):
 * <ul>
 *   <li>{@code stockItemId} parses as a product UUID with an {@code ext_product} row →
 *       the replica's {@code tracking_level} (unknown values normalize to {@code NONE})</li>
 *   <li>non-UUID stock items (legacy free-text SKUs) or products without a replica row →
 *       {@code NONE} — they predate the catalog contract and must behave exactly as before</li>
 * </ul>
 */
@Component
public class ProductTrackingLevelService {

    private final ExtProductReplicaRepository extProductReplicaRepository;

    public ProductTrackingLevelService(ExtProductReplicaRepository extProductReplicaRepository) {
        this.extProductReplicaRepository = extProductReplicaRepository;
    }

    /** Tracking level for the stock item; {@code NONE} whenever the catalog contract cannot answer. */
    public @NonNull ProductTrackingLevel trackingLevelFor(@NonNull String stockItemId) {
        UUID productId = parseProductId(stockItemId);
        if (productId == null) {
            return ProductTrackingLevel.NONE;
        }
        return extProductReplicaRepository
                .findById(productId)
                .map(replica -> ProductTrackingLevel.fromReplicaValue(replica.getTrackingLevel()))
                .orElse(ProductTrackingLevel.NONE);
    }

    private static UUID parseProductId(String stockItemId) {
        if (stockItemId == null || stockItemId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(stockItemId.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

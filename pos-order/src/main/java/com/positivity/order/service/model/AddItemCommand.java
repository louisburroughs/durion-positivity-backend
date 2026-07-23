package com.positivity.order.service.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Command for adding a line item to a cart (parity stories B1/B2/I1).
 *
 * @param itemSku SKU to add
 * @param quantity units
 * @param reasonCode reason for a manual price or special handling
 * @param manualPrice permissioned manual unit price (pricing-outage fallback)
 * @param clientLineUuid client-stable line identity for replay-safe adds
 * @param customerNote customer-visible line note
 * @param internalNote shop-internal line note
 * @param serialNumbers captured lot/serial numbers (count ≤ quantity; parity story H3)
 */
public record AddItemCommand(
        @NonNull String itemSku,
        int quantity,
        @Nullable String reasonCode,
        @Nullable BigDecimal manualPrice,
        @Nullable UUID clientLineUuid,
        @Nullable String customerNote,
        @Nullable String internalNote,
        @Nullable List<String> serialNumbers) {

    public AddItemCommand {
        Objects.requireNonNull(itemSku, "itemSku must not be null");
    }
}

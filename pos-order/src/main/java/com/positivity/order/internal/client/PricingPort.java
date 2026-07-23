package com.positivity.order.internal.client;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Server-authoritative price resolution for a cart line (parity story B1). Implementations resolve
 * the SKU against the {@code ext_product} replica and quote pos-price (an ADR-0044 utility
 * module).
 */
public interface PricingPort {

    @NonNull
    PricingQuote quoteForSku(@NonNull String sku, int quantity, @Nullable UUID locationId, @Nullable UUID customerId);
}

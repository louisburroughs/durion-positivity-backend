package com.positivity.inventory.internal.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * SPI hook that feeds lot-expiry data into the FEFO sourcing strategy
 * (odoo-parity H1, issue #1037). Lots do not exist yet — they arrive with
 * odoo-parity E1/E2/E3. Until a lot-aware implementation is registered (as
 * the {@code @Primary} bean, replacing {@link NoOpLotExpiryProvider}), FEFO
 * configurations fall back to FIFO ordering in the engine.
 */
public interface LotExpiryProvider {

    /**
     * Whether any lot-expiry data exists for the SKU. {@code false} makes a
     * configured FEFO strategy fall back to FIFO ordering.
     */
    boolean hasExpiryData(@NonNull String stockItemId);

    /**
     * Earliest lot expiry of the SKU per location. Locations without expiring
     * lots are absent from the map (FEFO sorts them last).
     */
    @NonNull
    Map<UUID, Instant> earliestExpiryByLocation(@NonNull String stockItemId, @NonNull Collection<UUID> locationIds);
}

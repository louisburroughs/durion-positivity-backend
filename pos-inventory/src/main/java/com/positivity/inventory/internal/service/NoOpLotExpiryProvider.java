package com.positivity.inventory.internal.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op {@link LotExpiryProvider}. Since odoo-parity E3 (issue #1047) the lot-aware
 * {@link LotExpiryLotProvider} is registered {@code @Primary} and is the bean the
 * {@link FefoSourcingStrategy} actually injects; this bean remains only as the always-empty
 * fallback that keeps FEFO on its FIFO ordering when no lot-expiry data is available.
 */
@Component
public class NoOpLotExpiryProvider implements LotExpiryProvider {

    @Override
    public boolean hasExpiryData(@NonNull String stockItemId) {
        return false;
    }

    @Override
    public @NonNull Map<UUID, Instant> earliestExpiryByLocation(
            @NonNull String stockItemId, @NonNull Collection<UUID> locationIds) {
        return Map.of();
    }
}

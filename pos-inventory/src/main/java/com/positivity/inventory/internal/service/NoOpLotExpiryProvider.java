package com.positivity.inventory.internal.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Default no-op {@link LotExpiryProvider}: no lots exist yet (odoo-parity E1
 * is a later story), so FEFO always falls back to FIFO ordering. The E2/E3
 * lot stories replace this by registering a lot-aware {@code @Primary} bean.
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

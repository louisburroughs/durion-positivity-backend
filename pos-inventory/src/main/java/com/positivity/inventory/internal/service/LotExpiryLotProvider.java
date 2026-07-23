package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Lot-aware {@link LotExpiryProvider} that activates FEFO sourcing (odoo-parity E3, issue #1047;
 * spec §6 E3). Registered as {@code @Primary} so it replaces {@link NoOpLotExpiryProvider} as the
 * bean the {@link FefoSourcingStrategy} injects — the strategy orders candidate locations by the
 * SKU's earliest lot expiry without any change to the strategy or the engine.
 *
 * <p>Expiry data is drawn from the per-lot {@code inventory_stock_summary} rows joined to the lot
 * master: only ACTIVE, non-expired lots with positive on-hand count, so a location's "earliest
 * expiry" is the soonest date it can still promise good stock from. A SKU with no such lot reports
 * no data — {@link #hasExpiryData} returns {@code false} and the engine keeps FEFO configurations
 * on their documented FIFO fallback (mirroring the retired {@link NoOpLotExpiryProvider}).
 *
 * <p>Lot expiration is a {@link LocalDate}; the SPI is defined in {@link Instant}. Each date maps
 * to the start of its day in UTC — the ordering is by calendar day, and the epoch mapping only has
 * to be monotonic and deterministic, which start-of-day-UTC is.
 */
@Component
@Primary
@RequiredArgsConstructor
public class LotExpiryLotProvider implements LotExpiryProvider {

    private final InventoryStockSummaryRepository summaryRepository;
    private final Clock clock;

    @Override
    public boolean hasExpiryData(@NonNull String stockItemId) {
        return summaryRepository.hasExpiryData(stockItemId, today());
    }

    @Override
    public @NonNull Map<UUID, Instant> earliestExpiryByLocation(
            @NonNull String stockItemId, @NonNull Collection<UUID> locationIds) {
        if (locationIds.isEmpty()) {
            return Map.of();
        }
        LocalDate today = today();
        Map<UUID, Instant> result = new HashMap<>();
        for (InventoryStockSummaryRepository.LocationExpiry row :
                summaryRepository.earliestExpiryByLocation(stockItemId, locationIds, today)) {
            if (row.getLocationId() != null && row.getEarliestExpiry() != null) {
                result.put(
                        row.getLocationId(),
                        row.getEarliestExpiry().atStartOfDay(ZoneOffset.UTC).toInstant());
            }
        }
        return Map.copyOf(result);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}

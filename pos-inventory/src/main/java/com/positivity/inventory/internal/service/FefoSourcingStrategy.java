package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * FEFO ordering (odoo-parity H1, issue #1037): candidate locations ordered by
 * the SKU's earliest lot expiry at each location, earliest first, fed by the
 * {@link LotExpiryProvider} SPI.
 *
 * <p>The lot master exists (odoo-parity E1) but expiry dates are not
 * populated until E3: with the default {@link NoOpLotExpiryProvider} the
 * engine never dispatches here — a configured FEFO strategy falls back to
 * FIFO ordering in {@code SourcingStrategyServiceImpl} until a lot-aware
 * provider reports expiry data for the SKU. E2/E3 plug in the real provider;
 * this ordering activates without further engine changes.
 *
 * <p>Tie-breakers (deterministic): locations without expiring lots sort last;
 * equal expiries order by ascending location id.
 */
@Component
@RequiredArgsConstructor
public class FefoSourcingStrategy implements SourcingOrderingStrategy {

    private final LotExpiryProvider lotExpiryProvider;

    @Override
    public @NonNull SourcingStrategy strategy() {
        return SourcingStrategy.FEFO;
    }

    @Override
    public @NonNull List<SourcingCandidate> order(@NonNull SourcingSelection selection) {
        if (selection.candidates().size() < 2) {
            return List.copyOf(selection.candidates());
        }
        List<UUID> locationIds = selection.candidates().stream()
                .map(SourcingCandidate::locationId)
                .toList();
        Map<UUID, Instant> earliestExpiry =
                lotExpiryProvider.earliestExpiryByLocation(selection.stockItemId(), locationIds);

        return selection.candidates().stream()
                .sorted(Comparator.comparing(
                                (SourcingCandidate candidate) -> earliestExpiry.get(candidate.locationId()),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SourcingCandidate::locationId))
                .toList();
    }
}

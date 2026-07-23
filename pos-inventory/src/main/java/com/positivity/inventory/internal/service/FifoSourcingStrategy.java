package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * FIFO ordering (odoo-parity H1, issue #1037): candidate locations ordered by
 * the earliest {@code GOODS_RECEIPT}/{@code TRANSFER_IN} ledger timestamp of
 * the SKU at each location, earliest first.
 *
 * <p>Documented approximation: ordering uses the per-location earliest-receipt
 * timestamp, not per-unit stock aging — a location that received the SKU first
 * ranks first even if that original stock has since turned over.
 *
 * <p>Tie-breakers (deterministic): locations with no receipt history sort
 * last; equal timestamps and no-history groups order by ascending location id.
 */
@Component
@RequiredArgsConstructor
public class FifoSourcingStrategy implements SourcingOrderingStrategy {

    private static final List<InventoryLedgerEventType> RECEIPT_EVENT_TYPES =
            List.of(InventoryLedgerEventType.GOODS_RECEIPT, InventoryLedgerEventType.TRANSFER_IN);

    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Override
    public @NonNull SourcingStrategy strategy() {
        return SourcingStrategy.FIFO;
    }

    @Override
    public @NonNull List<SourcingCandidate> order(@NonNull SourcingSelection selection) {
        if (selection.candidates().size() < 2) {
            return List.copyOf(selection.candidates());
        }
        List<UUID> locationIds = selection.candidates().stream()
                .map(SourcingCandidate::locationId)
                .toList();
        Map<UUID, Instant> earliestReceipt = new HashMap<>();
        inventoryLedgerEntryRepository
                .findEarliestReceiptByLocation(selection.stockItemId(), locationIds, RECEIPT_EVENT_TYPES)
                .forEach(row -> earliestReceipt.put(row.getLocationId(), row.getEarliestReceipt()));

        return selection.candidates().stream()
                .sorted(Comparator.comparing(
                                (SourcingCandidate candidate) -> earliestReceipt.get(candidate.locationId()),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SourcingCandidate::locationId))
                .toList();
    }
}

package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads bulk per-location quantity maps inside a single read-only
 * transaction, keeping the remote topology fetch outside transactional
 * scope (CAP-218 #658; see #657 handoff note on non-atomic grouped sums).
 */
@Component
public class SiteInventoryQuantityLoader {

    private static final List<InventoryLedgerEventType> ALLOCATION_CREATED_TYPES =
            List.of(InventoryLedgerEventType.ALLOCATION_CREATED);
    private static final List<InventoryLedgerEventType> ALLOCATION_RELEASED_TYPES =
            List.of(InventoryLedgerEventType.ALLOCATION_RELEASED);

    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    public SiteInventoryQuantityLoader(InventoryLedgerEntryRepository inventoryLedgerEntryRepository) {
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
    }

    /**
     * On-hand and outstanding-allocation quantities per location.
     *
     * @param sku optional stock item filter; null/blank = all stock items
     */
    @Transactional(readOnly = true)
    @NonNull
    public QuantitySnapshot load(@NonNull Collection<UUID> locationIds, @Nullable String sku) {
        Collection<InventoryLedgerEventType> onHandTypes = InventoryLedgerEventType.onHandAffectingTypes();

        Map<UUID, Long> onHand;
        Map<UUID, Long> allocated;
        if (sku == null || sku.isBlank()) {
            onHand = inventoryLedgerEntryRepository.sumQuantityByLocationChunked(locationIds, onHandTypes);
            allocated = inventoryLedgerEntryRepository.calculateOutstandingAllocationsByLocation(locationIds);
        } else {
            onHand = inventoryLedgerEntryRepository.sumQuantityByLocationForSkuChunked(sku, locationIds, onHandTypes);
            allocated = outstandingForSku(sku, locationIds);
        }
        return new QuantitySnapshot(onHand, allocated);
    }

    private Map<UUID, Long> outstandingForSku(String sku, Collection<UUID> locationIds) {
        Map<UUID, Long> created = inventoryLedgerEntryRepository.sumQuantityByLocationForSkuChunked(
                sku, locationIds, ALLOCATION_CREATED_TYPES);
        Map<UUID, Long> released = inventoryLedgerEntryRepository.sumQuantityByLocationForSkuChunked(
                sku, locationIds, ALLOCATION_RELEASED_TYPES);

        Map<UUID, Long> outstanding = new HashMap<>(created);
        released.forEach((locationId, quantity) -> outstanding.merge(locationId, -quantity, Long::sum));
        return Map.copyOf(outstanding);
    }

    public record QuantitySnapshot(Map<UUID, Long> onHand, Map<UUID, Long> allocated) {}
}

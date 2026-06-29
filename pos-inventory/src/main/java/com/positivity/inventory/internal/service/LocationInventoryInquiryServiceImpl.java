package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.internal.dto.LocationInventoryItemsResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.service.LocationInventoryInquiryService;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates on-hand inventory at the location level from inventory ledger
 * entries.
 */
@Service
public class LocationInventoryInquiryServiceImpl implements LocationInventoryInquiryService {

    private static final List<InventoryLedgerEventType> ON_HAND_EVENT_TYPES = Arrays.stream(
                    InventoryLedgerEventType.values())
            .filter(InventoryLedgerEventType::affectsOnHand)
            .toList();

    private static final List<InventoryLedgerEventType> ALLOCATION_EVENT_TYPES =
            List.of(InventoryLedgerEventType.ALLOCATION_CREATED, InventoryLedgerEventType.ALLOCATION_RELEASED);

    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    public LocationInventoryInquiryServiceImpl(InventoryLedgerEntryRepository inventoryLedgerEntryRepository) {
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public LocationInventoryInquiryResponse getLocationInventory(@NonNull UUID locationId, @Nullable String sku) {
        Integer onHandQuantity = sku == null || sku.isBlank()
                ? inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(locationId, ON_HAND_EVENT_TYPES)
                : inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                        sku, locationId, ON_HAND_EVENT_TYPES);

        int resolvedOnHandQuantity = onHandQuantity == null ? 0 : onHandQuantity;
        int outstandingAllocations = calculateOutstandingAllocations(locationId, sku);

        return LocationInventoryInquiryResponse.builder()
                .locationId(locationId)
                .onHandQuantity(resolvedOnHandQuantity)
                .availableToPromiseQuantity(resolvedOnHandQuantity - outstandingAllocations)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public LocationInventoryItemsResponse listLocationInventoryItems(@NonNull UUID locationId) {
        List<LocationInventoryItemsResponse.Item> items =
                inventoryLedgerEntryRepository.findPositiveOnHandByLocation(locationId, ON_HAND_EVENT_TYPES).stream()
                        .map(row -> LocationInventoryItemsResponse.Item.builder()
                                .stockItemId(row.getStockItemId())
                                .onHandQuantity(row.getOnHandQuantity() == null ? 0L : row.getOnHandQuantity())
                                .build())
                        .toList();

        return LocationInventoryItemsResponse.builder()
                .locationId(locationId)
                .items(items)
                .build();
    }

    private int calculateOutstandingAllocations(UUID locationId, @Nullable String sku) {
        List<InventoryLedgerEntry> allocationEntries = sku == null || sku.isBlank()
                ? inventoryLedgerEntryRepository.findByLocationIdAndEventTypeIn(locationId, ALLOCATION_EVENT_TYPES)
                : inventoryLedgerEntryRepository
                        .findByStockItemIdAndLocationIdOrderByTimestampAsc(sku, locationId)
                        .stream()
                        .filter(entry -> entry.getEventType() == InventoryLedgerEventType.ALLOCATION_CREATED
                                || entry.getEventType() == InventoryLedgerEventType.ALLOCATION_RELEASED)
                        .toList();

        int allocationCreated = allocationEntries.stream()
                .filter(entry -> entry.getEventType() == InventoryLedgerEventType.ALLOCATION_CREATED)
                .mapToInt(entry -> entry.getChangeInQuantity() == null ? 0 : entry.getChangeInQuantity())
                .sum();

        int allocationReleased = allocationEntries.stream()
                .filter(entry -> entry.getEventType() == InventoryLedgerEventType.ALLOCATION_RELEASED)
                .mapToInt(entry -> entry.getChangeInQuantity() == null ? 0 : entry.getChangeInQuantity())
                .sum();

        return allocationCreated - allocationReleased;
    }
}
